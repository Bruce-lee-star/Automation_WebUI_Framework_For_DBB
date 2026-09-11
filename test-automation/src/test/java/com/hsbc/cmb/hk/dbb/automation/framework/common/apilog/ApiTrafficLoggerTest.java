package com.hsbc.cmb.hk.dbb.automation.framework.common.apilog;

import com.hsbc.cmb.hk.dbb.automation.framework.common.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-3 HTTP 报文脱敏落盘契约测试。
 *
 * <p>核心保证（金融级）：
 * <ol>
 *   <li><b>默认关闭</b>：未开启时不产生任何文件（不打扰、不落敏感数据）；</li>
 *   <li><b>开启后确实落盘</b>，且内容是结构化可读的（方法 / URL / 状态码 / 头 / 体）；</li>
 *   <li><b>零敏感泄露</b>：URL 查询参数、请求头、请求体中的密钥，写入前必须被脱敏，
 *       原始明文<b>绝不能</b>出现在落盘文件里。</li>
 * </ol>
 */
public class ApiTrafficLoggerTest {

    /** 一个"一看就该被脱敏"的假密钥。 */
    private static final String SECRET = "super-secret-value-9f3a2b";

    private Path tempDir;

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("api-traffic-test");
        System.clearProperty(ApiTrafficLogger.ENABLED_KEY);
        System.clearProperty(ApiTrafficLogger.DIR_KEY);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(ApiTrafficLogger.ENABLED_KEY);
        System.clearProperty(ApiTrafficLogger.DIR_KEY);
        deleteRecursively(tempDir);
    }

    /** 默认关闭：不写任何文件。 */
    @Test
    public void disabledByDefaultWritesNothing() throws Exception {
        assertFalse(ApiTrafficLogger.isEnabled(), "默认必须关闭（避免无意把敏感报文落盘）");

        ApiTrafficLogger.record("MONITOR", "POST", "http://host/api?password=" + SECRET, 200,
                headers(), "{\"password\":\"" + SECRET + "\"}", headers(), "{}",
                System.currentTimeMillis());

        assertFalse(Files.exists(ApiTrafficLogger.currentFile()), "未开启时不落盘");
        // 即使等待异步窗口也不应出现
        Thread.sleep(300);
        assertFalse(Files.exists(ApiTrafficLogger.currentFile()), "未开启时异步任务也不应写出文件");
    }

    /** 开启后落盘，且内容结构化可读。 */
    @Test
    public void enabledWritesStructuredTraffic() throws Exception {
        enableToTempDir();

        ApiTrafficLogger.record("MONITOR", "POST", "http://host/api/login", 200,
                headers(), "{\"user\":\"admin\"}", headers(), "{\"code\":0}",
                System.currentTimeMillis());

        String content = awaitContent();
        assertNotNull(content, "开启后应产生落盘内容");
        assertTrue(content.contains("POST"), "应记录 HTTP 方法");
        assertTrue(content.contains("http://host/api/login"), "应记录 URL");
        assertTrue(content.contains("status=200"), "应记录状态码");
        assertTrue(content.contains("request.body"), "应记录请求体段落");
        assertTrue(content.contains("response.body"), "应记录响应体段落");
    }

    /** 零敏感泄露：URL 参数 / 请求头 / 请求体中的密钥均不得明文出现。 */
    @Test
    public void sanitizesSecretsBeforeWriting() throws Exception {
        enableToTempDir();

        Map<String, String> secretHeaders = new HashMap<>();
        secretHeaders.put("Authorization", "Bearer " + SECRET);
        secretHeaders.put("X-Trace", "keep-me");

        ApiTrafficLogger.record("MOCK", "POST",
                "http://host/api/login?password=" + SECRET, 200,
                secretHeaders,
                "{\"username\":\"admin\",\"password\":\"" + SECRET + "\"}",
                headers(), "{\"token\":\"" + SECRET + "\"}",
                System.currentTimeMillis());

        String content = awaitContent();
        assertNotNull(content);

        assertFalse(content.contains(SECRET), "落盘内容绝不能出现明文密钥（零敏感泄露）");
        assertTrue(content.contains(SensitiveDataSanitizer.maskToken()), "掩码应出现在落盘内容中");
        // 非敏感字段必须保留，否则脱敏过头就失去了排障价值
        assertTrue(content.contains("keep-me"), "非敏感字段应保留，否则落盘失去排障价值");
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private void enableToTempDir() {
        System.setProperty(ApiTrafficLogger.ENABLED_KEY, "true");
        System.setProperty(ApiTrafficLogger.DIR_KEY, tempDir.toString());
        assertTrue(ApiTrafficLogger.isEnabled(), "配置后应处于开启状态");
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        return h;
    }

    /** 落盘是异步的（AsyncPool），轮询等待文件内容出现。 */
    private String awaitContent() throws Exception {
        Path file = ApiTrafficLogger.currentFile();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.size(file) > 0) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 测试清理失败不影响断言结果
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
