package org.example.quantapi.dto.market;

import java.util.List;

public record StockChartResponseDto(
        String symbol,
        String range,
        String timezoneNote,
        String marketHoursBeijing,
        Double latestClose,
        Double changePct,
        List<PricePointDto> points
) {
}
