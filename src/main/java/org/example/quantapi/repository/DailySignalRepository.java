package org.example.quantapi.repository;

import org.example.quantapi.model.DailySignal;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DailySignalRepository extends MongoRepository<DailySignal, String> {

    /** Most recent trade_date overall (ISO date strings sort chronologically). */
    DailySignal findTopByOrderByTradeDateDesc();

    /** All signals for a given trade_date, ranked best-first. */
    List<DailySignal> findByTradeDateOrderBySignalRankAsc(String tradeDate);
}
