package org.example.quantapi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Date;

@Document(collection = "daily_signals")
public class DailySignal {

    @Id
    private String id;

    private String symbol;

    @Field("trade_date")
    private String tradeDate;

    @Field("composite_score")
    private Double compositeScore;

    @Field("signal_rank")
    private Integer signalRank;

    @Field("signal_type")
    private String signalType;

    @Field("quality_score")
    private Double qualityScore;

    @Field("news_burst_20d")
    private Double newsBurst20d;

    @Field("earnings_beat_signal")
    private Integer earningsBeatSignal;

    @Field("earnings_miss_signal")
    private Integer earningsMissSignal;

    @Field("avg_sentiment_5d")
    private Double avgSentiment5d;

    @Field("sentiment_shift_5d")
    private Double sentimentShift5d;

    @Field("top_n")
    private Integer topN;

    @Field("published_at")
    private Date publishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getTradeDate() { return tradeDate; }
    public void setTradeDate(String tradeDate) { this.tradeDate = tradeDate; }

    public Double getCompositeScore() { return compositeScore; }
    public void setCompositeScore(Double compositeScore) { this.compositeScore = compositeScore; }

    public Integer getSignalRank() { return signalRank; }
    public void setSignalRank(Integer signalRank) { this.signalRank = signalRank; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }

    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }

    public Double getNewsBurst20d() { return newsBurst20d; }
    public void setNewsBurst20d(Double newsBurst20d) { this.newsBurst20d = newsBurst20d; }

    public Integer getEarningsBeatSignal() { return earningsBeatSignal; }
    public void setEarningsBeatSignal(Integer earningsBeatSignal) { this.earningsBeatSignal = earningsBeatSignal; }

    public Integer getEarningsMissSignal() { return earningsMissSignal; }
    public void setEarningsMissSignal(Integer earningsMissSignal) { this.earningsMissSignal = earningsMissSignal; }

    public Double getAvgSentiment5d() { return avgSentiment5d; }
    public void setAvgSentiment5d(Double avgSentiment5d) { this.avgSentiment5d = avgSentiment5d; }

    public Double getSentimentShift5d() { return sentimentShift5d; }
    public void setSentimentShift5d(Double sentimentShift5d) { this.sentimentShift5d = sentimentShift5d; }

    public Integer getTopN() { return topN; }
    public void setTopN(Integer topN) { this.topN = topN; }

    public Date getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Date publishedAt) { this.publishedAt = publishedAt; }
}
