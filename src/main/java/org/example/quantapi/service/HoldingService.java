package org.example.quantapi.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's own holdings, derived from a transaction log.
 *
 * <p>Nothing here overlaps with {@code PortfolioService}: those "positions" are synthetic,
 * written by {@code track_positions.py}, which mechanically opens the day's top-5 signals.
 * This service tracks what is actually held.
 *
 * <p><b>Transactions are the source of truth; holdings are computed.</b> Storing a holding
 * row with an {@code avgCost} field would make it unmaintainable — edit the quantity and
 * there is no way to recompute what the average cost should now be, because the individual
 * buys are gone. Keeping the log instead means any single trade can be corrected or deleted
 * and every derived number follows.
 *
 * <p>Average cost is a running weighted average: a buy moves it, a sell does not. Selling
 * realises {@code (price - avgCost) * quantity} against the average at that moment, which
 * is why realised P&L has to be accumulated by replaying the log in trade order rather than
 * computed from the final state.
 */
@Service
public class HoldingService {

    private static final String DB = "quant_data";
    private static final String TX = "portfolio_transactions";
    private static final String CASH = "portfolio_cash";
    private static final String CASH_ID = "default";

    private final MongoClient mongo;
    private final QuoteService quoteService;

    public HoldingService(@Value("${quant.mongo.quant-data-uri}") String quantDataUri,
                          QuoteService quoteService) {
        this.mongo = MongoClients.create(quantDataUri);
        this.quoteService = quoteService;
    }

    @PreDestroy
    void close() {
        mongo.close();
    }

    private MongoCollection<Document> tx() {
        return mongo.getDatabase(DB).getCollection(TX);
    }

    private MongoCollection<Document> cash() {
        return mongo.getDatabase(DB).getCollection(CASH);
    }

    // =========================================================
    // Transactions — full CRUD, because any trade may be mis-keyed
    // =========================================================

    /** Every transaction, newest trade first. Optionally filtered to one symbol. */
    public List<Map<String, Object>> listTransactions(String symbol) {
        Document filter = (symbol == null || symbol.isBlank())
                ? new Document()
                : new Document("symbol", symbol.toUpperCase().trim());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : tx().find(filter).sort(Sorts.descending("tradeDate", "_id"))) {
            out.add(clean(d));
        }
        return out;
    }

    public Map<String, Object> addTransaction(Map<String, Object> body) {
        Document d = new Document()
                .append("symbol", requireSymbol(body.get("symbol")))
                .append("side", requireSide(body.get("side")))
                .append("quantity", requirePositive(body.get("quantity"), "quantity"))
                .append("price", requirePositive(body.get("price"), "price"))
                .append("tradeDate", requireDate(body.get("tradeDate")))
                .append("fee", optionalNonNegative(body.get("fee")))
                .append("note", body.get("note") == null ? "" : String.valueOf(body.get("note")))
                .append("createdAt", Instant.now().toString());
        tx().insertOne(d);
        return clean(d);
    }

    public Map<String, Object> updateTransaction(String id, Map<String, Object> body) {
        ObjectId oid = objectId(id);
        List<org.bson.conversions.Bson> sets = new ArrayList<>();
        if (body.containsKey("symbol"))    sets.add(Updates.set("symbol", requireSymbol(body.get("symbol"))));
        if (body.containsKey("side"))      sets.add(Updates.set("side", requireSide(body.get("side"))));
        if (body.containsKey("quantity"))  sets.add(Updates.set("quantity", requirePositive(body.get("quantity"), "quantity")));
        if (body.containsKey("price"))     sets.add(Updates.set("price", requirePositive(body.get("price"), "price")));
        if (body.containsKey("tradeDate")) sets.add(Updates.set("tradeDate", requireDate(body.get("tradeDate"))));
        if (body.containsKey("fee"))       sets.add(Updates.set("fee", optionalNonNegative(body.get("fee"))));
        if (body.containsKey("note"))      sets.add(Updates.set("note", String.valueOf(body.get("note"))));
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("no updatable field supplied");
        }
        sets.add(Updates.set("updatedAt", Instant.now().toString()));
        Document updated = tx().findOneAndUpdate(Filters.eq("_id", oid), Updates.combine(sets));
        if (updated == null) {
            throw new IllegalArgumentException("transaction not found: " + id);
        }
        return clean(tx().find(Filters.eq("_id", oid)).first());
    }

    public void deleteTransaction(String id) {
        if (tx().deleteOne(Filters.eq("_id", objectId(id))).getDeletedCount() == 0) {
            throw new IllegalArgumentException("transaction not found: " + id);
        }
    }

    // =========================================================
    // Cash
    // =========================================================

    public Map<String, Object> getCash() {
        Document d = cash().find(Filters.eq("_id", CASH_ID)).first();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", d == null ? 0.0 : num(d.get("amount")));
        out.put("currency", d == null ? "USD" : String.valueOf(d.getOrDefault("currency", "USD")));
        out.put("updatedAt", d == null ? null : d.get("updatedAt"));
        return out;
    }

    public Map<String, Object> setCash(Map<String, Object> body) {
        double amount = num(body.get("amount"));
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        String currency = body.get("currency") == null ? "USD"
                : String.valueOf(body.get("currency")).toUpperCase().trim();
        Document d = new Document("_id", CASH_ID)
                .append("amount", amount)
                .append("currency", currency)
                .append("updatedAt", Instant.now().toString());
        cash().replaceOne(Filters.eq("_id", CASH_ID), d, new ReplaceOptions().upsert(true));
        return getCash();
    }

    // =========================================================
    // Derived holdings
    // =========================================================

    /**
     * Replay the transaction log into current holdings, then price them.
     *
     * <p>Returns open holdings (largest market value first) plus portfolio totals. Symbols
     * whose net quantity has gone to zero are dropped from {@code holdings} but keep
     * contributing their realised P&L to the totals.
     */
    public Map<String, Object> getHoldings() {
        // Replay in trade order: realised P&L depends on the average cost at the time of
        // each sell, so it cannot be recovered from the end state.
        List<Document> log = new ArrayList<>();
        for (Document d : tx().find().sort(Sorts.ascending("tradeDate", "_id"))) {
            log.add(d);
        }

        Map<String, Lot> lots = replay(log);
        double realisedTotal = 0.0;
        double feesTotal = 0.0;
        for (Lot lot : lots.values()) {
            realisedTotal += lot.realised;
            feesTotal += lot.fees;
        }

        List<String> openSymbols = lots.entrySet().stream()
                .filter(e -> e.getValue().quantity > 0)
                .map(Map.Entry::getKey)
                .toList();
        Map<String, Map<String, Object>> quotes = quoteService.getQuotes(openSymbols);

        List<Map<String, Object>> holdings = new ArrayList<>();
        double holdingsValue = 0.0;
        double costBasis = 0.0;
        for (String sym : openSymbols) {
            Lot lot = lots.get(sym);
            Map<String, Object> q = quotes.get(sym);
            double price = q == null ? 0.0 : num(q.get("price"));
            double marketValue = price * lot.quantity;
            double cost = lot.avgCost * lot.quantity;
            holdingsValue += marketValue;
            costBasis += cost;

            Map<String, Object> h = new LinkedHashMap<>();
            h.put("symbol", sym);
            h.put("quantity", round(lot.quantity, 6));
            h.put("avgCost", round(lot.avgCost, 4));
            h.put("price", round(price, 4));
            h.put("costBasis", round(cost, 2));
            h.put("marketValue", round(marketValue, 2));
            h.put("unrealisedPnl", round(marketValue - cost, 2));
            h.put("unrealisedPnlPct", cost == 0 ? 0.0 : round((marketValue - cost) / cost * 100, 4));
            h.put("realisedPnl", round(lot.realised, 2));
            h.put("dayChangePct", q == null ? 0.0 : round(num(q.get("changePct")), 4));
            h.put("tradeCount", lot.tradeCount);
            h.put("quoteSource", q == null ? "unavailable" : q.get("source"));
            if (lot.warning != null) {
                h.put("warning", lot.warning);
            }
            holdings.add(h);
        }
        holdings.sort(Comparator.comparingDouble(h -> -num(h.get("marketValue"))));

        Map<String, Object> cashDoc = getCash();
        double cashAmount = num(cashDoc.get("amount"));
        double totalValue = holdingsValue + cashAmount;

        // Weight is a share of total capital including cash — a 5%-per-position rule is
        // meaningless measured against invested capital alone.
        for (Map<String, Object> h : holdings) {
            h.put("weightPct", totalValue == 0 ? 0.0
                    : round(num(h.get("marketValue")) / totalValue * 100, 4));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("holdingsValue", round(holdingsValue, 2));
        totals.put("cash", round(cashAmount, 2));
        totals.put("totalValue", round(totalValue, 2));
        totals.put("costBasis", round(costBasis, 2));
        totals.put("unrealisedPnl", round(holdingsValue - costBasis, 2));
        totals.put("unrealisedPnlPct", costBasis == 0 ? 0.0
                : round((holdingsValue - costBasis) / costBasis * 100, 4));
        totals.put("realisedPnl", round(realisedTotal, 2));
        totals.put("feesPaid", round(feesTotal, 2));
        totals.put("openPositions", holdings.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holdings", holdings);
        out.put("totals", totals);
        out.put("currency", cashDoc.get("currency"));
        out.put("marketOpen", quoteService.marketOpen());
        out.put("asOf", Instant.now().toString());
        return out;
    }

    /**
     * Fold a trade log into per-symbol state. Pure and package-private so the money maths
     * can be tested without a database — an average cost that is wrong by a cent makes
     * every P&L figure downstream wrong too.
     *
     * <p>The log must already be in trade order. A buy moves the running weighted average;
     * a sell does not, and realises against the average as it stood at that moment.
     */
    static Map<String, Lot> replay(List<Document> log) {
        Map<String, Lot> lots = new LinkedHashMap<>();
        for (Document t : log) {
            String sym = String.valueOf(t.get("symbol"));
            double qty = num(t.get("quantity"));
            double price = num(t.get("price"));
            boolean buy = "BUY".equalsIgnoreCase(String.valueOf(t.get("side")));
            Lot lot = lots.computeIfAbsent(sym, s -> new Lot());
            lot.fees += num(t.get("fee"));
            lot.tradeCount++;

            if (buy) {
                double newQty = lot.quantity + qty;
                lot.avgCost = newQty == 0 ? 0.0
                        : (lot.quantity * lot.avgCost + qty * price) / newQty;
                lot.quantity = newQty;
                continue;
            }

            // Selling more than is held means the log is wrong. Cap the sale rather than
            // producing a negative position, and mark the holding so the user can see why
            // the numbers look odd instead of silently trusting them.
            double sold = Math.min(qty, lot.quantity);
            if (sold < qty) {
                lot.warning = "sell exceeds recorded quantity — check the transaction log";
            }
            lot.realised += (price - lot.avgCost) * sold;
            lot.quantity -= sold;
            if (lot.quantity <= 1e-9) {
                lot.quantity = 0.0;
                lot.avgCost = 0.0;
            }
        }
        return lots;
    }

    /** Mutable running state while replaying one symbol's trades. */
    static final class Lot {
        double quantity;
        double avgCost;
        double realised;
        double fees;
        int tradeCount;
        String warning;
    }

    // =========================================================
    // Validation and conversion
    // =========================================================

    private static String requireSymbol(Object v) {
        String s = v == null ? "" : String.valueOf(v).toUpperCase().trim();
        if (!s.matches("[A-Z0-9.\\-]{1,12}")) {
            throw new IllegalArgumentException("symbol must be 1-12 chars of A-Z 0-9 . -");
        }
        return s;
    }

    private static String requireSide(Object v) {
        String s = v == null ? "" : String.valueOf(v).toUpperCase().trim();
        if (!s.equals("BUY") && !s.equals("SELL")) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        return s;
    }

    private static double requirePositive(Object v, String field) {
        double d = num(v);
        if (!(d > 0)) {
            throw new IllegalArgumentException(field + " must be greater than 0");
        }
        return d;
    }

    private static double optionalNonNegative(Object v) {
        if (v == null) {
            return 0.0;
        }
        double d = num(v);
        if (d < 0) {
            throw new IllegalArgumentException("fee must not be negative");
        }
        return d;
    }

    private static String requireDate(Object v) {
        String s = v == null ? "" : String.valueOf(v).trim();
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("tradeDate must be YYYY-MM-DD");
        }
        return s;
    }

    private static ObjectId objectId(String id) {
        try {
            return new ObjectId(id);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid transaction id: " + id);
        }
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? 0.0 : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    private static Map<String, Object> clean(Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (d == null) {
            return out;
        }
        out.put("id", String.valueOf(d.get("_id")));
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (!"_id".equals(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
