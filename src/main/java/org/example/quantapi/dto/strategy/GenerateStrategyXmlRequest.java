package org.example.quantapi.dto.strategy;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record GenerateStrategyXmlRequest(
        @NotEmpty(message = "strategySpec must not be empty")
        Map<String, Object> strategySpec,
        @NotNull(message = "tasks must not be null")
        Object tasks
) {
}
