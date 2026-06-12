package org.example.quantapi.controller;

import lombok.RequiredArgsConstructor;
import org.example.quantapi.model.DailySignal;
import org.example.quantapi.repository.DailySignalRepository;
import org.example.quantapi.service.signal.SignalPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalPublisherService signalPublisherService;
    private final DailySignalRepository dailySignalRepository;

    /**
     * Manually trigger signal publishing (for testing / backfill).
     * POST /api/signals/publish
     */
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish() {
        int count = signalPublisherService.publishLatestSignals();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "signalsPublished", count
        ));
    }

    /**
     * Latest day's ranked signals for the dashboard.
     * GET /api/signals/latest
     */
    @GetMapping("/latest")
    public List<DailySignal> latest() {
        DailySignal newest = dailySignalRepository.findTopByOrderByTradeDateDesc();
        if (newest == null || newest.getTradeDate() == null) {
            return Collections.emptyList();
        }
        return dailySignalRepository.findByTradeDateOrderBySignalRankAsc(newest.getTradeDate());
    }

    /**
     * Ranked signals for a specific trade_date (YYYY-MM-DD).
     * GET /api/signals?date=2026-06-05
     */
    @GetMapping
    public List<DailySignal> byDate(@RequestParam(name = "date") String date) {
        return dailySignalRepository.findByTradeDateOrderBySignalRankAsc(date);
    }
}
