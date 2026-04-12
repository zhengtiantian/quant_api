package org.example.quantapi.controller;

import org.example.quantapi.dto.market.StockChartResponseDto;
import org.example.quantapi.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/symbols")
    public List<String> getSymbols() {
        return marketDataService.getSymbols();
    }

    @GetMapping("/stocks/{symbol}/chart")
    public StockChartResponseDto getStockChart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1Y") String range
    ) {
        return marketDataService.getStockChart(symbol, range);
    }

    @GetMapping("/nasdaq-hours")
    public Map<String, String> getNasdaqHoursBeijing() {
        return marketDataService.getNasdaqHoursBeijing();
    }
}
