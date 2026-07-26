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

    /**
     * Feature columns that must never leave this API.
     *
     * <p>{@code future_ret_*} are the training labels — the realised return over the next
     * N trading days — and they are populated on 189,668 of the 190,828 rows. Handing
     * them to a research agent is look-ahead leakage in its purest form: asked to analyse
     * a past date, the agent would be reading that date's actual future and could
     * "predict" it perfectly.
     *
     * <p>The latest-row endpoint happened to be safe already, but only because it keeps
     * the first 25 fields in document order and {@code future_ret_*} sorts after them.
     * That is an accident, not a guarantee — raising the cap or reordering the schema
     * would have started leaking silently. This blocks them by name instead.
     */
    private static final String LEAKING_FIELD_PREFIX = "future_ret_";

    /** A row carries 123 columns; these bound what one history call can pull into a context. */
    private static final int MAX_FIELDS = 12;
    private static final int MAX_DAYS = 365;

    private static boolean leaks(String field) {
        return field.startsWith(LEAKING_FIELD_PREFIX);
    }

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
            if (leaks(e.getKey())) continue;
            if (i++ >= 25) break;
            slim.put(e.getKey(), e.getValue());
        }
        out.put("latestFeatures", slim);
        return out;
    }

    /**
     * Feature time series for one symbol — the trend behind the latest row.
     *
     * <p>{@link #latestFeatures(String)} answers "what does this look like now"; it cannot
     * answer "is sentiment improving or deteriorating", which is usually the question worth
     * asking. Each row carries 123 columns, so a caller must name the fields it wants: 90
     * days of everything would be eleven thousand numbers.
     *
     * <p>Alongside the series, a per-field summary (first, last, change, min, max, mean)
     * so a caller can read the direction without walking every row.
     *
     * @param symbol ticker
     * @param fields comma-separated feature names; required, capped at {@value #MAX_FIELDS}
     * @param days   lookback in calendar days (default 90, capped at {@value #MAX_DAYS})
     */
    @GetMapping("/features/{symbol}/history")
    public Map<String, Object> featureHistory(
            @PathVariable String symbol,
            @RequestParam("fields") String fields,
            @RequestParam(name = "days", defaultValue = "90") int days) {

        String sym = symbol.toUpperCase().trim();
        List<String> requested = new ArrayList<>();
        for (String f : fields.split(",")) {
            String name = f.trim();
            if (name.isEmpty()) {
                continue;
            }
            // Reject rather than silently drop: a caller that asked for a leaking field
            // needs to learn why it is refused, not wonder where its column went.
            if (leaks(name)) {
                throw new IllegalArgumentException(
                        "field '" + name + "' is a forward-looking training label and is not "
                                + "available — returning it would be look-ahead leakage");
            }
            if (!name.matches("[a-zA-Z0-9_]{1,60}")) {
                throw new IllegalArgumentException("invalid field name: " + name);
            }
            if (!requested.contains(name)) {
                requested.add(name);
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("fields is required, e.g. fields=avg_sentiment_5d,past_ret_20d");
        }
        if (requested.size() > MAX_FIELDS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_FIELDS + " fields per call, got " + requested.size());
        }
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        String cutoff = java.time.LocalDate.now().minusDays(window).toString();

        Document projection = new Document("_id", 0).append("date", 1);
        requested.forEach(f -> projection.append(f, 1));

        List<Map<String, Object>> series = new ArrayList<>();
        for (Document d : collection(FEATURES_COLLECTION)
                .find(new Document("symbol", sym).append("date", new Document("$gte", cutoff)))
                .projection(projection)
                .sort(new Document("date", 1))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.getString("date"));
            requested.forEach(f -> row.put(f, d.get(f)));
            series.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("fields", requested);
        out.put("days", window);
        out.put("rows", series.size());
        if (series.isEmpty()) {
            out.put("note", "no feature rows for this symbol in the window — check the ticker "
                    + "or widen `days`");
            out.put("series", series);
            return out;
        }
        out.put("from", series.get(0).get("date"));
        out.put("to", series.get(series.size() - 1).get("date"));
        out.put("summary", summarise(requested, series));
        out.put("series", series);
        return out;
    }

    /** first / last / change / min / max / mean per field, ignoring nulls. */
    private static Map<String, Object> summarise(List<String> fields,
                                                 List<Map<String, Object>> series) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String f : fields) {
            List<Double> values = new ArrayList<>();
            for (Map<String, Object> row : series) {
                if (row.get(f) instanceof Number n) {
                    values.add(n.doubleValue());
                }
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("observations", values.size());
            if (values.isEmpty()) {
                // A field that is entirely null is worth saying out loud — otherwise a
                // caller reads "no trend" as a finding rather than as missing data.
                s.put("note", "no values in this window (feature not populated for this symbol)");
                summary.put(f, s);
                continue;
            }
            double first = values.get(0);
            double last = values.get(values.size() - 1);
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            s.put("first", round(first));
            s.put("last", round(last));
            s.put("change", round(last - first));
            s.put("min", round(min));
            s.put("max", round(max));
            s.put("mean", round(mean));
            summary.put(f, s);
        }
        return summary;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static Double round4(Double v) {
        return v == null ? null : Math.round(v * 10000.0) / 10000.0;
    }
}
