package org.example.quantapi.dto.market;

public record PricePointDto(
        String timestamp,
        Double open,
        Double high,
        Double low,
        Double close,
        Long volume
) {
}
