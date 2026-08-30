package pl.myproject.kanbanproject2.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Everything the catch-all used to answer 500 for that is actually the caller's mistake.
 *
 * <p>{@code GlobalExceptionHandler} does not extend {@code ResponseEntityExceptionHandler}, so its
 * {@code @ExceptionHandler(Exception.class)} caught Spring MVC's own request exceptions along with
 * everything else and reported each of them as a server fault. That matters beyond tidiness: a 500
 * is what an alert on the error rate fires on, so a client sending bad JSON in a loop read as an
 * outage. This asserts the statuses rather than the handler methods, because the status is the part
 * a client and a dashboard both see.
 *
 * <p>The routes below are a stand-in rather than a real controller: the behaviour under test is the
 * advice, and pinning it to one production route would make this test move whenever that route did.
 */
class ClientErrorStatusTest {

    record Body(String name, int size) {
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @PostMapping
        public String accept(@RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/{id}")
        public String byId(@PathVariable Integer id) {
            return String.valueOf(id);
        }

        @GetMapping("/search")
        public String search(@RequestParam String term) {
            return term;
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("a body that is not JSON is 400, not 500")
    void unparseableBodyIs400() throws Exception {
        mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\\\"name\\\":\\\"x\\\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("an empty body on a route that requires one is 400")
    void missingBodyIs400() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("a value of the wrong JSON type is 400")
    void wrongFieldTypeIs400() throws Exception {
        mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"size\":\"not a number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("the malformed-body message says nothing about the target type")
    void malformedBodyMessageLeaksNothing() throws Exception {
        var body = mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"size\":\"not a number\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("Body")
                .doesNotContain("size")
                .doesNotContain("com.fasterxml");
    }

    @Test
    @DisplayName("a path variable that will not convert is 400")
    void typeMismatchIs400() throws Exception {
        mvc.perform(get("/probe/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("a missing required parameter is 400 and names the parameter")
    void missingParameterIs400() throws Exception {
        mvc.perform(get("/probe/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Required parameter is missing: term"));
    }

    @Test
    @DisplayName("the wrong method on a mapped path is 405")
    void wrongMethodIs405() throws Exception {
        mvc.perform(delete("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("a content type nothing can read is 415")
    void unsupportedMediaTypeIs415() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /**
     * Raised by the resource handler rather than by dispatch, so it cannot be provoked through
     * standalone MockMvc - the handler is called directly instead of dropping the case.
     */
    @Test
    @DisplayName("an unmapped API path is 404")
    void noResourceIs404() {
        var response = new GlobalExceptionHandler()
                .handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/api/nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("a violation on a handler parameter is 400 and reports the constraint")
    void constraintViolationIs400() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new Constrained(""));
            var response = new GlobalExceptionHandler()
                    .handleConstraintViolation(new ConstraintViolationException(violations));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().message()).contains("email");
        }
    }

    @Test
    @DisplayName("a constraint violation carrying no violations still answers 400 with a message")
    void emptyConstraintViolationIs400() {
        var response = new GlobalExceptionHandler()
                .handleConstraintViolation(new ConstraintViolationException("none", Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("The request is not valid");
    }

    record Constrained(@jakarta.validation.constraints.NotBlank String email) {
    }
}
