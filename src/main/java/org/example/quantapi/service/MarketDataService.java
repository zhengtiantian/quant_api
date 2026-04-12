package org.example.quantapi.service;

import org.bson.Document;
import org.example.quantapi.dto.market.PricePointDto;
import org.example.quantapi.dto.market.StockChartResponseDto;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class MarketDataService {

    private static final String DB_NAME = "quant_data";
    private static final String PRICE_COLLECTION = "stock_prices_history";
    private static final String UNIVERSE_COLLECTION = "stock_universe";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final List<String> DEFAULT_SYMBOLS = Stream.of(
            "AAPL", "MSFT", "GOOGL", "AMZN", "INTC", "TSLA", "META",
            "NVDA", "QCOM", "AVGO", "ARM", "AMD", "MU", "DDOG"
    ).toList();

    private final MongoTemplate mongoTemplate;

    public MarketDataService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<String> getSymbols() {
        List<Document> docs = mongoTemplate
                .getMongoDatabaseFactory()
                .getMongoDatabase(DB_NAME)
                .getCollection(UNIVERSE_COLLECTION)
                .find()
                .projection(new Document("symbol", 1).append("_id", 0))
                .sort(new Document("symbol", 1))
                .into(new ArrayList<>());

        List<String> symbols = new ArrayList<>();
        for (Document doc : docs) {
            String symbol = doc.getString("symbol");
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol);
            }
        }
        return symbols.isEmpty() ? DEFAULT_SYMBOLS : symbols;
    }

    public StockChartResponseDto getStockChart(String symbol, String range) {
        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        String normalizedRange = normalizeRange(range);
        OffsetDateTime cutoff = rangeCutoff(normalizedRange);
        List<Document> docs = mongoTemplate
                .getMongoDatabaseFactory()
                .getMongoDatabase(DB_NAME)
                .getCollection(PRICE_COLLECTION)
                .find(new Document("symbol", normalizedSymbol).append("timestamp", new Document("$gte", cutoff.toString())))
                .sort(new Document("timestamp", 1))
                .into(new ArrayList<>());
        List<PricePointDto> points = new ArrayList<>();
        for (Document doc : docs) {
            points.add(new PricePointDto(
                    doc.getString("timestamp"),
                    toDouble(doc.get("open")),
                    toDouble(doc.get("high")),
                    toDouble(doc.get("low")),
                    toDouble(doc.get("close")),
                    toLong(doc.get("volume"))
            ));
        }

        Double latestClose = null;
        Double changePct = null;
        if (!points.isEmpty()) {
            PricePointDto first = points.get(0);
            PricePointDto last = points.get(points.size() - 1);
            latestClose = last.close();
            if (first.close() != null && first.close() != 0 && last.close() != null) {
                changePct = last.close() / first.close() - 1.0;
            }
        }

        return new StockChartResponseDto(
                normalizedSymbol,
                normalizedRange,
                nasdaqTimezoneNote(),
                marketHoursBeijing(),
                latestClose,
                changePct,
                points
        );
    }

    public Map<String, String> getNasdaqHoursBeijing() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("timezone", "Asia/Shanghai");
        result.put("daylight_saving", marketHoursForDate(ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, BEIJING)));
        result.put("standard_time", marketHoursForDate(ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, BEIJING)));
        result.put("current_note", nasdaqTimezoneNote());
        return result;
    }

    private String normalizeRange(String range) {
        if (range == null || range.isBlank()) {
            return "1Y";
        }
        return switch (range.toUpperCase()) {
            case "1W", "1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y" -> range.toUpperCase();
            default -> "1Y";
        };
    }

    private OffsetDateTime rangeCutoff(String range) {
        Document latest = mongoTemplate
                .getMongoDatabaseFactory()
                .getMongoDatabase(DB_NAME)
                .getCollection(PRICE_COLLECTION)
                .find()
                .sort(new Document("timestamp", -1))
                .limit(1)
                .first();
        OffsetDateTime latestTs = latest != null && latest.getString("timestamp") != null
                ? OffsetDateTime.parse(latest.getString("timestamp"))
                : OffsetDateTime.now();

        return switch (range) {
            case "1W" -> latestTs.minusWeeks(1);
            case "1M" -> latestTs.minusMonths(1);
            case "3M" -> latestTs.minusMonths(3);
            case "6M" -> latestTs.minusMonths(6);
            case "2Y" -> latestTs.minusYears(2);
            case "5Y" -> latestTs.minusYears(5);
            case "10Y" -> latestTs.minusYears(10);
            default -> latestTs.minusYears(1);
        };
    }

    private String nasdaqTimezoneNote() {
        ZonedDateTime nowBj = ZonedDateTime.now(BEIJING);
        return "按当前日期换算，北京时间 " + marketHoursForDate(nowBj);
    }

    private String marketHoursBeijing() {
        return "夏令时: 21:30-04:00 | 冬令时: 22:30-05:00";
    }

    private String marketHoursForDate(ZonedDateTime dateInBeijing) {
        ZonedDateTime newYorkDate = dateInBeijing.withZoneSameInstant(ZoneId.of("America/New_York"));
        ZonedDateTime openNy = newYorkDate
                .with(LocalTime.of(9, 30))
                .withSecond(0)
                .withNano(0);
        ZonedDateTime closeNy = newYorkDate
                .with(LocalTime.of(16, 0))
                .withSecond(0)
                .withNano(0);

        ZonedDateTime openBj = openNy.withZoneSameInstant(BEIJING);
        ZonedDateTime closeBj = closeNy.withZoneSameInstant(BEIJING);
        return String.format("%02d:%02d 开市，%s %02d:%02d 收市",
                openBj.getHour(),
                openBj.getMinute(),
                closeBj.toLocalDate().isAfter(openBj.toLocalDate()) ? "次日" : "当日",
                closeBj.getHour(),
                closeBj.getMinute());
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
