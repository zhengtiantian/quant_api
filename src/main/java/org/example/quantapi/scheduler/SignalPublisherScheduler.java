package org.example.quantapi.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.quantapi.service.signal.SignalPublisherService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalPublisherScheduler {

    private final SignalPublisherService signalPublisherService;

    // Run at 09:30 on weekdays — after Airflow feature build (05:00) and LLM pipeline (09:00)
    @Scheduled(cron = "0 30 9 * * MON-FRI")
    public void publishDailySignals() {
        log.info("Starting daily signal publish job...");
        int count = signalPublisherService.publishLatestSignals();
        log.info("Daily signal publish complete: {} signals sent", count);
    }
}
