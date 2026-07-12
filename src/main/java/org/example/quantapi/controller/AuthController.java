package org.example.quantapi.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private static final String KEYCLOAK_URL = "http://quant_keycloak:8080";

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
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "error", "login_failed",
                    "error_description", e.getResponseBodyAsString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "login_exception",
                    "error_description", e.getMessage()
            ));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        String generatedEmail = username + "@local.quant";

        try {
            RestTemplate rest = new RestTemplate();

            String adminTokenUrl = KEYCLOAK_URL + "/realms/master/protocol/openid-connect/token";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String adminBody = "client_id=admin-cli&username=admin&password=admin&grant_type=password";
            Map<String, Object> adminToken = rest.postForObject(adminTokenUrl, new HttpEntity<>(adminBody, headers), Map.class);
            String accessToken = (String) adminToken.get("access_token");

            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.setBearerAuth(accessToken);
            userHeaders.setContentType(MediaType.APPLICATION_JSON);

            String createUserBody = String.format("""
                {
                  "username": "%s",
                  "firstName": "%s",
                  "lastName": "User",
                  "email": "%s",
                  "enabled": true,
                  "emailVerified": true,
                  "requiredActions": [],
                  "credentials": [{"type": "password", "value": "%s", "temporary": false}]
                }
                """, username, username, generatedEmail, password);

            HttpEntity<String> createUserRequest = new HttpEntity<>(createUserBody, userHeaders);
            rest.exchange(KEYCLOAK_URL + "/admin/realms/quant/users", HttpMethod.POST, createUserRequest, String.class);

            return ResponseEntity.ok(Map.of("message", "User registered successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Registration failed", "details", e.getMessage()));
        }
    }
}
