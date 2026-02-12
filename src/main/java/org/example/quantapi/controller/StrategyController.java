package org.example.quantapi.controller;

import org.example.quantapi.service.StrategyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/strategies", "/api/v1/workflows"})
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @PostMapping("/generate-spec")
    public ResponseEntity<Map<String, Object>> generateSpec(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", ""));
        String userId = String.valueOf(body.getOrDefault("userId", "local-user"));
        return ResponseEntity.ok(strategyService.generateSpec(prompt, userId));
    }

    @PostMapping("/generate-tasks")
    public ResponseEntity<List<Map<String, Object>>> generateTasks(@RequestBody Map<String, Object> workflowSpec) {
        return ResponseEntity.ok(strategyService.generateTasks(workflowSpec));
    }

    @PostMapping({"/generate-xml", "/preview-xml"})
    public ResponseEntity<Map<String, Object>> generateXml(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> workflowSpec = body.get("strategySpec") instanceof Map<?, ?> ss
                ? (Map<String, Object>) ss
                : body.get("workflowSpec") instanceof Map<?, ?> ws
                ? (Map<String, Object>) ws
                : Map.of();
        Object tasks = body.get("tasks");
        return ResponseEntity.ok(strategyService.generateXml(workflowSpec, tasks));
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> workflowSpec = body.get("strategySpec") instanceof Map<?, ?> ss
                ? (Map<String, Object>) ss
                : body.get("workflowSpec") instanceof Map<?, ?> ws
                ? (Map<String, Object>) ws
                : Map.of();
        Object tasks = body.get("tasks");
        String xml = String.valueOf(body.getOrDefault("xml", ""));
        String userId = String.valueOf(body.getOrDefault("userId", "local-user"));
        return ResponseEntity.ok(strategyService.saveWorkflow(workflowSpec, tasks, xml, userId));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        String userId = String.valueOf(body.getOrDefault("userId", "local-user"));
        @SuppressWarnings("unchecked")
        Map<String, Object> strategySpec = body.get("strategySpec") instanceof Map<?, ?> ss
                ? (Map<String, Object>) ss
                : Map.of();
        return ResponseEntity.ok(strategyService.chat(message, userId, strategySpec));
    }
}
