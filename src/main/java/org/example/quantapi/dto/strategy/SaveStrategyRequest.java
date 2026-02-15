package org.example.quantapi.dto.strategy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SaveStrategyRequest(
        @NotEmpty(message = "strategySpec must not be empty")
        Map<String, Object> strategySpec,
        @NotNull(message = "tasks must not be null")
        Object tasks,
        @NotBlank(message = "xml must not be blank")
        String xml,
        String userId
) {
}
