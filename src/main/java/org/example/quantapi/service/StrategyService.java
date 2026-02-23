package org.example.quantapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.quantapi.model.StrategyWorkflow;
import org.example.quantapi.repository.StrategyWorkflowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StrategyService {
    private static final List<String> CANONICAL_TASK_ORDER = List.of(
            "fetch_market_data", "build_features", "generate_signals", "risk_control", "backtest_strategy"
    );
    private static final List<String> CANONICAL_TASK_ORDER_WITH_SENTIMENT = List.of(
            "fetch_market_data", "fetch_news_sentiment", "build_features", "generate_signals", "risk_control", "backtest_strategy"
    );

    private final StrategyWorkflowRepository strategyWorkflowRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, Map<String, Object>> workflowStore = new ConcurrentHashMap<>();
    private final String langchainApi;
    private final List<Map<String, Object>> moduleCatalog = List.of(
            Map.of(
                    "taskId", "fetch_market_data",
                    "type", "data_collection",
                    "module", "quant_data.stock_collector.price_collector.collector",
                    "requiredParams", List.of("symbols", "timeframe", "lookback_days")
            ),
            Map.of(
                    "taskId", "build_features",
                    "type", "feature_engineering",
                    "module", "quant_langchain.features.momentum",
                    "requiredParams", List.of("indicators", "window")
            ),
            Map.of(
                    "taskId", "fetch_news_sentiment",
                    "type", "data_collection",
                    "module", "quant_data.news_collectors.gdelt.historical_collector",
                    "requiredParams", List.of("symbols", "lookback_days", "language")
            ),
            Map.of(
                    "taskId", "generate_signals",
                    "type", "signal_generation",
                    "module", "quant_langchain.signals.rule_engine",
                    "requiredParams", List.of("rule")
            ),
            Map.of(
                    "taskId", "risk_control",
                    "type", "risk_management",
                    "module", "quant_langchain.risk.position_manager",
                    "requiredParams", List.of("max_position_size", "stop_loss")
            ),
            Map.of(
                    "taskId", "backtest_strategy",
                    "type", "backtesting",
                    "module", "quant_langchain.backtest.engine",
                    "requiredParams", List.of("initial_cash", "fee_bps")
            )
    );
    private final Map<String, Map<String, Object>> moduleByTaskId;

    public StrategyService(
            StrategyWorkflowRepository strategyWorkflowRepository,
            @Value("${quant.langchain.api:http://langchain-agent:8083}") String langchainApi
    ) {
        this.strategyWorkflowRepository = strategyWorkflowRepository;
        this.langchainApi = langchainApi;
        Map<String, Map<String, Object>> temp = new LinkedHashMap<>();
        for (Map<String, Object> module : moduleCatalog) {
            temp.put(String.valueOf(module.get("taskId")), module);
        }
        this.moduleByTaskId = Collections.unmodifiableMap(temp);
    }

    public Map<String, Object> generateSpec(String prompt, String userId) {
        String strategyId = "stg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String safePrompt = (prompt == null || prompt.isBlank()) ? "default strategy prompt" : prompt.trim();
        String owner = (userId == null || userId.isBlank()) ? "local-user" : userId;

        Map<String, Object> remoteSpec = tryGenerateSpecWithAgent(safePrompt, strategyId, owner);
        if (!remoteSpec.isEmpty()) {
            return remoteSpec;
        }

        Map<String, Object> fallbackRaw = new LinkedHashMap<>();
        fallbackRaw.put("name", "Quant Strategy - " + strategyId);
        fallbackRaw.put("description", safePrompt);
        fallbackRaw.put("tasks", List.of(
                task("fetch_market_data", "data_collection", "quant_data.stock_collector.price_collector.collector",
                        List.of(), Map.of("symbols", List.of("AAPL", "MSFT"), "timeframe", "1d", "lookback_days", 365)),
                task("build_features", "feature_engineering", "quant_langchain.features.momentum",
                        List.of("fetch_market_data"), Map.of("indicators", List.of("rsi", "macd"), "window", 14)),
                task("generate_signals", "signal_generation", "quant_langchain.signals.rule_engine",
                        List.of("build_features"), Map.of("rule", safePrompt)),
                task("risk_control", "risk_management", "quant_langchain.risk.position_manager",
                        List.of("generate_signals"), Map.of("max_position_size", 0.1, "stop_loss", 0.02)),
                task("backtest_strategy", "backtesting", "quant_langchain.backtest.engine",
                        List.of("risk_control"), Map.of("initial_cash", 100000, "fee_bps", 5))
        ));
        fallbackRaw.put("risk", Map.of("max_drawdown", 0.15, "position_limit", 0.1));
        fallbackRaw.put("backtest", Map.of("window", "2y", "rebalance", "daily"));

        Map<String, Object> spec = normalizeSpec(
                fallbackRaw, safePrompt, strategyId, owner, requiresSentimentTask(safePrompt)
        );
        spec.put("_source", "api_mcp_fallback");
        return spec;
    }

    public List<Map<String, Object>> generateTasks(Map<String, Object> workflowSpec) {
        List<Map<String, Object>> remoteTasks = tryGenerateTasksWithAgent(workflowSpec);
        if (!remoteTasks.isEmpty()) {
            return remoteTasks;
        }

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
        String normalizedUser = (userId == null || userId.isBlank()) ? "local-user" : userId;
        String savedAt = Instant.now().toString();

        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("strategyId", strategyId);
        persisted.put("workflowId", strategyId);
        persisted.put("workflowSpec", workflowSpec);
        persisted.put("tasks", tasksObj);
        persisted.put("xml", xml);
        persisted.put("savedBy", normalizedUser);
        persisted.put("savedAt", savedAt);
        workflowStore.put(strategyId, persisted);

        try {
            StrategyWorkflow doc = new StrategyWorkflow();
            doc.setStrategyId(strategyId);
            doc.setWorkflowId(strategyId);
            doc.setWorkflowSpec(workflowSpec);
            doc.setTasks(tasksObj);
            doc.setXml(xml);
            doc.setSavedBy(normalizedUser);
            doc.setSavedAt(savedAt);
            strategyWorkflowRepository.save(doc);
            return Map.of(
                    "success", true,
                    "strategyId", strategyId,
                    "workflowId", strategyId,
                    "savedAt", savedAt,
                    "savedBy", normalizedUser,
                    "storage", "mongodb"
            );
        } catch (Exception ex) {
            return Map.of(
                    "success", true,
                    "strategyId", strategyId,
                    "workflowId", strategyId,
                    "savedAt", savedAt,
                    "savedBy", normalizedUser,
                    "storage", "memory_fallback",
                    "warning", "mongodb_persist_failed: " + ex.getMessage()
            );
        }
    }

    public Map<String, Object> getWorkflow(String strategyId) {
        try {
            Optional<StrategyWorkflow> fromMongo = strategyWorkflowRepository.findByStrategyId(strategyId);
            if (fromMongo.isPresent()) {
                return workflowToMap(fromMongo.get(), "mongodb");
            }
        } catch (Exception ignored) {
            // Fallback to in-memory cache when Mongo is unavailable.
        }

        Map<String, Object> mem = workflowStore.get(strategyId);
        if (mem == null) {
            return Map.of("found", false, "strategyId", strategyId);
        }
        Map<String, Object> result = new LinkedHashMap<>(mem);
        result.put("found", true);
        result.put("storage", "memory");
        return result;
    }

    public List<Map<String, Object>> listWorkflows(String userId) {
        String normalizedUser = (userId == null || userId.isBlank()) ? "local-user" : userId;
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (StrategyWorkflow wf : strategyWorkflowRepository.findTop20BySavedByOrderBySavedAtDesc(normalizedUser)) {
                result.add(Map.of(
                        "strategyId", wf.getStrategyId(),
                        "workflowId", wf.getWorkflowId(),
                        "savedBy", wf.getSavedBy(),
                        "savedAt", wf.getSavedAt(),
                        "name", String.valueOf(wf.getWorkflowSpec() == null ? "" : wf.getWorkflowSpec().getOrDefault("name", "")),
                        "storage", "mongodb"
                ));
            }
        } catch (Exception ignored) {
            // Fallback to in-memory cache when Mongo is unavailable.
        }
        if (!result.isEmpty()) {
            return result;
        }

        for (Map<String, Object> mem : workflowStore.values()) {
            if (normalizedUser.equals(String.valueOf(mem.getOrDefault("savedBy", "")))) {
                result.add(Map.of(
                        "strategyId", String.valueOf(mem.getOrDefault("strategyId", "")),
                        "workflowId", String.valueOf(mem.getOrDefault("workflowId", "")),
                        "savedBy", String.valueOf(mem.getOrDefault("savedBy", "")),
                        "savedAt", String.valueOf(mem.getOrDefault("savedAt", "")),
                        "name", String.valueOf(asMap(mem.get("workflowSpec")).getOrDefault("name", "")),
                        "storage", "memory"
                ));
            }
        }
        return result;
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

    private static Map<String, Object> workflowToMap(StrategyWorkflow wf, String storage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("storage", storage);
        result.put("strategyId", wf.getStrategyId());
        result.put("workflowId", wf.getWorkflowId());
        result.put("workflowSpec", wf.getWorkflowSpec());
        result.put("tasks", wf.getTasks());
        result.put("xml", wf.getXml());
        result.put("savedBy", wf.getSavedBy());
        result.put("savedAt", wf.getSavedAt());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> raw) {
            Map<String, Object> cast = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                cast.put(String.valueOf(e.getKey()), e.getValue());
            }
            return cast;
        }
        return Map.of();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryGenerateSpecWithAgent(String prompt, String strategyId, String owner) {
        try {
            String modulesJson = objectMapper.writeValueAsString(moduleCatalog);
            String targetMarket = detectMarket(prompt);
            boolean requireSentiment = requiresSentimentTask(prompt);
            String instruction = """
                    You generate quant workflow JSON.
                    Return ONLY ONE JSON object, no markdown.

                    User requirement:
                    %s

                    Available modules:
                    %s

                    Constraints:
                    1. Must include fields: strategyId, workflowId, name, description, market, owner, tasks, processes, risk, backtest, createdAt
                    2. strategyId = "%s", workflowId = "%s", owner = "%s"
                    2.1 market should match "%s"
                    3. tasks order: data_collection -> feature_engineering -> signal_generation -> risk_management -> backtesting
                    4. each task needs: taskId, type, module, dependencies, parameters
                    5. dependencies must point to existing previous taskIds
                    6. must use taskIds from: fetch_market_data, build_features, generate_signals, risk_control, backtest_strategy
                    7. if the requirement includes news/sentiment, must include fetch_news_sentiment before build_features
                    """.formatted(prompt, modulesJson, strategyId, strategyId, owner, targetMarket);

            Map<String, Object> response = postAsk(Map.of("question", instruction));
            String answer = String.valueOf(response.getOrDefault("answer", ""));
            String json = extractJsonObject(answer);
            if (json.isBlank()) {
                return Map.of();
            }

            Map<String, Object> rawSpec = objectMapper.readValue(json, Map.class);
            if (rawSpec.isEmpty() || !rawSpec.containsKey("tasks")) {
                return Map.of();
            }
            Map<String, Object> spec = normalizeSpec(rawSpec, prompt, strategyId, owner, requireSentiment);
            if (!validateDependencies(spec)) {
                return Map.of();
            }
            return spec;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> tryGenerateTasksWithAgent(Map<String, Object> workflowSpec) {
        try {
            String specJson = objectMapper.writeValueAsString(workflowSpec);
            String modulesJson = objectMapper.writeValueAsString(moduleCatalog);
            String instruction = """
                    You generate quant python tasks from WorkflowSpec.
                    Return ONLY ONE JSON array.

                    WorkflowSpec:
                    %s

                    Available modules:
                    %s

                    Each item fields: taskId, taskType, module, fileName, code
                    Keep module names aligned with available modules.
                    """.formatted(specJson, modulesJson);

            Map<String, Object> response = postAsk(Map.of("question", instruction));
            String answer = String.valueOf(response.getOrDefault("answer", ""));
            String json = extractJsonArray(answer);
            if (json.isBlank()) {
                return List.of();
            }
            List<Map<String, Object>> rawList = objectMapper.readValue(json, List.class);
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (Object item : rawList) {
                Map<String, Object> cast = asMap(item);
                if (!cast.isEmpty()) {
                    tasks.add(cast);
                }
            }
            return normalizeTaskArtifacts(tasks, workflowSpec);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postAsk(Map<String, String> body) {
        String url = langchainApi + "/api/ask";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
        return response == null ? Map.of() : response;
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    private static String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    private static boolean validateDependencies(Map<String, Object> spec) {
        List<Map<String, Object>> tasks = asMapList(spec.get("tasks"));
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> task : tasks) {
            String taskId = String.valueOf(task.getOrDefault("taskId", ""));
            Object depsObj = task.get("dependencies");
            if (depsObj instanceof List<?> deps) {
                for (Object dep : deps) {
                    if (!seen.contains(String.valueOf(dep))) {
                        return false;
                    }
                }
            }
            seen.add(taskId);
        }
        return !tasks.isEmpty();
    }

    private Map<String, Object> normalizeSpec(
            Map<String, Object> rawSpec, String prompt, String strategyId, String owner, boolean requireSentiment
    ) {
        Map<String, Object> spec = new LinkedHashMap<>(rawSpec);
        String market = detectMarket(prompt);
        List<Map<String, Object>> normalizedTasks = normalizeSpecTasks(
                asMapList(rawSpec.get("tasks")), market, requireSentiment
        );

        spec.put("strategyId", strategyId);
        spec.put("workflowId", strategyId);
        spec.put("owner", owner);
        spec.put("name", String.valueOf(rawSpec.getOrDefault("name", "Quant Strategy - " + strategyId)));
        spec.put("description", String.valueOf(rawSpec.getOrDefault("description", prompt)));
        spec.put("market", market);
        spec.put("tasks", normalizedTasks);
        spec.put("processes", buildProcess(strategyId, normalizedTasks));
        spec.put("risk", normalizeRisk(asMap(rawSpec.get("risk"))));
        spec.put("backtest", normalizeBacktest(asMap(rawSpec.get("backtest")), market));
        spec.put("createdAt", Instant.now().toString());
        spec.put("_source", "api_mcp+llm");
        return spec;
    }

    private List<Map<String, Object>> normalizeSpecTasks(
            List<Map<String, Object>> rawTasks, String market, boolean requireSentiment
    ) {
        List<String> orderedTasks = requireSentiment ? CANONICAL_TASK_ORDER_WITH_SENTIMENT : CANONICAL_TASK_ORDER;
        Map<String, Map<String, Object>> rawByTask = new LinkedHashMap<>();
        for (Map<String, Object> task : rawTasks) {
            String rawTaskId = normalizeTaskId(String.valueOf(task.getOrDefault("taskId", "")));
            if (!rawTaskId.isBlank()) {
                rawByTask.put(rawTaskId, task);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < orderedTasks.size(); i++) {
            String taskId = orderedTasks.get(i);
            Map<String, Object> raw = rawByTask.getOrDefault(taskId, Map.of());
            Map<String, Object> module = moduleByTaskId.get(taskId);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("taskId", taskId);
            task.put("type", String.valueOf(module.get("type")));
            task.put("module", normalizeModule(String.valueOf(module.get("module")), market));
            task.put("dependencies", i == 0 ? List.of() : List.of(orderedTasks.get(i - 1)));
            Map<String, Object> parameters = normalizeTaskParameters(taskId, asMap(raw.get("parameters")), market);
            if (requireSentiment && "build_features".equals(taskId)) {
                parameters = ensureSentimentIndicator(parameters);
            }
            task.put("parameters", parameters);
            out.add(task);
        }
        return out;
    }

    private static String normalizeTaskId(String taskId) {
        if ("risk_management".equals(taskId)) {
            return "risk_control";
        }
        return taskId;
    }

    private String normalizeModule(String defaultModule, String market) {
        if (!"crypto".equals(market)) {
            return defaultModule;
        }
        if ("quant_data.stock_collector.price_collector.collector".equals(defaultModule)) {
            return "quant_data.crypto_collector.price_collector.collector";
        }
        return defaultModule;
    }

    private static String detectMarket(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (lower.contains("btc") || lower.contains("eth") || lower.contains("crypto") || lower.contains("加密")) {
            return "crypto";
        }
        if (lower.contains("a股") || lower.contains("沪深") || lower.contains("china")) {
            return "cn_equity";
        }
        return "us_equity";
    }

    private static boolean requiresSentimentTask(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        return lower.contains("新闻") || lower.contains("情绪") || lower.contains("sentiment") || lower.contains("news");
    }

    private Map<String, Object> normalizeTaskParameters(String taskId, Map<String, Object> raw, String market) {
        if ("fetch_market_data".equals(taskId)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbols", detectDefaultSymbols(raw, market));
            out.put("timeframe", String.valueOf(raw.getOrDefault("timeframe", "crypto".equals(market) ? "4h" : "1d")));
            out.put("lookback_days", asInt(raw.getOrDefault("lookback_days", "crypto".equals(market) ? 180 : 365), "crypto".equals(market) ? 180 : 365));
            return out;
        }
        if ("fetch_news_sentiment".equals(taskId)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbols", detectDefaultSymbols(raw, market));
            out.put("lookback_days", asInt(raw.getOrDefault("lookback_days", 30), 30));
            out.put("language", String.valueOf(raw.getOrDefault("language", "en")));
            return out;
        }
        if ("build_features".equals(taskId)) {
            return Map.of(
                    "indicators", raw.getOrDefault("indicators", List.of("sma_fast", "sma_slow", "rsi", "news_sentiment_score")),
                    "window", asInt(raw.getOrDefault("window", 14), 14)
            );
        }
        if ("generate_signals".equals(taskId)) {
            Object rule = raw.get("rule");
            if (rule == null || String.valueOf(rule).isBlank()) {
                rule = Map.of(
                        "buy_rule", "sma_fast crosses above sma_slow and rsi < 70",
                        "sell_rule", "sma_fast crosses below sma_slow or rsi > 75"
                );
            }
            return Map.of("rule", rule);
        }
        if ("risk_control".equals(taskId)) {
            return Map.of(
                    "max_position_size", asDouble(raw.getOrDefault("max_position_size", 0.1), 0.1),
                    "stop_loss", normalizeRatio(raw.getOrDefault("stop_loss", 0.03), 0.03),
                    "take_profit", normalizeRatio(raw.getOrDefault("take_profit", 0.06), 0.06)
            );
        }
        if ("backtest_strategy".equals(taskId)) {
            return Map.of(
                    "initial_cash", asInt(raw.getOrDefault("initial_cash", 100000), 100000),
                    "fee_bps", asInt(raw.getOrDefault("fee_bps", "crypto".equals(market) ? 8 : 2), "crypto".equals(market) ? 8 : 2)
            );
        }
        return raw;
    }

    private static List<String> detectDefaultSymbols(Map<String, Object> raw, String market) {
        Object symbols = raw.get("symbols");
        if (symbols instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        if ("crypto".equals(market)) {
            return List.of("BTCUSDT", "ETHUSDT");
        }
        if ("cn_equity".equals(market)) {
            return List.of("600519.SH", "000001.SZ");
        }
        return List.of("AAPL", "MSFT");
    }

    private static Map<String, Object> normalizeRisk(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("max_drawdown", normalizeRatio(raw.getOrDefault("max_drawdown", 0.15), 0.15));
        out.put("position_limit", normalizeRatio(raw.getOrDefault("position_limit", 0.1), 0.1));
        return out;
    }

    private static Map<String, Object> ensureSentimentIndicator(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        Object indicatorsObj = raw.get("indicators");
        List<String> indicators = new ArrayList<>();
        if (indicatorsObj instanceof List<?> list) {
            for (Object item : list) {
                indicators.add(String.valueOf(item));
            }
        }
        if (!indicators.contains("news_sentiment_score")) {
            indicators.add("news_sentiment_score");
        }
        out.put("indicators", indicators);
        return out;
    }

    private static Map<String, Object> normalizeBacktest(Map<String, Object> raw, String market) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", String.valueOf(raw.getOrDefault("window", "2y")));
        out.put("rebalance", String.valueOf(raw.getOrDefault("rebalance", "crypto".equals(market) ? "4h" : "daily")));
        return out;
    }

    private static List<Map<String, Object>> buildProcess(String strategyId, List<Map<String, Object>> tasks) {
        List<Map<String, Object>> processTasks = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            String taskId = String.valueOf(task.getOrDefault("taskId", "task_unknown"));
            processTasks.add(Map.of(
                    "taskId", taskId,
                    "taskFileName", "Task_" + taskId + ".py",
                    "processPath", "/opt/quant_repository/Process/" + strategyId + "/Tasks",
                    "displayName", taskId
            ));
        }
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("processName", "Process/quant-trading/" + strategyId + "/Process_execute_strategy");
        process.put("type", "CREATE");
        process.put("tasks", processTasks);
        return List.of(process);
    }

    private List<Map<String, Object>> normalizeTaskArtifacts(List<Map<String, Object>> rawTasks, Map<String, Object> workflowSpec) {
        List<Map<String, Object>> specTasks = asMapList(workflowSpec.get("tasks"));
        Map<String, Map<String, Object>> rawByTaskId = new LinkedHashMap<>();
        for (Map<String, Object> task : rawTasks) {
            String taskId = normalizeTaskId(String.valueOf(task.getOrDefault("taskId", "")));
            if (!taskId.isBlank()) {
                rawByTaskId.put(taskId, task);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> specTask : specTasks) {
            String taskId = String.valueOf(specTask.getOrDefault("taskId", ""));
            Map<String, Object> raw = rawByTaskId.getOrDefault(taskId, Map.of());
            String taskType = String.valueOf(specTask.getOrDefault("type", raw.getOrDefault("taskType", "generic")));
            String module = String.valueOf(specTask.getOrDefault("module", raw.getOrDefault("module", "")));
            String fileName = String.valueOf(raw.getOrDefault("fileName", "Task_" + taskId + ".py"));
            String code = String.valueOf(raw.getOrDefault("code", ""));
            if (code.isBlank()) {
                code = buildDefaultTaskCode(taskId, taskType, module);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", taskId);
            item.put("taskType", taskType);
            item.put("module", module);
            item.put("fileName", fileName);
            item.put("code", code);
            out.add(item);
        }
        return out;
    }

    private static String buildDefaultTaskCode(String taskId, String taskType, String module) {
        return """
                import json
                from typing import Any, Dict

                def run(context: Dict[str, Any]) -> Dict[str, Any]:
                    \"\"\"Auto-generated quant task.\"\"\"
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
    }

    private static int asInt(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double asDouble(Object value, double fallback) {
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double normalizeRatio(Object value, double fallback) {
        double parsed = asDouble(value, fallback);
        if (parsed < 0) {
            parsed = Math.abs(parsed);
        }
        if (parsed > 1.0) {
            parsed = parsed / 100.0;
        }
        if (parsed <= 0 || parsed >= 1.0) {
            return fallback;
        }
        return parsed;
    }
}
