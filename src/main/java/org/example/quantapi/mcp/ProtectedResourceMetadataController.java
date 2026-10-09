package org.example.quantapi.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP endpoint.
 *
 * <p>This is how an MCP client that has never heard of this platform finds out where to log
 * in. The MCP authorization spec has the client call {@code /mcp}, receive a 401 whose
 * {@code WWW-Authenticate} header points here ({@link McpAuthenticationEntryPoint}), read the
 * authorization server from this document, and run the OAuth flow against Keycloak. Without
 * it a 401 is a dead end: the client knows it is unauthorised and has no way to fix that.
 *
 * <p>The authorization server is configured rather than derived: the issuer a client must
 * reach (the published Keycloak port) is not the address quant_api uses internally.
 */
@RestController
public class ProtectedResourceMetadataController {

    public static final String PATH = "/.well-known/oauth-protected-resource";

    private final String authorizationServer;

    public ProtectedResourceMetadataController(
            @Value("${quant.mcp.authorization-server:http://localhost:18082/realms/quant}") String authorizationServer) {
        this.authorizationServer = authorizationServer;
    }

    @GetMapping({PATH, PATH + McpServerConfig.ENDPOINT})
    public Map<String, Object> metadata(HttpServletRequest request) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("resource", resourceUrl());
        doc.put("authorization_servers", List.of(authorizationServer));
        doc.put("bearer_methods_supported", List.of("header"));
        doc.put("resource_name", "quant-api MCP server");
        return doc;
    }

    static String resourceUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(McpServerConfig.ENDPOINT).toUriString();
    }

    static String metadataUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(PATH + McpServerConfig.ENDPOINT).toUriString();
    }
}
