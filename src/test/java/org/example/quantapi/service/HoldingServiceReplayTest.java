package org.example.quantapi.service;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the transaction-log fold behind the holdings page.
 *
 * <p>This is the part worth testing: a wrong average cost silently corrupts every P&L
 * number the user sees and acts on. {@code HoldingService.replay} is pure, so none of this
 * needs a database or a quote provider.
 */
class HoldingServiceReplayTest {

    private static final double EPS = 1e-6;

    private static Document trade(String symbol, String side, double qty, double price,
                                  String date, double fee) {
        return new Document("symbol", symbol)
                .append("side", side)
                .append("quantity", qty)
                .append("price", price)
                .append("tradeDate", date)
                .append("fee", fee);
    }

    private static Document buy(String symbol, double qty, double price, String date) {
        return trade(symbol, "BUY", qty, price, date, 0);
    }

    private static Document sell(String symbol, double qty, double price, String date) {
        return trade(symbol, "SELL", qty, price, date, 0);
    }

    @Test
    @DisplayName("a single buy sets quantity and average cost to the trade")
    void singleBuy() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(
                List.of(buy("NVDA", 10, 118.40, "2026-05-12")));

        HoldingService.Lot lot = lots.get("NVDA");
        assertNotNull(lot);
        assertEquals(10, lot.quantity, EPS);
        assertEquals(118.40, lot.avgCost, EPS);
        assertEquals(0, lot.realised, EPS);
        assertEquals(1, lot.tradeCount);
    }

    @Test
    @DisplayName("a second buy moves the average to the quantity-weighted mean")
    void weightedAverageAcrossBuys() {
        // 10 @ 100 then 30 @ 200 -> (1000 + 6000) / 40 = 175, not the 150 a plain mean gives.
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("AMD", 10, 100.00, "2026-01-05"),
                buy("AMD", 30, 200.00, "2026-02-05")));

        HoldingService.Lot lot = lots.get("AMD");
        assertEquals(40, lot.quantity, EPS);
        assertEquals(175.00, lot.avgCost, EPS);
    }

    @Test
    @DisplayName("a sell realises against the average and leaves it unchanged")
    void sellRealisesAgainstAverage() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("AAPL", 10, 100.00, "2026-01-05"),
                buy("AAPL", 10, 200.00, "2026-02-05"),   // avg 150
                sell("AAPL", 5, 180.00, "2026-03-05")));  // (180-150)*5 = 150

        HoldingService.Lot lot = lots.get("AAPL");
        assertEquals(15, lot.quantity, EPS);
        assertEquals(150.00, lot.avgCost, EPS, "selling must not move the average cost");
        assertEquals(150.00, lot.realised, EPS);
    }

    @Test
    @DisplayName("realised P&L uses the average at the time of each sell, not the final one")
    void realisedUsesAverageAtTimeOfSale() {
        // Sell at avg 100 first, then buy higher. Folding from the end state would price the
        // first sale against the later average of 150 and report a loss instead of a gain.
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("MSFT", 10, 100.00, "2026-01-05"),
                sell("MSFT", 5, 120.00, "2026-02-05"),   // (120-100)*5 = +100
                buy("MSFT", 5, 200.00, "2026-03-05")));  // avg now (5*100 + 5*200)/10 = 150

        HoldingService.Lot lot = lots.get("MSFT");
        assertEquals(10, lot.quantity, EPS);
        assertEquals(150.00, lot.avgCost, EPS);
        assertEquals(100.00, lot.realised, EPS);
    }

    @Test
    @DisplayName("selling the whole position zeroes quantity and average but keeps realised P&L")
    void fullExitKeepsRealised() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("TSLA", 10, 100.00, "2026-01-05"),
                sell("TSLA", 10, 130.00, "2026-02-05")));

        HoldingService.Lot lot = lots.get("TSLA");
        assertEquals(0, lot.quantity, EPS);
        assertEquals(0, lot.avgCost, EPS);
        assertEquals(300.00, lot.realised, EPS);
        assertNull(lot.warning);
    }

    @Test
    @DisplayName("a loss-making sell realises a negative number")
    void realisedLoss() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("INTC", 20, 50.00, "2026-01-05"),
                sell("INTC", 20, 40.00, "2026-02-05")));

        assertEquals(-200.00, lots.get("INTC").realised, EPS);
    }

    @Test
    @DisplayName("overselling is capped and flagged rather than producing a negative position")
    void overSellIsCappedAndFlagged() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("META", 5, 100.00, "2026-01-05"),
                sell("META", 50, 120.00, "2026-02-05")));

        HoldingService.Lot lot = lots.get("META");
        assertEquals(0, lot.quantity, EPS, "quantity must never go negative");
        assertEquals(100.00, lot.realised, EPS, "only the 5 actually held are realised");
        assertNotNull(lot.warning, "the user needs to know the log is inconsistent");
        assertTrue(lot.warning.contains("sell exceeds"));
    }

    @Test
    @DisplayName("symbols are folded independently and fees accumulate per symbol")
    void symbolsAreIndependent() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                trade("NVDA", "BUY", 10, 100.00, "2026-01-05", 1.50),
                trade("AMD", "BUY", 20, 50.00, "2026-01-06", 2.25),
                trade("NVDA", "BUY", 10, 140.00, "2026-01-07", 1.50)));

        assertEquals(2, lots.size());
        assertEquals(120.00, lots.get("NVDA").avgCost, EPS);
        assertEquals(3.00, lots.get("NVDA").fees, EPS);
        assertEquals(50.00, lots.get("AMD").avgCost, EPS);
        assertEquals(2.25, lots.get("AMD").fees, EPS);
    }

    @Test
    @DisplayName("a re-entry after a full exit starts a fresh average")
    void reEntryAfterFullExit() {
        Map<String, HoldingService.Lot> lots = HoldingService.replay(List.of(
                buy("GOOG", 10, 100.00, "2026-01-05"),
                sell("GOOG", 10, 150.00, "2026-02-05"),
                buy("GOOG", 4, 200.00, "2026-03-05")));

        HoldingService.Lot lot = lots.get("GOOG");
        assertEquals(4, lot.quantity, EPS);
        assertEquals(200.00, lot.avgCost, EPS, "the closed round must not drag the average");
        assertEquals(500.00, lot.realised, EPS);
    }

    @Test
    @DisplayName("an empty log yields no holdings")
    void emptyLog() {
        assertTrue(HoldingService.replay(List.of()).isEmpty());
    }
}
