package org.example.quantapi.mcp;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Spring's bearer-token 401, plus the {@code resource_metadata} pointer MCP clients look for.
 *
 * <p>The MCP authorization spec requires a protected MCP server to answer an unauthenticated
 * request with {@code WWW-Authenticate: Bearer resource_metadata="<url>"} so the client can
 * discover the authorization server. Spring already writes a correct Bearer challenge
 * (including {@code error="invalid_token"} for a bad token); this only appends the pointer,
 * and only for the MCP endpoint, so the REST API's responses are unchanged.
 */
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        delegate.commence(request, response, e);
        if (request.getRequestURI().startsWith(McpServerConfig.ENDPOINT)) {
            String challenge = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
            String pointer = "resource_metadata=\"" + ProtectedResourceMetadataController.metadataUrl() + "\"";
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    challenge == null || challenge.equals("Bearer") ? "Bearer " + pointer : challenge + ", " + pointer);
        }
    }
}
