package org.example.quantapi.controller;

import org.example.quantapi.dto.strategy.GenerateStrategySpecRequest;
import org.example.quantapi.dto.strategy.GenerateStrategyTasksRequest;
import org.example.quantapi.dto.strategy.GenerateStrategyXmlRequest;
import org.example.quantapi.dto.strategy.StrategyChatRequest;
import org.example.quantapi.dto.strategy.SaveStrategyRequest;
import org.example.quantapi.service.StrategyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
        return ResponseEntity.ok(strategyService.generateSpec(request.prompt(), callerId()));
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
                callerId()
        ));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody StrategyChatRequest request) {
        return ResponseEntity.ok(strategyService.chat(
                request.message(),
                callerId(),
                request.strategySpec() == null ? Map.of() : request.strategySpec()
        ));
    }

    @GetMapping("/{strategyId}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String strategyId) {
        Map<String, Object> result = strategyService.getWorkflow(strategyId);
        if (Boolean.FALSE.equals(result.get("found"))) {
            return ResponseEntity.notFound().build();
        }
        // Fetching by id is the other half of the same hole: an authenticated caller could
        // read any strategy by guessing or observing its id, whoever saved it. Ownership is
        // checked here rather than only on the list endpoint, because a list that filters
        // and a lookup that does not is a filter with a hole next to it.
        Object owner = result.get("savedBy");
        if (owner != null && !owner.equals(callerId())) {
            // 404, not 403: confirming that an id exists but belongs to someone else still
            // leaks which ids are real.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Lists the caller's own strategies. The {@code userId} parameter is still accepted so
     * existing clients do not break, and is deliberately ignored — see {@link #callerId()}.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listByUser(@RequestParam(required = false) String userId) {
        return ResponseEntity.ok(strategyService.listWorkflows(callerId()));
    }

    /**
     * The caller's identity, taken from the verified token and never from the request.
     *
     * <p>This replaced {@code normalizeUserId(request.userId())}, which read the owner out
     * of the request body or query string and defaulted to {@code "local-user"}. Any
     * authenticated caller could therefore read and overwrite another user's strategies by
     * changing one field — the client was being trusted to say who it was, which is the
     * whole thing a token exists to stop. It was the only place in the API with a notion
     * of a user, and it got that notion from the wrong side of the trust boundary.
     *
     * <p>The requested {@code userId} is still accepted by the DTOs and ignored, so the UI
     * and quant_ai keep working without a coordinated deploy. Removing the field from the
     * contract is a separate, breaking change.
     *
     * <p>Existing documents are stored under {@code savedBy="admin"}, which is exactly the
     * {@code preferred_username} of the admin user, so no migration is needed. Service
     * accounts resolve to {@code service-account-quant-ai} and simply see none of the
     * human's strategies, which is the correct outcome rather than a bug.
     */
    private static String callerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            // Unreachable while these endpoints require a token. Failing to a name that
            // owns nothing beats defaulting to a shared bucket every caller can reach.
            return "anonymous";
        }
        return auth.getName();
    }
}
