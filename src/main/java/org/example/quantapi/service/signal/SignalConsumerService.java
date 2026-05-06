package org.example.quantapi.service.signal;

import lombok.extern.slf4j.Slf4j;
import org.example.quantapi.event.DailySignalEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SignalConsumerService {

    @KafkaListener(topics = "${quant.kafka.signal-topic}", groupId = "quant-signal-consumer")
    public void onSignal(DailySignalEvent event) {
        if ("LONG".equals(event.getSignalType())) {
            log.info("[SIGNAL] {} {} | rank=#{} score={:.4f} sentiment={} beat={}",
                    event.getTradeDate(), event.getSymbol(),
                    event.getSignalRank(), event.getCompositeScore(),
                    event.getAvgSentiment5d(), event.getEarningsBeatSignal());
        }
    }
}
