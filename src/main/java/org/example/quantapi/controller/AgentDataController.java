package org.example.quantapi.controller;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only data endpoints backing the quant_ai research agent's tools.
 *
 * The agent (quant_ai/agent.py) calls these instead of querying MongoDB
 * directly, keeping data access behind the Java API layer. Uses the
 * quant_data database (same pattern as SignalPublisherService) — the default
 * Spring MongoTemplate points at quantdb, which does not hold these
 * collections.
 */
@RestController
@RequestMapping("/api/agent-data")
public class AgentDataController {

    private static final String NEWS_COLLECTION = "news_articles_company_matched_v2";
    private static final String FEATURES_COLLECTION = "daily_symbol_features";
    private static final DateTimeFormatter GKG_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MongoClient quantDataClient;

    public AgentDataController(@Value("${quant.mongo.quant-data-uri}") String quantDataUri) {
        this.quantDataClient = MongoClients.create(quantDataUri);
    }

    @PreDestroy
    public void close() {
        quantDataClient.close();
    }

    private MongoCollection<Document> collection(String name) {
        return quantDataClient.getDatabase("quant_data").getCollection(name);
    }

    /**
     * Aggregated LLM-labeled news sentiment for one symbol:
     * article count, average sentiment (-1..1), average model disagreement,
     * and the three most recent headlines inside the lookback window.
     */
    @GetMapping("/news/{symbol}/sentiment")
    public Map<String, Object> newsSentiment(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "90") int days) {

        String sym = symbol.toUpperCase();
        Document match = new Document("symbol", sym)
                .append("llm_sentiment_final", new Document("$exists", true));
        if (days > 0) {
            // article `date` is a "yyyyMMddHHmmss" string — lexicographic compare works
            String cutoff = LocalDateTime.now().minusDays(days).format(GKG_TS);
            match.append("date", new Document("$gte", cutoff));
        }

        List<Document> pipeline = List.of(
                new Document("$match", match),
                new Document("$group", new Document("_id", null)
                        .append("articles", new Document("$sum", 1))
                        .append("avgSentiment", new Document("$avg", "$llm_sentiment_final"))
                        .append("avgDisagreement", new Document("$avg", "$llm_disagreement"))));

        Document stats = null;
        for (Document d : collection(NEWS_COLLECTION).aggregate(pipeline)) {
            stats = d;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        if (stats == null) {
            out.put("articles", 0);
            out.put("note", "no labeled articles for this symbol in window");
            return out;
        }
        out.put("articles", stats.getInteger("articles", 0));
        out.put("avgSentiment", round4(stats.getDouble("avgSentiment")));
        out.put("avgModelDisagreement", round4(stats.getDouble("avgDisagreement")));

        List<Map<String, Object>> headlines = new ArrayList<>();
        collection(NEWS_COLLECTION)
                .find(match)
                .projection(new Document("title", 1).append("date", 1)
                        .append("llm_sentiment_final", 1).append("_id", 0))
                .sort(new Document("date", -1))
                .limit(3)
                .forEach(d -> headlines.add(new LinkedHashMap<>(d)));
        out.put("recentHeadlines", headlines);
        out.put("scale", "sentiment -1 (bearish) .. +1 (bullish); low disagreement = high model consensus");
        return out;
    }

    /** Latest engineered daily feature row for one symbol (trimmed to 25 fields). */
    @GetMapping("/features/{symbol}/latest")
    public Map<String, Object> latestFeatures(@PathVariable String symbol) {
        String sym = symbol.toUpperCase();
        Document doc = collection(FEATURES_COLLECTION)
                .find(new Document("symbol", sym))
                .projection(new Document("_id", 0))
                .sort(new Document("date", -1))
                .first();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        if (doc == null) {
            out.put("note", "no features computed yet for this symbol");
            return out;
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            if (i++ >= 25) break;
            slim.put(e.getKey(), e.getValue());
        }
        out.put("latestFeatures", slim);
        return out;
    }

    private static Double round4(Double v) {
        return v == null ? null : Math.round(v * 10000.0) / 10000.0;
    }
}
