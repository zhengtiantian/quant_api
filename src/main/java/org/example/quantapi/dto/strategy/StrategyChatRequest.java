package org.example.quantapi.dto.strategy;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record StrategyChatRequest(
        @NotBlank(message = "message must not be blank")
        String message,
        String userId,
        Map<String, Object> strategySpec
) {
}
