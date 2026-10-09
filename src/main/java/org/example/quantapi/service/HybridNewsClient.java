package org.example.quantapi.service;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Hybrid news retrieval (R.12), served by quant_ai's {@code /api/retrieve/news}.
 *
 * <p>The fusion of semantic and keyword search lives in quant_ai only. Porting it here would
 * give the Java and Python MCP servers two implementations of the same ranking, free to drift
 * apart — the situation R.12 exists to end. This client calls the one implementation and, when
 * quant_ai, its embedding model or Qdrant is unreachable, reports that instead of throwing, so
 * the caller can fall back to keyword search and say so.
 *
 * <p>Timeouts are short on purpose: a news search that waits 30 s for a dead service before
 * falling back is worse than one that falls back after 2 s.
 */
@Service
public class HybridNewsClient {

    private static final Logger log = LoggerFactory.getLogger(HybridNewsClient.class);

    private final RestClient rest;
    private final String baseUrl;

    public HybridNewsClient(@Value("${quant.ai.api:http://quant-ai:8083}") String baseUrl,
                            @Value("${quant.ai.retrieve-timeout-ms:8000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout(readTimeoutMs);
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Articles ranked by quant_ai's hybrid retriever, or empty with the reason logged. */
    public Result search(String query, String symbol, String from, String to, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/retrieve/news")
                .queryParam("q", query)
                .queryParamIfPresent("symbol", Optional.ofNullable(symbol))
                .queryParamIfPresent("from", Optional.ofNullable(from))
                .queryParamIfPresent("to", Optional.ofNullable(to))
                .queryParam("limit", limit)
                .build().encode().toUri();
        try {
            Map<String, Object> body = rest.get().uri(uri).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return new Result(body, null);
        } catch (RestClientException e) {
            // Full detail stays in the server log; the caller gets the kind of failure only.
            // The message names internal hosts and ports, which an MCP client has no use for.
            log.warn("hybrid retrieval unavailable at {}: {}", baseUrl, e.getMessage());
            return new Result(null, "quant_ai retriever unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** {@code payload} when the call succeeded, otherwise {@code failure} says why. */
    public record Result(Map<String, Object> payload, String failure) {
        public boolean ok() {
            return payload != null;
        }
    }
}
