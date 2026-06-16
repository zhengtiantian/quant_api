package org.example.quantapi.service.signal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.example.quantapi.event.DailySignalEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class SignalPublisherService {

    private final KafkaTemplate<String, DailySignalEvent> kafkaTemplate;
    private final String signalTopic;
    private final String quantDataUri;

    public SignalPublisherService(
            KafkaTemplate<String, DailySignalEvent> kafkaTemplate,
            @Value("${quant.kafka.signal-topic}") String signalTopic,
            @Value("${quant.mongo.quant-data-uri}") String quantDataUri) {
        this.kafkaTemplate = kafkaTemplate;
        this.signalTopic = signalTopic;
        this.quantDataUri = quantDataUri;
    }

    /**
     * Reads today's top-N signals from MongoDB daily_signals collection
     * and publishes each one to the Kafka topic.
     *
     * @return number of signals published
     */
    public int publishLatestSignals() {
        List<DailySignalEvent> signals = getLatestSignals();
        if (signals.isEmpty()) {
            log.warn("No signals found in daily_signals collection");
            return 0;
        }

        int published = 0;
        for (DailySignalEvent signal : signals) {
            String key = signal.getTradeDate() + "#" + signal.getSymbol();
            CompletableFuture<SendResult<String, DailySignalEvent>> future =
                    kafkaTemplate.send(signalTopic, key, signal);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish signal for {}: {}", key, ex.getMessage());
                } else {
                    log.debug("Published signal {} to partition {}",
                            key, result.getRecordMetadata().partition());
                }
            });
            published++;
        }

        log.info("Published {} signals to topic {}", published, signalTopic);
        return published;
    }

    /** Latest trade_date's ranked signals from quant_data.daily_signals. */
    public List<DailySignalEvent> getLatestSignals() {
        List<DailySignalEvent> events = new ArrayList<>();
        try (MongoClient client = MongoClients.create(quantDataUri)) {
            MongoCollection<Document> col =
                    client.getDatabase("quant_data").getCollection("daily_signals");

            // find the latest trade_date
            Document latestDoc = col.find()
                    .sort(new Document("trade_date", -1))
                    .limit(1)
                    .first();
            if (latestDoc == null) return events;

            Object latestDate = latestDoc.get("trade_date");

            // load all signals for that date
            try (MongoCursor<Document> cursor = col
                    .find(new Document("trade_date", latestDate))
                    .sort(new Document("signal_rank", 1))
                    .cursor()) {
                while (cursor.hasNext()) {
                    events.add(toEvent(cursor.next()));
                }
            }
        }
        return events;
    }

    private DailySignalEvent toEvent(Document doc) {
        // trade_date is stored as a "YYYY-MM-DD" string, but tolerate a Date too.
        Object rawDate = doc.get("trade_date");
        String tradeDateStr;
        if (rawDate instanceof Date d) {
            tradeDateStr = d.toInstant().toString().substring(0, 10);
        } else if (rawDate instanceof String s && s.length() >= 10) {
            tradeDateStr = s.substring(0, 10);
        } else if (rawDate != null) {
            tradeDateStr = rawDate.toString();
        } else {
            tradeDateStr = "unknown";
        }

        return DailySignalEvent.builder()
                .tradeDate(tradeDateStr)
                .symbol(doc.getString("symbol"))
                .compositeScore(getDouble(doc, "composite_score", 0.0))
                .signalRank(doc.getInteger("signal_rank", 0))
                .signalType(doc.getString("signal_type"))
                .topN(doc.getInteger("top_n", 10))
                .avgSentiment5d(getNullableDouble(doc, "avg_sentiment_5d"))
                .sentimentShift5d(getNullableDouble(doc, "sentiment_shift_5d"))
                .earningsBeatSignal(doc.getInteger("earnings_beat_signal", 0))
                .earningsMissSignal(doc.getInteger("earnings_miss_signal", 0))
                .newsBurst20d(getNullableDouble(doc, "news_burst_20d"))
                .qualityScore(getNullableDouble(doc, "quality_score"))
                .ahGap(getNullableDouble(doc, "ah_gap"))
                .analystBuyRatio(getNullableDouble(doc, "analyst_buy_ratio"))
                .analystBuyRatioChg1m(getNullableDouble(doc, "analyst_buy_ratio_chg_1m"))
                .instHoldingPctChg(getNullableDouble(doc, "inst_holding_pct_chg"))
                .retailSentScore(getNullableDouble(doc, "retail_sent_score"))
                .macroRiskOn(doc.getInteger("macro_risk_on"))
                .macroVix(getNullableDouble(doc, "macro_vix"))
                .regimeMult(getNullableDouble(doc, "regime_mult"))
                .publishedAt(Instant.now())
                .build();
    }

    private double getDouble(Document doc, String key, double defaultVal) {
        Object v = doc.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return defaultVal;
    }

    private Double getNullableDouble(Document doc, String key) {
        Object v = doc.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }
}
