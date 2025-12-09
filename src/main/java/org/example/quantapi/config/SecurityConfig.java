package org.example.quantapi.config;

import org.keycloak.adapters.springsecurity.KeycloakConfiguration;
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.core.session.SessionRegistryImpl;

@Configuration
@EnableMethodSecurity
@KeycloakConfiguration
public class SecurityConfig {

    @Bean
    public KeycloakAuthenticationProvider keycloakAuthenticationProvider() {
        return new KeycloakAuthenticationProvider();
    }

    @Bean
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(new SessionRegistryImpl());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // ✅ 放行 React 前端静态资源
                        .requestMatchers("/", "/index.html", "/static/**", "/favicon.ico").permitAll()
                        // ✅ 放行后端健康检测、登录接口
                        .requestMatchers(
                                "/api/health",
                                "/actuator/health",
                                "/api/auth/**"
                        ).permitAll()
                        // ✅ 其他 API 都要求带 Keycloak token
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                // ✅ 不要重定向到 Keycloak登录页，只返回401
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendError(401, "Unauthorized")
                ));

        return http.build();
    }
}