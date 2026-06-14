package org.example.quantapi.controller;

import lombok.RequiredArgsConstructor;
import org.example.quantapi.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    /** Paper-trading positions (open holdings first, then closed). */
    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return portfolioService.getPositions();
    }

    /** Exit-trigger alerts, most recent first. */
    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return portfolioService.getAlerts(limit);
    }

    /** Portfolio backtest equity curve + stats. */
    @GetMapping("/performance")
    public Map<String, Object> performance() {
        return portfolioService.getPerformance();
    }

    /** Live paper-trading performance (out-of-sample) from the positions log. */
    @GetMapping("/paper-performance")
    public Map<String, Object> paperPerformance() {
        return portfolioService.getPaperPerformance();
    }
}
