package org.example.quantapi.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailySignalEvent {

    private String tradeDate;       // "2025-05-06"
    private String symbol;          // "NVDA"
    private double compositeScore;
    private int signalRank;
    private String signalType;      // "LONG" | "NEUTRAL"
    private int topN;

    // context features
    private Double avgSentiment5d;
    private Double sentimentShift5d;
    private int earningsBeatSignal;
    private int earningsMissSignal;
    private Double newsBurst20d;
    private Double qualityScore;

    // D-series context features
    private Double ahGap;
    private Double analystBuyRatio;
    private Double analystBuyRatioChg1m;
    private Double instHoldingPctChg;
    private Double retailSentScore;
    private Integer macroRiskOn;
    private Double macroVix;
    /**
     * RISK_ON / NEUTRAL / STRESSED / RISK_OFF, from classify_regime().
     *
     * <p>Only regimeMult was serialised before, which left every consumer able to see
     * that conviction had been scaled without being able to see why. The portfolio
     * review agent's regime check was structurally dead as a result: it read UNKNOWN on
     * every run and could never fire, even while the platform itself was in STRESSED.
     */
    private String regimeLabel;
    private Double regimeMult;

    private Instant publishedAt;
}
