package org.example.quantapi.repository;

import org.example.quantapi.model.StrategyWorkflow;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyWorkflowRepository extends MongoRepository<StrategyWorkflow, String> {
    Optional<StrategyWorkflow> findByStrategyId(String strategyId);
    List<StrategyWorkflow> findTop20BySavedByOrderBySavedAtDesc(String savedBy);
}
