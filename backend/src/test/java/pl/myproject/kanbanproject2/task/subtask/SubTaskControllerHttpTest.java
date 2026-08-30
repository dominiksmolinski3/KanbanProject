package pl.myproject.kanbanproject2.task.subtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP half of the subtask routes: the statuses, and the point at which a partial body stops
 * being JSON and becomes a {@link PatchSubTaskRequest} whose absent fields are distinguishable
 * from explicitly-null ones. That distinction is only observable through a real deserialisation,
 * which is why it is asserted here rather than in the service test.
 */
class SubTaskControllerHttpTest {

    private SubTaskService subTaskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        subTaskService = mock(SubTaskService.class);

        var converter = new MappingJackson2HttpMessageConverter(
                new ObjectMapper().registerModule(new JsonNullableModule()));
        mvc = MockMvcBuilders.standaloneSetup(new SubTaskController(subTaskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private static SubTaskDto dto(Integer id, String title) {
        return new SubTaskDto(id, title, "described", false, 1, 7);
    }

    @Test
    @DisplayName("the listing answers 200 with the mapped DTOs")
    void listAnswers200() throws Exception {
        when(subTaskService.getAllSubTasks()).thenReturn(List.of(dto(1, "first")));

        mvc.perform(get("/subtasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("first"))
                .andExpect(jsonPath("$[0].taskId").value(7));
    }

    @Test
    @DisplayName("creating answers 201")
    void createAnswers201() throws Exception {
        when(subTaskService.addSubTask(any())).thenReturn(dto(1, "new"));

        mvc.perform(post("/subtasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\",\"task\":{\"id\":7}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("a blank title is a 400 from bean validation, before the service is reached")
    void blankTitleIs400() throws Exception {
        mvc.perform(post("/subtasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(subTaskService, org.mockito.Mockito.never()).addSubTask(any());
    }

    @Test
    @DisplayName("a nested task reference without an id is a 400, not a null dereference")
    void taskReferenceNeedsAnId() throws Exception {
        mvc.perform(post("/subtasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ok\",\"task\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("deleting answers 204, and a missing subtask answers 404")
    void deleteStatuses() throws Exception {
        mvc.perform(delete("/subtasks/1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        doThrow(new GlobalException(ExceptionIdentifier.SUBTASK_NOT_FOUND))
                .when(subTaskService).deleteSubTask(404);

        mvc.perform(delete("/subtasks/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBTASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("a body naming only description arrives with the other fields undefined")
    void partialPatchKeepsTheOtherFieldsUndefined() throws Exception {
        when(subTaskService.patchSubTask(eq(1), any())).thenReturn(dto(1, "unchanged"));

        mvc.perform(patch("/subtasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"only this\"}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(PatchSubTaskRequest.class);
        verify(subTaskService).patchSubTask(eq(1), captor.capture());

        var request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.description().isPresent()).isTrue();
        org.assertj.core.api.Assertions.assertThat(request.description().get()).isEqualTo("only this");
        org.assertj.core.api.Assertions.assertThat(request.completed().isPresent()).isFalse();
        org.assertj.core.api.Assertions.assertThat(request.title().isPresent()).isFalse();
        org.assertj.core.api.Assertions.assertThat(request.position().isPresent()).isFalse();
        org.assertj.core.api.Assertions.assertThat(request.task().isPresent()).isFalse();
    }

    @Test
    @DisplayName("an explicit null arrives as present-and-null, which is a different instruction")
    void explicitNullIsPresent() throws Exception {
        when(subTaskService.patchSubTask(eq(1), any())).thenReturn(dto(1, "unchanged"));

        mvc.perform(patch("/subtasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(PatchSubTaskRequest.class);
        verify(subTaskService).patchSubTask(eq(1), captor.capture());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().description().isPresent()).isTrue();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().description().get()).isNull();
    }

    @Test
    @DisplayName("a service-level IllegalArgumentException reaches the client as 400 BAD_REQUEST")
    void serviceRefusalIs400() throws Exception {
        when(subTaskService.patchSubTask(eq(1), any()))
                .thenThrow(new IllegalArgumentException("A subtask title cannot be blank"));

        mvc.perform(patch("/subtasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("the assignment, toggle, position and by-task routes reach the service as mapped")
    void remainingRoutes() throws Exception {
        when(subTaskService.assignTaskToSubTask(1, 7)).thenReturn(dto(1, "a"));
        when(subTaskService.toggleSubTaskCompletion(1)).thenReturn(dto(1, "a"));
        when(subTaskService.updateSubTaskPosition(1, 5)).thenReturn(dto(1, "a"));
        when(subTaskService.getSubTasksByTaskId(7)).thenReturn(List.of(dto(1, "a")));
        when(subTaskService.getSubTaskById(1)).thenReturn(dto(1, "a"));

        mvc.perform(put("/subtasks/1/task/7")).andExpect(status().isOk());
        mvc.perform(patch("/subtasks/1/change")).andExpect(status().isOk());
        mvc.perform(patch("/subtasks/1/position/5")).andExpect(status().isOk());
        mvc.perform(get("/subtasks/task/7")).andExpect(status().isOk());
        mvc.perform(get("/subtasks/1")).andExpect(status().isOk());

        verify(subTaskService).assignTaskToSubTask(1, 7);
        verify(subTaskService).updateSubTaskPosition(1, 5);
    }
}
