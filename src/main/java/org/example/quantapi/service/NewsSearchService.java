package org.example.quantapi.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-text search over the LLM-labeled news corpus in quant_data.
 *
 * <p>Every other view of this corpus is an aggregate: a caller could learn that a symbol
 * averaged +0.31 sentiment over 90 days but could not read one headline behind it, ask
 * what happened on the day of a drawdown, or tell whether an average rests on ten
 * articles or ten thousand. This turns the corpus from something that reports conclusions
 * into something that can be interrogated.
 *
 * <p>Backed by a weighted text index (`title` 10x, `content` 1x) so a keyword in the
 * headline outranks a passing mention in the body — the same "incidental mention"
 * distinction the labeling pipeline cares about.
 *
 * <p>Article bodies average 4.5 KB. Returning them whole would put ~90 KB into an LLM
 * context for a 20-result page, so results carry a bounded excerpt instead and the caller
 * follows the `url` when it needs the rest.
 *
 * <p>Uses the quant_data connection directly: the default MongoTemplate points at
 * `quantdb`, a different database.
 */
@Service
public class NewsSearchService {

    private static final String DB = "quant_data";
    private static final String COLLECTION = "news_articles_company_matched_v2";
    private static final int MAX_LIMIT = 50;
    private static final int EXCERPT_CHARS = 400;

    private final MongoClient mongo;

    public NewsSearchService(@Value("${quant.mongo.quant-data-uri}") String quantDataUri) {
        this.mongo = MongoClients.create(quantDataUri);
    }

    @PreDestroy
    void close() {
        mongo.close();
    }

    private MongoCollection<Document> articles() {
        return mongo.getDatabase(DB).getCollection(COLLECTION);
    }

    /**
     * Search labeled articles.
     *
     * @param query    keywords; blank falls back to a plain recency listing so a caller can
     *                 browse one symbol's coverage without inventing a search term
     * @param symbol   optional ticker filter
     * @param fromDate optional inclusive lower bound, YYYYMMDD
     * @param toDate   optional inclusive upper bound, YYYYMMDD
     * @param limit    capped at {@value #MAX_LIMIT}
     */
    public Map<String, Object> search(String query, String symbol,
                                      String fromDate, String toDate, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        String q = query == null ? "" : query.trim();
        boolean textSearch = !q.isEmpty();

        List<Bson> filters = new ArrayList<>();
        if (textSearch) {
            filters.add(Filters.text(q));
        }
        if (symbol != null && !symbol.isBlank()) {
            filters.add(Filters.eq("symbol", symbol.toUpperCase().trim()));
        }
        String from = normaliseDate(fromDate);
        String to = normaliseDate(toDate);
        if (from != null) {
            filters.add(Filters.gte("date", from));
        }
        if (to != null) {
            // `date` is a string in two shapes: 588K documents are YYYYMMDD and 263K are
            // YYYYMMDDHHMMSS. A plain lexical `<= "20260121"` therefore drops every
            // timestamped article from that same day, because "20260121150000" sorts
            // after "20260121". Padding the upper bound covers the whole day in both
            // shapes. The lower bound needs no such fix — it already sorts correctly.
            filters.add(Filters.lte("date", to + "999999"));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);

        // Only project what a caller can act on. `content` is fetched for the excerpt and
        // dropped before the result leaves this method.
        Bson projection = Projections.fields(
                Projections.include("symbol", "name", "title", "content", "date", "url",
                        "llm_sentiment_final", "llm_disagreement", "llm_event_type",
                        "llm_signal_strength"),
                Projections.excludeId());

        var find = articles().find(filter).projection(projection).limit(capped);
        if (textSearch) {
            // Rank by relevance; the weighted index puts headline hits above body mentions.
            find = find.projection(Projections.fields(projection,
                            Projections.metaTextScore("score")))
                    .sort(Sorts.metaTextScore("score"));
        } else {
            find = find.sort(Sorts.descending("date"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Document d : find) {
            results.add(toResult(d));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("symbol", symbol == null ? "" : symbol.toUpperCase().trim());
        out.put("from", from == null ? "" : from);
        out.put("to", to == null ? "" : to);
        out.put("returned", results.size());
        out.put("rankedBy", textSearch ? "relevance" : "date");
        out.put("articles", results);
        if (results.isEmpty()) {
            out.put("note", textSearch
                    ? "no labeled articles matched — try broader keywords or drop the date filter"
                    : "no labeled articles for this symbol in the window");
        }
        return out;
    }

    private Map<String, Object> toResult(Document d) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("symbol", d.getString("symbol"));
        r.put("company", d.getString("name"));
        r.put("date", d.getString("date"));
        r.put("title", d.getString("title"));
        r.put("excerpt", excerpt(d.getString("content")));
        r.put("sentiment", d.get("llm_sentiment_final"));
        r.put("modelDisagreement", d.get("llm_disagreement"));
        r.put("eventType", d.getString("llm_event_type"));
        r.put("signalStrength", d.getString("llm_signal_strength"));
        r.put("url", d.getString("url"));
        if (d.get("score") != null) {
            r.put("relevance", round(((Number) d.get("score")).doubleValue()));
        }
        return r;
    }

    /** Bounded excerpt, cut on a word boundary so it does not end mid-token. */
    private static String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String c = content.strip();
        if (c.length() <= EXCERPT_CHARS) {
            return c;
        }
        int cut = c.lastIndexOf(' ', EXCERPT_CHARS);
        return c.substring(0, cut > 0 ? cut : EXCERPT_CHARS) + "…";
    }

    /** Accepts YYYYMMDD or YYYY-MM-DD and returns the bare 8-digit form. */
    private static String normaliseDate(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim().replace("-", "");
        if (!s.matches("\\d{8}")) {
            throw new IllegalArgumentException("date must be YYYYMMDD or YYYY-MM-DD, got: " + v);
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
