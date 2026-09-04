package pl.myproject.kanbanproject2.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.myproject.kanbanproject2.config.JsonNullableConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of the request records is that these three bodies mean three different things.
 * Before, the first two were indistinguishable and both read as "leave the row alone", which is why
 * the frontend's row-delete workaround — {@code updateTaskRow(id, null)} — never detached anything.
 */
class PatchTaskRequestJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new org.openapitools.jackson.nullable.JsonNullableModule())
            .build();

    @Test
    @DisplayName("a property left out of the body is undefined, so the field is untouched")
    void absentPropertyIsUndefined() throws Exception {
        PatchTaskRequest request = mapper.readValue("{\"title\":\"renamed\"}", PatchTaskRequest.class);

        assertThat(request.row().isPresent()).isFalse();
        assertThat(request.deadline().isPresent()).isFalse();
        assertThat(request.title().get()).isEqualTo("renamed");
    }

    @Test
    @DisplayName("a property sent as null is present and null, which is a request to clear it")
    void explicitNullIsPresent() throws Exception {
        PatchTaskRequest request = mapper.readValue("{\"row\":null,\"deadline\":null}", PatchTaskRequest.class);

        assertThat(request.row().isPresent()).isTrue();
        assertThat(request.row().get()).isNull();
        assertThat(request.deadline().isPresent()).isTrue();
        assertThat(request.deadline().get()).isNull();
    }

    @Test
    @DisplayName("a nested association still arrives in the {\"id\": n} shape the client sends")
    void readsNestedIdReferences() throws Exception {
        PatchTaskRequest request = mapper.readValue("{\"column\":{\"id\":4}}", PatchTaskRequest.class);

        assertThat(request.column().get()).isEqualTo(new IdRef(4));
    }

    @Test
    @DisplayName("version is read straight off the body, and is null when the client omits it")
    void readsTheVersionWhenPresent() throws Exception {
        assertThat(mapper.readValue("{\"title\":\"x\"}", PatchTaskRequest.class).version()).isNull();
        assertThat(mapper.readValue("{\"title\":\"x\",\"version\":6}", PatchTaskRequest.class).version()).isEqualTo(6);
    }

    @Test
    @DisplayName("Boot's ObjectMapper picks the module up, so this holds for real requests too")
    void theApplicationMapperUnderstandsJsonNullable() {
        // Boot does not load Jackson modules off the service loader by default, so the module has to
        // be a bean — without JsonNullableConfiguration every field above would deserialize to null.
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(Jackson2AutoConfiguration.class))
                .withUserConfiguration(JsonNullableConfiguration.class)
                .run(context -> {
                    ObjectMapper applicationMapper = context.getBean(ObjectMapper.class);
                    PatchTaskRequest request =
                            applicationMapper.readValue("{\"row\":null}", PatchTaskRequest.class);

                    assertThat(request.row().isPresent()).isTrue();
                    assertThat(request.row().get()).isNull();
                });
    }
}
