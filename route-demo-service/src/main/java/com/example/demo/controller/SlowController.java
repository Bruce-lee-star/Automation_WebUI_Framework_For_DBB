package com.example.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/slow")
public class SlowController {

    @GetMapping("/endpoint")
    public ResponseEntity<Map<String, Object>> slowEndpoint() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "This is a slow endpoint");
        response.put("timestamp", System.currentTimeMillis());
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/very-slow")
    public ResponseEntity<Map<String, Object>> verySlowEndpoint() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Very slow endpoint for testing high latency");
        response.put("processingTime", "5000ms");
        response.put("status", "completed");
        return ResponseEntity.ok(response);
    }
}
