package org.example.quantapi.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads paper-trading positions and exit alerts from quant_data
 * (written by research/track_positions.py).
 */
@Service
public class PortfolioService {

    private final String quantDataUri;

    public PortfolioService(@Value("${quant.mongo.quant-data-uri}") String quantDataUri) {
        this.quantDataUri = quantDataUri;
    }

    /** Open positions first (best return first), then recently closed. */
    public List<Map<String, Object>> getPositions() {
        List<Map<String, Object>> open = new ArrayList<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        try (MongoClient client = MongoClients.create(quantDataUri)) {
            MongoCursor<Document> cur = client.getDatabase("quant_data")
                    .getCollection("positions").find().iterator();
            try (cur) {
                while (cur.hasNext()) {
                    Document d = cur.next();
                    ("open".equals(d.getString("status")) ? open : closed).add(clean(d));
                }
            }
        }
        open.sort((a, b) -> Double.compare(num(b.get("currentReturn")), num(a.get("currentReturn"))));
        closed.sort((a, b) -> str(b.get("exitDate")).compareTo(str(a.get("exitDate"))));
        open.addAll(closed);
        return open;
    }

    /**
     * Live paper-trading performance computed from the positions collection:
     * realized stats from closed trades (cumulative equity), unrealized from
     * open holdings, plus the most recent closed trades. This is the genuine
     * out-of-sample record that accumulates day by day (vs the backtest).
     */
    public Map<String, Object> getPaperPerformance() {
        List<Document> open = new ArrayList<>();
        List<Document> closed = new ArrayList<>();
        String firstEntry = null;
        try (MongoClient client = MongoClients.create(quantDataUri)) {
            MongoCursor<Document> cur = client.getDatabase("quant_data")
                    .getCollection("positions").find().iterator();
            try (cur) {
                while (cur.hasNext()) {
                    Document d = cur.next();
                    String ed = d.getString("entry_date");
                    if (ed != null && (firstEntry == null || ed.compareTo(firstEntry) < 0)) firstEntry = ed;
                    ("open".equals(d.getString("status")) ? open : closed).add(d);
                }
            }
        }
        closed.sort((a, b) -> str(a.get("exit_date")).compareTo(str(b.get("exit_date"))));

        double realizedSum = 0, daysSum = 0, equity = 1.0;
        int wins = 0;
        List<Map<String, Object>> curve = new ArrayList<>();
        for (Document c : closed) {
            double r = num(c.get("exit_return"));
            realizedSum += r;
            daysSum += num(c.get("days_held"));
            if (r > 0) wins++;
            equity *= (1 + r);
            curve.add(Map.of("exitDate", str(c.get("exit_date")),
                    "equity", Math.round(equity * 10000) / 10000.0,
                    "tradeReturn", r, "symbol", str(c.get("symbol"))));
        }
        double unrealizedSum = 0;
        for (Document o : open) unrealizedSum += num(o.get("current_return"));

        int nc = closed.size(), no = open.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("openCount", no);
        summary.put("closedCount", nc);
        summary.put("firstEntry", firstEntry);
        summary.put("realizedAvgReturn", nc > 0 ? realizedSum / nc : 0.0);
        summary.put("unrealizedAvgReturn", no > 0 ? unrealizedSum / no : 0.0);
        summary.put("winRate", nc > 0 ? (double) wins / nc : 0.0);
        summary.put("avgDaysHeld", nc > 0 ? daysSum / nc : 0.0);
        summary.put("cumulativeReturn", equity - 1.0);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = closed.size() - 1; i >= 0 && recent.size() < 10; i--) recent.add(clean(closed.get(i)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("equityCurve", curve);
        out.put("recentTrades", recent);
        return out;
    }

    /** Latest portfolio backtest: equity curve + headline stats (raw, _id dropped). */
    public Map<String, Object> getPerformance() {
        try (MongoClient client = MongoClients.create(quantDataUri)) {
            Document d = client.getDatabase("quant_data")
                    .getCollection("portfolio_performance")
                    .find(new Document("_id", "latest")).first();
            if (d == null) return Map.of();
            d.remove("_id");
            return d;
        }
    }

    /** Exit alerts, most recent first. */
    public List<Map<String, Object>> getAlerts(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (MongoClient client = MongoClients.create(quantDataUri)) {
            MongoCursor<Document> cur = client.getDatabase("quant_data")
                    .getCollection("alerts")
                    .find().sort(new Document("alert_date", -1)).limit(limit).iterator();
            try (cur) {
                while (cur.hasNext()) out.add(clean(cur.next()));
            }
        }
        return out;
    }

    /** Drop _id and convert snake_case keys to camelCase. */
    private Map<String, Object> clean(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if ("_id".equals(e.getKey())) continue;
            m.put(toCamel(e.getKey()), e.getValue());
        }
        return m;
    }

    private static String toCamel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
