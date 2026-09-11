package com.example.demo.controller;

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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 性能 / 压力测试端点 —— 供 Route 模块独立性能测试使用。
 *
 * <p>覆盖 Route 在高负载 / 大报文 / 二进制 / 流式 / 高延迟等场景下的表现：
 * <ul>
 *   <li>{@code /api/perf/large-json?sizeKb=}        —— 返回可控大小的 JSON 响应体，压测响应体捕获与 OOM 守门</li>
 *   <li>{@code POST /api/perf/echo-body}            —— 回显请求体长度 + MD5（不全量回显，避免双倍内存），压测 modify/mock 大报文改写</li>
 *   <li>{@code POST /api/perf/upload} (multipart)   —— 接收文件 part，回显文件名 / 字节数 / SHA-256 + 文本字段，端到端验证二进制 multipart 保真</li>
 *   <li>{@code /api/perf/delay?ms=}                 —— 可控服务端延迟，压测 delay 注入与「事件线程 vs 延迟线程」时序竞争</li>
 *   <li>{@code /api/perf/stream?chunks=&intervalMs=} —— 分块流式响应（SSE 风格），压测流式资源处理与监听器解绑</li>
 *   <li>{@code /api/perf/headers}                   —— 回显请求头，压测 setRequestHeaders / 断言 header</li>
 *   <li>{@code /api/perf/cpu?iter=}                 —— CPU 负载端点，量化 Route 处理开销占比</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/perf")
public class PerformanceController {

    private static final int MAX_SIZE_KB = 200 * 1024; // 200MB 上限，防止误用拖垮本机

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

    /**
     * 回显请求体摘要（长度 + MD5），不全量回显以避免服务端双倍内存占用。
     * 用于压测 Route 对大请求体的 modify / mock 改写路径。
     */
    @PostMapping("/echo-body")
    public ResponseEntity<Map<String, Object>> echoBody(@RequestBody(required = false) String body) {
        byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        Map<String, Object> resp = new HashMap<>();
        resp.put("receivedBytes", data.length);
        resp.put("md5", md5(data));
        resp.put("first64", truncate(new String(data, StandardCharsets.UTF_8), 64));
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * 二进制 multipart 上传回显：逐个字节读取文件 part，计算 SHA-256 与长度；
     * 文本字段原样回显。用于验证 Route 的二进制 multipart 改写 / 透传是否零拷贝保真。
     */
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
            Map<String, String> echoFields = new HashMap<>(fields);
            resp.put("fields", echoFields);
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

    /**
     * 分块流式响应（SSE 风格）：逐块 flush，便于压测 Route 在流式传输下的资源处理与监听器解绑。
     */
    @GetMapping("/stream")
    public ResponseBodyEmitter stream(@RequestParam(defaultValue = "10") int chunks,
                                       @RequestParam(defaultValue = "200") long intervalMs) {
        int n = Math.max(1, Math.min(chunks, 1000));
        long gap = Math.max(10, intervalMs);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L); // 0 = 无超时
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perf-stream");
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

    // ── 工具 ──

    private static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hex(md.digest(data));
        } catch (Exception e) {
            return "unsupported";
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
