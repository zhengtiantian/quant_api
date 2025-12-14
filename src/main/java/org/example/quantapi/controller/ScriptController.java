package org.example.quantapi.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")
public class ScriptController {

    private static final String QUANT_DATA_BASE = "http://quant_data:8000";

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/scripts")
    public ResponseEntity<String> listScripts() {
        String url = QUANT_DATA_BASE + "/scripts";
        String response = restTemplate.getForObject(url, String.class);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/run/{**path}")
    public ResponseEntity<String> runScript(@PathVariable String path) {
        String url = QUANT_DATA_BASE + "/run/" + path;
        String response = restTemplate.getForObject(url, String.class);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stop/{**path}")
    public ResponseEntity<String> stopScript(@PathVariable String path) {
        String url = QUANT_DATA_BASE + "/stop/" + path;
        String response = restTemplate.postForObject(url, null, String.class);
        return ResponseEntity.ok(response);
    }
}