package org.example.quantapi.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WorkflowController {

    @GetMapping("/api/workflows")
    public List<Map<String, String>> getWorkflows() {
        return List.of(
                Map.of("id", "1", "name", "Fetch Stock Data", "description", "从股票数据源抓取行情"),
                Map.of("id", "2", "name", "Train ML Model", "description", "训练量化预测模型"),
                Map.of("id", "3", "name", "Analyze News", "description", "分析财经新闻情绪")
        );
    }
}