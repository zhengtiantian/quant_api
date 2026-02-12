package org.example.quantapi.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StrategyService {

    private final Map<String, Map<String, Object>> workflowStore = new ConcurrentHashMap<>();

    public Map<String, Object> generateSpec(String prompt, String userId) {
        String strategyId = "stg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String safePrompt = (prompt == null || prompt.isBlank()) ? "default strategy prompt" : prompt.trim();
        String owner = (userId == null || userId.isBlank()) ? "local-user" : userId;

        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.add(task("fetch_market_data", "data_collection", "quant_data.stock_collector.price_collector.collector",
                List.of(), Map.of("symbols", List.of("AAPL", "MSFT"), "timeframe", "1d", "lookback_days", 365)));
        tasks.add(task("build_features", "feature_engineering", "quant_langchain.features.momentum",
                List.of("fetch_market_data"), Map.of("indicators", List.of("rsi", "macd"), "window", 14)));
        tasks.add(task("generate_signals", "signal_generation", "quant_langchain.signals.rule_engine",
                List.of("build_features"), Map.of("rule", safePrompt)));
        tasks.add(task("risk_control", "risk_management", "quant_langchain.risk.position_manager",
                List.of("generate_signals"), Map.of("max_position_size", 0.1, "stop_loss", 0.02)));
        tasks.add(task("backtest_strategy", "backtesting", "quant_langchain.backtest.engine",
                List.of("risk_control"), Map.of("initial_cash", 100000, "fee_bps", 5)));

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("processName", "Process/quant-trading/" + strategyId + "/Process_execute_strategy");
        process.put("type", "CREATE");
        List<Map<String, Object>> processTasks = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            String taskId = String.valueOf(t.get("taskId"));
            processTasks.add(Map.of(
                    "taskId", taskId,
                    "taskFileName", "Task_" + taskId + ".py",
                    "processPath", "/opt/quant_repository/Process/" + strategyId + "/Tasks",
                    "displayName", taskId
            ));
        }
        process.put("tasks", processTasks);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("strategyId", strategyId);
        spec.put("workflowId", strategyId); // backward compatibility
        spec.put("name", "Quant Strategy - " + strategyId);
        spec.put("description", safePrompt);
        spec.put("market", "us_equity");
        spec.put("owner", owner);
        spec.put("tasks", tasks);
        spec.put("processes", List.of(process));
        spec.put("risk", Map.of("max_drawdown", 0.2, "position_limit", 0.1));
        spec.put("backtest", Map.of("window", "2y", "rebalance", "daily"));
        spec.put("createdAt", Instant.now().toString());
        return spec;
    }

    public List<Map<String, Object>> generateTasks(Map<String, Object> workflowSpec) {
        List<Map<String, Object>> specTasks = asMapList(workflowSpec.get("tasks"));
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> task : specTasks) {
            String taskId = String.valueOf(task.getOrDefault("taskId", "task_unknown"));
            String taskType = String.valueOf(task.getOrDefault("type", "generic"));
            String module = String.valueOf(task.getOrDefault("module", ""));

            String code = """
                    import json
                    from typing import Any, Dict

                    def run(context: Dict[str, Any]) -> Dict[str, Any]:
                        \"\"\"Auto-generated task from WorkflowSpec.\"\"\"
                        params = context.get("params", {})
                        print("Running task: %s")
                        print("Task type: %s")
                        print("Module: %s")
                        return {
                            "task_id": "%s",
                            "status": "SUCCESS",
                            "params": params
                        }
                    """.formatted(taskId, taskType, module, taskId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", taskId);
            item.put("taskType", taskType);
            item.put("module", module);
            item.put("fileName", "Task_" + taskId + ".py");
            item.put("code", code);
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> generateXml(Map<String, Object> workflowSpec, Object taskCodesObj) {
        String strategyId = extractStrategyId(workflowSpec);
        String workflowName = String.valueOf(workflowSpec.getOrDefault("name", strategyId));
        List<Map<String, Object>> processes = asMapList(workflowSpec.get("processes"));
        List<Map<String, Object>> taskCodes = asMapList(taskCodesObj);

        StringBuilder xml = new StringBuilder();
        xml.append("<ObjectDefinition>\n");
        xml.append("  <information>\n");
        xml.append("    <name>").append(escapeXml(workflowName)).append("</name>\n");
        xml.append("    <description>").append(escapeXml(String.valueOf(workflowSpec.getOrDefault("description", "")))).append("</description>\n");
        xml.append("    <serviceTaskType>python</serviceTaskType>\n");
        xml.append("  </information>\n");

        if (!processes.isEmpty()) {
            for (Map<String, Object> process : processes) {
                xml.append("  <process name=\"").append(escapeXml(String.valueOf(process.getOrDefault("processName", "Process/" + strategyId)))).append("\">\n");
                xml.append("    <type>").append(escapeXml(String.valueOf(process.getOrDefault("type", "CREATE")))).append("</type>\n");
                List<Map<String, Object>> processTasks = asMapList(process.get("tasks"));
                for (Map<String, Object> t : processTasks) {
                    xml.append("    <task name=\"").append(escapeXml(String.valueOf(t.getOrDefault("taskFileName", "Task_unknown.py")))).append("\">\n");
                    xml.append("      <processPath>").append(escapeXml(String.valueOf(t.getOrDefault("processPath", "/opt/quant_repository/Process/" + strategyId + "/Tasks")))).append("</processPath>\n");
                    xml.append("      <displayName>").append(escapeXml(String.valueOf(t.getOrDefault("displayName", "task")))).append("</displayName>\n");
                    xml.append("    </task>\n");
                }
                xml.append("  </process>\n");
            }
        } else {
            xml.append("  <process name=\"Process/quant-trading/").append(escapeXml(strategyId)).append("/Process_execute_strategy\">\n");
            xml.append("    <type>CREATE</type>\n");
            for (Map<String, Object> t : taskCodes) {
                xml.append("    <task name=\"").append(escapeXml(String.valueOf(t.getOrDefault("fileName", "Task_unknown.py")))).append("\">\n");
                xml.append("      <processPath>/opt/quant_repository/Process/").append(escapeXml(strategyId)).append("/Tasks</processPath>\n");
                xml.append("      <displayName>").append(escapeXml(String.valueOf(t.getOrDefault("taskId", "task")))).append("</displayName>\n");
                xml.append("    </task>\n");
            }
            xml.append("  </process>\n");
        }
        xml.append("</ObjectDefinition>");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyId", strategyId);
        result.put("workflowId", strategyId); // backward compatibility
        result.put("xml", xml.toString());
        return result;
    }

    public Map<String, Object> saveWorkflow(Map<String, Object> workflowSpec, Object tasksObj, String xml, String userId) {
        String strategyId = extractStrategyId(workflowSpec);
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("strategyId", strategyId);
        persisted.put("workflowId", strategyId);
        persisted.put("workflowSpec", workflowSpec);
        persisted.put("tasks", tasksObj);
        persisted.put("xml", xml);
        persisted.put("savedBy", (userId == null || userId.isBlank()) ? "local-user" : userId);
        persisted.put("savedAt", Instant.now().toString());
        workflowStore.put(strategyId, persisted);

        return Map.of(
                "success", true,
                "strategyId", strategyId,
                "workflowId", strategyId,
                "savedAt", persisted.get("savedAt"),
                "savedBy", persisted.get("savedBy")
        );
    }

    public Map<String, Object> chat(String message, String userId, Map<String, Object> strategySpec) {
        String safeMsg = message == null ? "" : message.trim();
        if (safeMsg.isEmpty()) {
            return Map.of(
                    "role", "assistant",
                    "message", "请先输入你的问题，例如：优化止损参数、增加风险控制、或解释当前策略流程。"
            );
        }

        String strategyId = extractStrategyId(strategySpec);
        int taskCount = asMapList(strategySpec.get("tasks")).size();
        String lower = safeMsg.toLowerCase(Locale.ROOT);

        String response;
        if (lower.contains("止损") || lower.contains("stop") || lower.contains("risk")) {
            response = """
                    建议先做这三项风控增强：
                    1. 将 `max_position_size` 从 10%% 下调到 5%%。
                    2. 增加波动率过滤（高波动时减仓）。
                    3. 在回测里加入滑点和交易成本敏感性分析。
                    """;
        } else if (lower.contains("回测") || lower.contains("backtest")) {
            response = """
                    回测建议：
                    1. 使用滚动窗口（例如 2 年训练 + 6 个月验证）。
                    2. 输出年化收益、最大回撤、夏普比率、胜率。
                    3. 对不同市场阶段做分段评估（震荡/趋势）。
                    """;
        } else if (lower.contains("解释") || lower.contains("explain") || lower.contains("流程")) {
            response = "当前策略 `" + strategyId + "` 包含 " + taskCount + " 个任务，主链路是 数据获取 -> 特征构建 -> 信号生成 -> 风控 -> 回测。";
        } else {
            response = """
                    我可以帮你做三类策略辅助：
                    1. 需求澄清：把自然语言转成可执行策略结构。
                    2. 参数优化：给出阈值、窗口、仓位控制建议。
                    3. 结果解读：解释 StrategySpec / Tasks / XML 的含义。
                    你可以继续说：`请把止损改成2%%并解释影响`。
                    """;
        }

        return Map.of(
                "role", "assistant",
                "message", response.trim(),
                "userId", (userId == null || userId.isBlank()) ? "local-user" : userId,
                "strategyId", strategyId
        );
    }

    private static String extractStrategyId(Map<String, Object> spec) {
        Object value = spec.get("strategyId");
        if (value == null || String.valueOf(value).isBlank()) {
            value = spec.get("workflowId");
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return "stg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> task(String taskId, String type, String module, List<String> deps, Map<String, Object> parameters) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("type", type);
        out.put("module", module);
        out.put("dependencies", deps);
        out.put("parameters", parameters);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object it : list) {
            if (it instanceof Map<?, ?> raw) {
                Map<String, Object> cast = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    cast.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(cast);
            }
        }
        return out;
    }

    private static String escapeXml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
