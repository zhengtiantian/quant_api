package org.example.quantapi.mcp;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is calling an MCP tool, captured on the servlet thread.
 *
 * <p>The MCP SDK may run a tool handler on a Reactor worker thread, where Spring's
 * thread-local {@code SecurityContext} is empty. A handler that asked
 * {@code SecurityContextHolder} directly would see no caller and — depending on how the
 * check was written — either deny everything or, worse, allow everything. So the
 * transport's context extractor copies the authenticated principal into the MCP
 * transport context while still on the request thread, and handlers read it from there.
 *
 * @param name           preferred_username for a person, azp for a service account
 *                       (see {@code KeycloakRealmRoleConverter})
 * @param roles          realm roles without the {@code ROLE_} prefix
 * @param serviceAccount true when the token was issued to a client, not a person
 */
public record McpCaller(String name, Set<String> roles, boolean serviceAccount) {

    public static final String CONTEXT_KEY = "quant.caller";

    static final McpCaller ANONYMOUS = new McpCaller("anonymous", Set.of(), false);

    public boolean has(String role) {
        return roles.contains(role);
    }

    /** The caller of the current request, or {@link #ANONYMOUS} if the chain let it through unauthenticated. */
    public static McpCaller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ANONYMOUS;
        }
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        String name = auth.getName();
        // Keycloak names a client's service account "service-account-<clientId>" and puts
        // that in preferred_username; the role converter falls back to azp when it is absent.
        boolean service = name != null && (name.startsWith("service-account-") || isClientOnly(auth));
        return new McpCaller(name, roles, service);
    }

    private static boolean isClientOnly(Authentication auth) {
        if (auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            return username == null || username.isBlank() || username.startsWith("service-account-");
        }
        return false;
    }
}
