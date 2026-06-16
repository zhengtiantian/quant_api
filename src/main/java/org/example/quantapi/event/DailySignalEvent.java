package org.example.quantapi.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Date;

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
    private Double regimeMult;

    private Instant publishedAt;
}
