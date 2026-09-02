package com.example.demoweb.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 增强版 demo 服务 —— 专用于覆盖 route-demo-service 无法制造的请求形态。
 *
 * <ul>
 *   <li>/api/echo (ALL)         —— 回显 method / query / body / headers，用于验证 modifyMethod、setRequestHeaders、modifyRequestBody、matchBodyRegex 等</li>
 *   <li>/api/iframe-echo (GET)  —— iframe 内自动请求的端点，配合 /frame.html 验证 frame 相关匹配</li>
 *   <li>/api/data (GET)         —— 通用 JSON</li>
 *   <li>/api/ct?ct=text|json|html —— 返回不同 Content-Type（验证 matchContentType / resourceType）</li>
 *   <li>/script.js (GET)        —— 返回 JS（resourceType=script）</li>
 *   <li>/img (GET)             —— 返回 1x1 PNG（resourceType=image）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class CoverageController {

    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMCAQDJ/3pUAAAAAElFTkSuQmCC";

    @RequestMapping(value = "/echo", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<Map<String, Object>> echo(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("method", request.getMethod());
        resp.put("query", request.getQueryString());
        resp.put("body", readBody(request));
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String k = names.nextElement();
                headers.put(k.toLowerCase(Locale.ROOT), request.getHeader(k));
            }
        }
        resp.put("headers", headers);
        resp.put("receivedAt", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/iframe-echo")
    public ResponseEntity<Map<String, Object>> iframeEcho() {
        Map<String, Object> m = new HashMap<>();
        m.put("frame", "ok");
        m.put("receivedAt", System.currentTimeMillis());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> data() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "CoverageData");
        m.put("value", 42);
        return ResponseEntity.ok(m);
    }

    @GetMapping("/ct")
    public ResponseEntity<String> contentType(@RequestParam(defaultValue = "json") String ct) {
        switch (ct) {
            case "text":
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body("plain text response");
            case "html":
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_HTML)
                        .body("<html><body>hi</body></html>");
            case "json":
            default:
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"ct\":\"json\"}");
        }
    }

    @GetMapping("/script.js")
    public ResponseEntity<String> script() {
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf("application/javascript"))
                .body("window.__scriptLoaded=true;");
    }

    @GetMapping(value = "/img", produces = "image/png")
    public ResponseEntity<byte[]> img() {
        byte[] bytes = Base64.getDecoder().decode(PNG_B64);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .body(bytes);
    }

    private String readBody(HttpServletRequest request) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = request.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
