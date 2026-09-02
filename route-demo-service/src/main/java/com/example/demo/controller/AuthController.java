package com.example.demo.controller;

import com.example.demo.model.LoginRequest;
import com.example.demo.model.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = new LoginResponse();

        if ("admin".equals(request.getUsername()) && "password123".equals(request.getPassword())) {
            response.setToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkFkbWluIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
            response.setMessage("Login successful");
            response.setUserId(1L);
        } else if ("user".equals(request.getUsername()) && "userpass".equals(request.getPassword())) {
            response.setToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIyMzQ1Njc4OTAiLCJuYW1lIjoiVXNlciIsImlhdCI6MTUxNjIzOTAyMn0.drtKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5d");
            response.setMessage("Login successful");
            response.setUserId(2L);
        } else {
            response.setToken(null);
            response.setMessage("Invalid credentials");
            response.setUserId(null);
            return ResponseEntity.status(401).body(response);
        }

        return ResponseEntity.ok(response);
    }
}
