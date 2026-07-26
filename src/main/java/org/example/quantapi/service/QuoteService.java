package org.example.quantapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live US equity quotes from Finnhub, behind a TTL cache.
 *
 * <p>The free tier allows 60 requests per minute and serves one symbol per call, so a
 * portfolio page refreshing 20 symbols every few seconds would be rate-limited within
 * seconds. Every quote therefore goes through {@link #getQuote(String)}, which serves a
 * cached value until it is {@code QUOTE_TTL} old. The UI is free to poll as often as it
 * likes; only a cache miss reaches Finnhub.
 *
 * <p>Outside US market hours prices do not move, so the TTL stretches to
 * {@code CLOSED_TTL} and the quote is reported with {@code "market": "closed"} — the
 * caller can then label it a close rather than presenting a stale number as live.
 *
 * <p>If Finnhub is unreachable or unconfigured, the last daily close from
 * {@code stock_prices_history} is returned with {@code "source": "daily-close"} instead
 * of failing the whole page.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private static final Duration QUOTE_TTL = Duration.ofSeconds(20);
    private static final Duration CLOSED_TTL = Duration.ofMinutes(15);
    private static final ZoneId US_EAST = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    private final String finnhubToken;
    private final String quantDataUri;
    private final MongoClient mongo;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Map<String, Object> quote, long fetchedAtMs) {}

    public QuoteService(@Value("${quant.finnhub.token:}") String finnhubToken,
                        @Value("${quant.mongo.quant-data-uri}") String quantDataUri) {
        this.finnhubToken = finnhubToken == null ? "" : finnhubToken.trim();
        this.quantDataUri = quantDataUri;
        this.mongo = MongoClients.create(quantDataUri);
        if (this.finnhubToken.isEmpty()) {
            log.warn("quant.finnhub.token is not set — quotes will fall back to the last daily close");
        }
    }

    @PreDestroy
    void close() {
        mongo.close();
    }

    /** True while the US regular session is open (no holiday calendar). */
    public boolean marketOpen() {
        ZonedDateTime now = ZonedDateTime.now(US_EAST);
        int dow = now.getDayOfWeek().getValue();
        if (dow > 5) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return !t.isBefore(MARKET_OPEN) && t.isBefore(MARKET_CLOSE);
    }

    /**
     * Latest price for one symbol. Never throws — on any failure the map carries an
     * {@code error} key and a {@code price} of 0 so the caller can still render a row.
     */
    public Map<String, Object> getQuote(String symbol) {
        String sym = symbol == null ? "" : symbol.toUpperCase().trim();
        if (sym.isEmpty()) {
            return error(sym, "empty symbol");
        }

        long ttlMs = (marketOpen() ? QUOTE_TTL : CLOSED_TTL).toMillis();
        Cached hit = cache.get(sym);
        if (hit != null && System.currentTimeMillis() - hit.fetchedAtMs() < ttlMs) {
            return hit.quote();
        }

        Map<String, Object> quote = finnhubToken.isEmpty() ? null : fetchFinnhub(sym);
        if (quote == null) {
            quote = lastDailyClose(sym);
        }
        cache.put(sym, new Cached(quote, System.currentTimeMillis()));
        return quote;
    }

    /** Quotes for many symbols. Each is cached independently, so a warm cache costs nothing. */
    public Map<String, Map<String, Object>> getQuotes(Iterable<String> symbols) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String s : symbols) {
            String sym = s == null ? "" : s.toUpperCase().trim();
            if (!sym.isEmpty() && !out.containsKey(sym)) {
                out.put(sym, getQuote(sym));
            }
        }
        return out;
    }

    /** Returns null (not an exception) on any failure so the caller can fall back. */
    private Map<String, Object> fetchFinnhub(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://finnhub.io/api/v1/quote?symbol=" + symbol
                            + "&token=" + finnhubToken))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("finnhub quote {} -> HTTP {}", symbol, resp.statusCode());
                return null;
            }
            JsonNode n = json.readTree(resp.body());
            double current = n.path("c").asDouble(0);
            // Finnhub answers 200 with c=0 for an unknown symbol.
            if (current <= 0) {
                return null;
            }
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("symbol", symbol);
            q.put("price", current);
            q.put("previousClose", n.path("pc").asDouble(0));
            q.put("change", n.path("d").asDouble(0));
            q.put("changePct", n.path("dp").asDouble(0));
            q.put("dayHigh", n.path("h").asDouble(0));
            q.put("dayLow", n.path("l").asDouble(0));
            q.put("source", "finnhub");
            q.put("market", marketOpen() ? "open" : "closed");
            return q;
        } catch (Exception e) {
            log.warn("finnhub quote {} failed: {}", symbol, e.toString());
            return null;
        }
    }

    /** Fallback: newest close already collected into quant_data. */
    private Map<String, Object> lastDailyClose(String symbol) {
        try {
            Document d = mongo.getDatabase("quant_data")
                    .getCollection("stock_prices_history")
                    .find(new Document("symbol", symbol))
                    .sort(Sorts.descending("timestamp"))
                    .limit(1)
                    .first();
            if (d == null || d.get("close") == null) {
                return error(symbol, "no price data");
            }
            double close = ((Number) d.get("close")).doubleValue();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("symbol", symbol);
            q.put("price", close);
            q.put("previousClose", close);
            q.put("change", 0.0);
            q.put("changePct", 0.0);
            q.put("asOf", String.valueOf(d.get("timestamp")));
            q.put("source", "daily-close");
            q.put("market", "closed");
            return q;
        } catch (Exception e) {
            log.warn("daily-close fallback {} failed: {}", symbol, e.toString());
            return error(symbol, e.toString());
        }
    }

    private Map<String, Object> error(String symbol, String message) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        q.put("price", 0.0);
        q.put("previousClose", 0.0);
        q.put("change", 0.0);
        q.put("changePct", 0.0);
        q.put("source", "unavailable");
        q.put("market", marketOpen() ? "open" : "closed");
        q.put("error", message);
        return q;
    }
}
