package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PinEvaluationRequest(
        @NotNull @Size(min = PinEvaluator.POINT_BYTES, max = PinEvaluator.POINT_BYTES) byte[] blinded) {
}
