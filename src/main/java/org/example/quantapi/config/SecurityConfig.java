package org.example.quantapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * R.5 phase 2 — deny by default, and separate reading from writing.
 *
 * <p><b>What this replaced.</b> The chain used to end with
 * {@code .anyRequest().permitAll()}, so only three path patterns required a token and
 * everything else was open — including {@code /api/portfolio/holdings}, which served
 * personal financial data to anyone who asked. The JWT infrastructure was configured and
 * enforcing almost nothing, which is the worst of both: it reads as protected.
 *
 * <p><b>Why the order of work mattered.</b> Tightening this first would have broken every
 * UI page and every MCP tool at once, because quant_ai sent no credential at all and six
 * of eight UI modules sent none either. Phase 1 made both carry a token while this file
 * still permitted everything; only then is flipping the default a no-op for correct
 * callers and a 401 for the rest.
 *
 * <p><b>Read and write are separated deliberately, not for tidiness.</b> quant_ai holds a
 * service-account credential that is broader than any single user's — the confused-deputy
 * arrangement described in service_auth.py. It cannot be removed until on-behalf-of token
 * exchange lands (R.5c), but its blast radius can be: the service account is granted
 * {@code quant-read} and not {@code quant-write}, so an injected agent can read the
 * platform and <b>cannot record a trade</b>. Least privilege is what limits the damage
 * while the delegation problem is still open.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Escape hatch for local work against a realm that has no roles yet. Defaults to
     * enforcing: a security control that defaults to off is one that ships off.
     */
    @Value("${quant.security.enforce:true}")
    private boolean enforce;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> {
                    // Unauthenticated by necessity: login itself cannot require a login,
                    // and a health check that needs a token cannot report that the token
                    // service is down.
                    auth.requestMatchers(
                            "/", "/index.html", "/static/**", "/favicon.ico", "/assets/**",
                            "/api/auth/**", "/api/health", "/actuator/health"
                    ).permitAll();

                    if (!enforce) {
                        auth.anyRequest().permitAll();
                        return;
                    }

                    // Anything that changes state needs write. Listed by method rather
                    // than by path so a new endpoint under these prefixes inherits the
                    // rule instead of arriving unprotected.
                    auth.requestMatchers(HttpMethod.POST, "/api/portfolio/**", "/api/holdings/**")
                            .hasRole("quant-write")
                        .requestMatchers(HttpMethod.PUT, "/api/portfolio/**", "/api/holdings/**")
                            .hasRole("quant-write")
                        .requestMatchers(HttpMethod.PATCH, "/api/portfolio/**", "/api/holdings/**")
                            .hasRole("quant-write")
                        .requestMatchers(HttpMethod.DELETE, "/api/portfolio/**", "/api/holdings/**")
                            .hasRole("quant-write");

                    // Script execution stays operator-only; it runs code.
                    auth.requestMatchers("/api/scripts/**", "/api/run/**", "/api/stop/**")
                            .hasRole("quant-write");

                    // Everything else: a valid token carrying read.
                    auth.anyRequest().hasRole("quant-read");
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        // Keycloak puts realm roles in realm_access.roles, which Spring's
                        // default converter does not read. Without this every hasRole
                        // check fails closed and looks like a permissions bug.
                        jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter())));

        return http.build();
    }
}
