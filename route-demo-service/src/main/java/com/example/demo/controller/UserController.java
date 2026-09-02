package com.example.demo.controller;

import com.example.demo.model.Order;
import com.example.demo.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UserController {

    private static final List<User> USERS = new ArrayList<>(Arrays.asList(
        new User(1L, "Alice", "alice@example.com", "USER"),
        new User(2L, "Bob", "bob@example.com", "ADMIN"),
        new User(3L, "Charlie", "charlie@example.com", "USER")
    ));

    private static final List<Order> ORDERS = new ArrayList<>(Arrays.asList(
        new Order(101L, "Laptop", 999.99, "SHIPPED"),
        new Order(102L, "Phone", 699.99, "PENDING"),
        new Order(103L, "Tablet", 399.99, "DELIVERED")
    ));

    @GetMapping("/users")
    public ResponseEntity<List<User>> getUsers() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.ok(USERS);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        User user = USERS.stream()
            .filter(u -> u.getId().equals(id))
            .findFirst()
            .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
        return ResponseEntity.ok(user);
    }

    @PostMapping("/users")
    public ResponseEntity<User> createUser(@RequestBody User user) {
        user.setId(System.currentTimeMillis());
        USERS.add(user);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User updatedUser) {
        for (int i = 0; i < USERS.size(); i++) {
            if (USERS.get(i).getId().equals(id)) {
                updatedUser.setId(id);
                USERS.set(i, updatedUser);
                return ResponseEntity.ok(updatedUser);
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        USERS.removeIf(u -> u.getId().equals(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}/orders")
    public ResponseEntity<List<Order>> getUserOrders(@PathVariable Long id) {
        User user = USERS.stream()
            .filter(u -> u.getId().equals(id))
            .findFirst()
            .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
        List<Order> userOrders = ORDERS.stream()
            .map(order -> new Order(
                order.getId() + id * 100,
                order.getProduct(),
                order.getPrice(),
                order.getStatus()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(userOrders);
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetData() {
        USERS.clear();
        USERS.addAll(Arrays.asList(
            new User(1L, "Alice", "alice@example.com", "USER"),
            new User(2L, "Bob", "bob@example.com", "ADMIN"),
            new User(3L, "Charlie", "charlie@example.com", "USER")
        ));
        ORDERS.clear();
        ORDERS.addAll(Arrays.asList(
            new Order(101L, "Laptop", 999.99, "SHIPPED"),
            new Order(102L, "Phone", 699.99, "PENDING"),
            new Order(103L, "Tablet", 399.99, "DELIVERED")
        ));
        return ResponseEntity.ok("{\"status\":\"reset\"}");
    }

    @GetMapping("/search")
    public ResponseEntity<List<User>> searchUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String name) {
        List<User> result = new ArrayList<>(USERS);
        if (role != null) {
            result = result.stream()
                .filter(u -> role.equals(u.getRole()))
                .collect(Collectors.toList());
        }
        if (name != null) {
            result = result.stream()
                .filter(u -> u.getName().contains(name))
                .collect(Collectors.toList());
        }
        return ResponseEntity.ok(result);
    }
}
