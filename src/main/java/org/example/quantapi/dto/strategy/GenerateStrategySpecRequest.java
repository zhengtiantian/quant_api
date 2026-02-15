package org.example.quantapi.dto.strategy;

import jakarta.validation.constraints.NotBlank;

public record GenerateStrategySpecRequest(
        @NotBlank(message = "prompt must not be blank")
        String prompt,
        String userId
) {
}
