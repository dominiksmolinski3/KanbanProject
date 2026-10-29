package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import pl.myproject.kanbanproject2.chat.Chat;
import pl.myproject.kanbanproject2.task.flow.FlowMetricsDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTimesAreInstantsTest {
    private static final String BASE_PACKAGE = "pl.myproject.kanbanproject2";

    private static List<Class<?>> productionClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);

        var classes = new ArrayList<Class<?>>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                var source = type.getProtectionDomain().getCodeSource();
                if (source == null || !source.getLocation().getPath().contains("test-classes")) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but could not load " + definition.getBeanClassName(), e);
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    @Test
    @DisplayName("the scan finds the production classes, nested records included, and no tests")
    void theScanFindsTheProductionClasses() {
        assertThat(productionClasses())
                .hasSizeGreaterThan(200)
                .contains(Chat.class, FlowMetricsDto.CycleTimeSample.class)
                .noneMatch(type -> type.getSimpleName().endsWith("Test"));
    }

    @Test
    @DisplayName("no field the API stores or answers with is a wall-clock time with no zone")
    void noFieldIsALocalDateTime() {
        List<String> zoneless = productionClasses().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields())
                        .filter(field -> field.getType() == LocalDateTime.class)
                        .map(field -> type.getName() + "." + field.getName()))
                .toList();

        assertThat(zoneless)
                .as("a LocalDateTime is written in the server's zone and read by each browser in its own; "
                        + "store and send an Instant")
                .isEmpty();
    }
}
