package org.example.quantapi.controller;

import org.example.quantapi.dto.strategy.GenerateStrategySpecRequest;
import org.example.quantapi.dto.strategy.GenerateStrategyTasksRequest;
import org.example.quantapi.dto.strategy.GenerateStrategyXmlRequest;
import org.example.quantapi.dto.strategy.StrategyChatRequest;
import org.example.quantapi.dto.strategy.SaveStrategyRequest;
import org.example.quantapi.service.StrategyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping({"/api/v1/strategies", "/api/v1/workflows"})
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @PostMapping("/generate-spec")
    public ResponseEntity<Map<String, Object>> generateSpec(@Valid @RequestBody GenerateStrategySpecRequest request) {
        return ResponseEntity.ok(strategyService.generateSpec(request.prompt(), normalizeUserId(request.userId())));
    }

    @PostMapping("/generate-tasks")
    public ResponseEntity<List<Map<String, Object>>> generateTasks(@Valid @RequestBody GenerateStrategyTasksRequest request) {
        return ResponseEntity.ok(strategyService.generateTasks(request.strategySpec()));
    }

    @PostMapping({"/generate-xml", "/preview-xml"})
    public ResponseEntity<Map<String, Object>> generateXml(@Valid @RequestBody GenerateStrategyXmlRequest request) {
        return ResponseEntity.ok(strategyService.generateXml(request.strategySpec(), request.tasks()));
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@Valid @RequestBody SaveStrategyRequest request) {
        return ResponseEntity.ok(strategyService.saveWorkflow(
                request.strategySpec(),
                request.tasks(),
                request.xml(),
                normalizeUserId(request.userId())
        ));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody StrategyChatRequest request) {
        return ResponseEntity.ok(strategyService.chat(
                request.message(),
                normalizeUserId(request.userId()),
                request.strategySpec() == null ? Map.of() : request.strategySpec()
        ));
    }

    @GetMapping("/{strategyId}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String strategyId) {
        Map<String, Object> result = strategyService.getWorkflow(strategyId);
        if (Boolean.FALSE.equals(result.get("found"))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listByUser(@RequestParam(required = false) String userId) {
        return ResponseEntity.ok(strategyService.listWorkflows(normalizeUserId(userId)));
    }

    private static String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? "local-user" : userId;
    }
}
