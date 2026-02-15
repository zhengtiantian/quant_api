package org.example.quantapi.dto.strategy;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record GenerateStrategyTasksRequest(
        @NotEmpty(message = "strategySpec must not be empty")
        Map<String, Object> strategySpec
) {
}
