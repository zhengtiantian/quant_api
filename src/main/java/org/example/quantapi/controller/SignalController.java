package org.example.quantapi.controller;

import lombok.RequiredArgsConstructor;
import org.example.quantapi.event.DailySignalEvent;
import org.example.quantapi.service.signal.SignalPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalPublisherService signalPublisherService;

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
     * Reads quant_data.daily_signals via SignalPublisherService.
     * GET /api/signals/latest
     */
    @GetMapping("/latest")
    public List<DailySignalEvent> latest() {
        return signalPublisherService.getLatestSignals();
    }
}
