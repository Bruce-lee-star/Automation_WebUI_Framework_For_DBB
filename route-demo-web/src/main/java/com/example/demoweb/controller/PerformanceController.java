package com.example.demoweb.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 性能 / 压力测试端点（route-demo-web 镜像，context-path=/web）。
 *
 * <p>与 route-demo-service 的 {@code PerformanceController} 提供对等能力，便于两套演示服务
 * 各自独立承载 Route 性能测试流量（覆盖大响应体 / 大请求体 / 二进制 multipart / 高延迟 /
 * 流式 / header / CPU 负载等场景）。具体语义见 demo-service 同名类。
 */
@RestController
@RequestMapping("/api/perf")
public class PerformanceController {

    private static final int MAX_SIZE_KB = 200 * 1024;

    @GetMapping("/large-json")
    public ResponseEntity<String> largeJson(@RequestParam(defaultValue = "1024") int sizeKb) {
        int kb = Math.max(1, Math.min(sizeKb, MAX_SIZE_KB));
        int bytes = kb * 1024;
        StringBuilder sb = new StringBuilder(bytes + 64);
        sb.append("{\"endpoint\":\"/api/perf/large-json\",\"sizeKb\":").append(kb).append(",\"payload\":\"");
        for (int i = 0; i < bytes; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        sb.append("\"}");
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(sb.toString());
    }

    @PostMapping("/echo-body")
    public ResponseEntity<Map<String, Object>> echoBody(@RequestBody(required = false) String body) {
        byte[] data = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> resp = new HashMap<>();
        resp.put("receivedBytes", data.length);
        resp.put("md5", md5(data));
        resp.put("first64", truncate(new String(data, java.nio.charset.StandardCharsets.UTF_8), 64));
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam Map<String, String> fields) {
        Map<String, Object> resp = new HashMap<>();
        try {
            byte[] bytes = file.getBytes();
            resp.put("fileName", file.getOriginalFilename());
            resp.put("size", bytes.length);
            resp.put("sha256", sha256(bytes));
            resp.put("fields", new HashMap<>(fields));
            resp.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            resp.put("error", e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/delay")
    public ResponseEntity<Map<String, Object>> delay(@RequestParam(defaultValue = "2000") long ms) {
        long wait = Math.max(0, Math.min(ms, 60_000));
        long start = System.nanoTime();
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("sleptMs", wait);
        resp.put("actualMs", (System.nanoTime() - start) / 1_000_000);
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/stream")
    public ResponseBodyEmitter stream(@RequestParam(defaultValue = "10") int chunks,
                                       @RequestParam(defaultValue = "200") long intervalMs) {
        int n = Math.max(1, Math.min(chunks, 1000));
        long gap = Math.max(10, intervalMs);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perf-stream-web");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            try {
                for (int i = 0; i < n; i++) {
                    Map<String, Object> chunk = new HashMap<>();
                    chunk.put("chunk", i);
                    chunk.put("timestamp", System.currentTimeMillis());
                    emitter.send(chunk);
                    Thread.sleep(gap);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });
        return emitter;
    }

    @GetMapping("/headers")
    public ResponseEntity<Map<String, Object>> headers(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        Map<String, String> hdr = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String k = names.nextElement();
                hdr.put(k.toLowerCase(Locale.ROOT), request.getHeader(k));
            }
        }
        resp.put("headers", hdr);
        resp.put("method", request.getMethod());
        resp.put("query", request.getQueryString());
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cpu")
    public ResponseEntity<Map<String, Object>> cpu(@RequestParam(defaultValue = "10000000") long iter) {
        long n = Math.max(0, iter);
        long start = System.nanoTime();
        double sum = 0;
        for (long i = 0; i < n; i++) {
            sum += Math.sqrt(i) * Math.sin(i);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("iterations", n);
        resp.put("result", sum);
        resp.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
        return ResponseEntity.ok(resp);
    }

    private static String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return hex(md.digest(data));
        } catch (Exception e) {
            return "unsupported";
        }
    }

    private static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            return "unsupported";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
