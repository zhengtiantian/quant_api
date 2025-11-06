package org.example.quantapi.controller;

import org.example.quantapi.service.DatabaseTestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class DatabaseTestController {

    private final DatabaseTestService dbService;

    public DatabaseTestController(DatabaseTestService dbService) {
        this.dbService = dbService;
    }

    @GetMapping("/api/dbtest")
    public Map<String, Object> testConnections() {
        return dbService.testConnections();
    }
}