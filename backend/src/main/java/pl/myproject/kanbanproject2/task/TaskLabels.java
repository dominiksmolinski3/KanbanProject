package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.util.Collection;

public final class TaskLabels {
    public static final int MAX_COUNT = 20;
    // task_labels.label is varchar(255): a longer one reaches the database and fails there.
    public static final int MAX_LENGTH = 255;

    private TaskLabels() {
    }

    public static void requireValid(Collection<String> labels) {
        if (labels == null) {
            return;
        }
        if (labels.size() > MAX_COUNT) {
            throw invalid("Zadanie może mieć najwyżej " + MAX_COUNT + " etykiet");
        }
        for (String label : labels) {
            if (label == null || label.isBlank() || label.length() > MAX_LENGTH) {
                throw invalid("Etykieta musi mieć od 1 do " + MAX_LENGTH + " znaków");
            }
        }
    }

    private static GlobalException invalid(String message) {
        return new GlobalException(ExceptionIdentifier.INVALID_LABELS, message);
    }
}
