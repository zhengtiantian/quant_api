package org.example.quantapi.controller;

import lombok.RequiredArgsConstructor;
import org.example.quantapi.service.signal.SignalPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
