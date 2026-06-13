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
