package org.example.quantapi.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns Keycloak realm roles into Spring authorities.
 *
 * <p>Spring's default converter reads the {@code scope} / {@code scp} claim, which
 * Keycloak does not use for realm roles — it puts them under
 * {@code realm_access.roles}. Without this the roles are present in the token,
 * invisible to the authorization rules, and every {@code hasRole} check quietly fails
 * closed. That failure looks exactly like a permissions bug, so it is worth naming.
 *
 * <p>Roles are prefixed with {@code ROLE_} because {@code hasRole("x")} expands to
 * {@code hasAuthority("ROLE_x")}; skipping the prefix is the other half of the same
 * silent mismatch.
 */
public class KeycloakRealmRoleConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        Collection<GrantedAuthority> authorities = List.of();

        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            authorities = ((Collection<String>) roles).stream()
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
        }

        // The principal name comes from preferred_username when a human is behind the
        // token and from azp when it is a service account, so an audit line says which
        // of the two acted rather than showing an opaque subject id.
        String principal = jwt.getClaimAsString("preferred_username");
        if (principal == null || principal.isBlank()) {
            principal = jwt.getClaimAsString("azp");
        }
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }
}
