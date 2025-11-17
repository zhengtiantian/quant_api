package org.example.quantapi.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private static final String KEYCLOAK_URL = "http://quant_keycloak:8080";

    // 登录：前端传 username / password，向 Keycloak 申请 token
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");

        String tokenUrl = KEYCLOAK_URL + "/realms/quant/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = String.format(
                "client_id=quant-ui&grant_type=password&username=%s&password=%s",
                username, password
        );

        RestTemplate rest = new RestTemplate();
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = rest.postForEntity(tokenUrl, request, Map.class);
            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
    }

    // 注册：后端调用 Keycloak Admin API 创建用户
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");

        try {
            RestTemplate rest = new RestTemplate();

            // 先用 admin 账号获取 token
            String adminTokenUrl = KEYCLOAK_URL + "/realms/master/protocol/openid-connect/token";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String adminBody = "client_id=admin-cli&username=admin&password=admin&grant_type=password";
            Map<String, Object> adminToken = rest.postForObject(adminTokenUrl, new HttpEntity<>(adminBody, headers), Map.class);
            String accessToken = (String) adminToken.get("access_token");

            // 调用 Keycloak 注册用户
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.setBearerAuth(accessToken);
            userHeaders.setContentType(MediaType.APPLICATION_JSON);

            String createUserBody = String.format("""
                {
                  "username": "%s",
                  "enabled": true,
                  "credentials": [{"type": "password", "value": "%s", "temporary": false}]
                }
                """, username, password);

            HttpEntity<String> createUserRequest = new HttpEntity<>(createUserBody, userHeaders);
            rest.exchange(KEYCLOAK_URL + "/admin/realms/quant/users", HttpMethod.POST, createUserRequest, String.class);

            return ResponseEntity.ok(Map.of("message", "User registered successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Registration failed", "details", e.getMessage()));
        }
    }
}