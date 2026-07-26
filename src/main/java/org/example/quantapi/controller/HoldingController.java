package org.example.quantapi.controller;

import lombok.RequiredArgsConstructor;
import org.example.quantapi.service.HoldingService;
import org.example.quantapi.service.QuoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The user's own portfolio: a freely editable transaction log, a cash balance, and the
 * holdings derived from them with live prices.
 *
 * <p>Distinct from {@code /api/positions}, which serves the synthetic positions that
 * {@code track_positions.py} generates from the daily top-5 signals.
 */
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class HoldingController {

    private final HoldingService holdingService;
    private final QuoteService quoteService;

    /** Current holdings with live prices, plus portfolio totals. Safe to poll. */
    @GetMapping("/holdings")
    public Map<String, Object> holdings() {
        return holdingService.getHoldings();
    }

    /** Raw transaction log, newest trade first; optionally one symbol. */
    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(
            @RequestParam(name = "symbol", required = false) String symbol) {
        return holdingService.listTransactions(symbol);
    }

    /** Record a trade: symbol, side (BUY/SELL), quantity, price, tradeDate, [fee], [note]. */
    @PostMapping("/transactions")
    public Map<String, Object> addTransaction(@RequestBody Map<String, Object> body) {
        return holdingService.addTransaction(body);
    }

    /** Correct any field of one trade. */
    @PatchMapping("/transactions/{id}")
    public Map<String, Object> updateTransaction(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        return holdingService.updateTransaction(id, body);
    }

    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable String id) {
        holdingService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cash")
    public Map<String, Object> cash() {
        return holdingService.getCash();
    }

    /** Set the cash balance outright — it is a number the user owns, not a ledger. */
    @PutMapping("/cash")
    public Map<String, Object> setCash(@RequestBody Map<String, Object> body) {
        return holdingService.setCash(body);
    }

    /** Ad-hoc quote passthrough (cached), handy for the add-trade form. */
    @GetMapping("/quote")
    public Map<String, Object> quote(@RequestParam("symbol") String symbol) {
        return quoteService.getQuote(symbol);
    }
}
