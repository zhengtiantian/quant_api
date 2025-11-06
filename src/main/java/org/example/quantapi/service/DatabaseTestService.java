package org.example.quantapi.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DatabaseTestService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    public Map<String, Object> testConnections() {
        Map<String, Object> result = new HashMap<>();

        // ✅ 测试 MySQL
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.tables", Integer.class);
            result.put("mysql", "✅ Connected (" + count + " tables)");
        } catch (Exception e) {
            result.put("mysql", "❌ Connection failed: " + e.getMessage());
        }

        // ✅ 测试 MongoDB
        try {
            String dbName = mongoTemplate.getDb().getName();
            result.put("mongodb", "✅ Connected (" + dbName + ")");
        } catch (Exception e) {
            result.put("mongodb", "❌ Connection failed: " + e.getMessage());
        }

        return result;
    }
}