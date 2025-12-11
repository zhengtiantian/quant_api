package org.example.quantapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/index.html", "/static/**", "/favicon.ico",
                                "/api/auth/**", "/api/health", "/actuator/health"
                        ).permitAll()
                        // ✅ 下面接口需要 token 验证
                        .requestMatchers("/api/scripts/**", "/api/run/**", "/api/stop/**").authenticated()
                        .anyRequest().permitAll()
                )
                // ✅ 关键点：只启用 Bearer Token 验证，不再重定向登录页
                .oauth2ResourceServer(oauth2 -> oauth2.jwt());

        return http.build();
    }
}