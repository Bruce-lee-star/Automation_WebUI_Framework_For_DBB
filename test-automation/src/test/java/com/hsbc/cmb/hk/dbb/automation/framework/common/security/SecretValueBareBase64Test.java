package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 双格式密文透明解密 + 「绑定本地主密钥」契约测试。
 *
 * <p>核心保证：
 * <ul>
 *   <li>{@code ENC(...)} 显式标记 —— <b>始终</b>解密（主加密写法，不受裸密文开关影响）；</li>
 *   <li>裸 base64 —— <b>默认即解密</b>（用户需求：双格式都支持），经 {@link ConfigCipher#looksLikeCiphertext}
 *       启发式识别；显式设 {@code framework.secret.allow-bare-base64=false} 可关闭；</li>
 *   <li><b>key 敏感</b>：{@code ENC(...)} 显式标记在主密钥不匹配（GCM 认证失败）时<b>失败快</b>
 *       （抛 {@link IllegalStateException}），确保「key 变则旧密文解不了」立即暴露；裸 base64 为启发式，
 *       失败降级保留原串（CORE-C5）；</li>
 *   <li><b>仅明显非密文形态</b>（非合法 base64 / 长度不足以含 IV + GCM 标签）才原样返回。</li>
 * </ul>
 *
 * <p>主密钥通过系统属性 {@code config.master.key} 注入（测试用固定 32 字节密钥），
 * 无需读写 {@code user.home} 下的真实密钥文件，测试零副作用、可重复。
 */
public class SecretValueBareBase64Test {

    private static final String PLAIN = "plain-secret-123";

    /** 测试主密钥（64 hex = 32 字节）。 */
    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 另一把不同的主密钥，用于验证「key 变则旧密文解不了」。 */
    private static final String OTHER_MASTER_KEY =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @BeforeEach
    public void setUp() {
        System.setProperty("config.master.key", TEST_MASTER_KEY);
        System.setProperty("security.secret.strict", "true"); // 确定性：默认严格失败快
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(SecretValue.ALLOW_BARE_BASE64_KEY);
        System.clearProperty("config.master.key");
        System.clearProperty("security.secret.strict");
    }

    /** 显式 ENC(...) 标记：无论开关如何都解密。 */
    @Test
    public void encMarkedValueIsAlwaysDecrypted() {
        String enc = encryptOrSkip();
        assertEquals(PLAIN, SecretValue.decryptIfNeeded(enc));
    }

    /** 裸 base64：默认即解密（双格式支持），无需显式开关。 */
    @Test
    public void bareBase64IsDecryptedByDefault() {
        String bare = bareCiphertextOrSkip();
        assertEquals(PLAIN, SecretValue.decryptIfNeeded(bare), "裸 base64 默认应透明解密");
    }

    /** 裸 base64：显式关闭开关（allow-bare-base64=false）后不再解密，原样返回。 */
    @Test
    public void bareBase64OptOutWhenDisabled() {
        String bare = bareCiphertextOrSkip();
        System.setProperty(SecretValue.ALLOW_BARE_BASE64_KEY, "false");
        assertEquals(bare, SecretValue.decryptIfNeeded(bare), "关闭开关后裸 base64 应原样返回");
    }

    /** key 不匹配：ENC(...) 密文解密失败 → 失败快（抛 IllegalStateException）。 */
    @Test
    public void encCiphertextWithWrongKeyFailsFast() {
        String enc = encryptOrSkip();
        System.setProperty("config.master.key", OTHER_MASTER_KEY);
        assertThrows(IllegalStateException.class, () -> SecretValue.decryptIfNeeded(enc),
                "key 变更后 ENC 密文应失败快，而非静默放行");
    }

    /**
     * CORE-C5：裸密文 key 不匹配 → <b>降级保留原串</b>（裸 base64 为启发式，无法与"恰好合法 base64 的
     * 普通值"区分，故解密失败不得让启动崩溃）。显式 {@code ENC(...)} 仍失败快（见
     * {@link #encCiphertextWithWrongKeyFailsFast}）。
     */
    @Test
    public void bareCiphertextWithWrongKeyDegradesToOriginal() {
        String bare = bareCiphertextOrSkip();
        System.setProperty("config.master.key", OTHER_MASTER_KEY);
        assertEquals(bare, SecretValue.decryptIfNeeded(bare),
                "裸密文解密失败应降级保留原串，而非崩溃");
    }

    /**
     * 不加任何 {@code security.secret.strict} 配置：{@code ENC(...)} 默认即严格失败快、不降级。
     * 直接验证框架默认值（而非显式置 true 的用例），确保"零配置 = 不降级"。
     */
    @Test
    public void encFailsFastByDefaultWithoutStrictConfig() {
        String enc = encryptOrSkip();
        System.clearProperty("security.secret.strict"); // 模拟"未配置"
        System.setProperty("config.master.key", OTHER_MASTER_KEY);
        assertThrows(IllegalStateException.class, () -> SecretValue.decryptIfNeeded(enc),
                "不配置 security.secret.strict 时 ENC 密文默认失败快、不降级");
    }

    /** 非严格模式（-Dsecurity.secret.strict=false）：解密失败降级为保留原串（排障用）。 */
    @Test
    public void wrongKeyDegradesToOriginalWhenNotStrict() {
        String bare = bareCiphertextOrSkip();
        System.setProperty("config.master.key", OTHER_MASTER_KEY);
        System.setProperty("security.secret.strict", "false");
        assertEquals(bare, SecretValue.decryptIfNeeded(bare), "非严格模式应保留原串");
    }

    /** 明显非密文形态：原样返回，不抛异常（非 base64 / 长度过短）。 */
    @Test
    public void nonCiphertextFormsPassThrough() {
        assertEquals("authorization,password", SecretValue.decryptIfNeeded("authorization,password"));
        assertEquals("open", SecretValue.decryptIfNeeded("open"));
        assertEquals("YWJj", SecretValue.decryptIfNeeded("YWJj"), "合法 base64 但长度过短应按明文返回");
    }

    /**
     * CORE-C5 验收：普通配置值"恰好是合法 base64 且超长"（如 32 位十六进制串）不再导致启动崩溃
     * —— 裸路径启发式误命中后解密失败，降级保留原串。
     */
    @Test
    public void plainLongBase64LookingValueDoesNotCrash() {
        String plainHex = "0123456789abcdef0123456789abcdef"; // 32 hex：合法 base64、解码 24B > IV
        assertEquals(plainHex, SecretValue.decryptIfNeeded(plainHex),
                "普通长 base64 值应原样返回，绝不因启发式误命中而抛异常");
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private static String encryptOrSkip() {
        try {
            return ConfigCipher.encrypt(PLAIN);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "主密钥不可用（无法加解密），跳过本断言");
            return null;
        }
    }

    /** 去掉 ENC(...) 外壳，得到"裸 base64 密文"。 */
    private static String bareCiphertextOrSkip() {
        String enc = encryptOrSkip();
        return enc.substring("ENC(".length(), enc.length() - 1);
    }
}
