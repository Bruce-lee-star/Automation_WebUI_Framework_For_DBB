package com.hsbc.cmb.hk.dbb.automation.tests.data;

import java.util.Map;
import java.util.UUID;

/**
 * D-5：测试数据工厂——以「模板 + 变体 / 随机化」构造请求载荷，替代复制整份 JSON 文件。
 *
 * <p>关键改进：{@link #uniqueUser()} 生成唯一数据，使用例之间<b>天然不冲突</b>，比「靠 {@code @Before}
 * 重置共享后端数据」可靠得多（后者在并行执行或漏写时必然互相污染）。
 *
 * <p>线程安全：无状态静态工厂。
 */
public final class PayloadFactory {

    private PayloadFactory() {
    }

    /** 登录载荷：以有效登录模板为基底，覆盖用户名/密码（凭证值由调用方经 SecretValue 等注入，不落盘）。 */
    public static Payload login(String username, String password) {
        return Payload.of("api-demo-login-valid")
                .with("username", username)
                .with("password", password);
    }

    /** 创建用户载荷：以创建用户模板为基底 + 变体覆盖。 */
    public static Payload createUser(Map<String, ?> overrides) {
        return Payload.ofTemplate("api-demo-create-user", overrides);
    }

    /** 唯一用户载荷：随机 name/email，保证用例间数据不冲突。 */
    public static Payload uniqueUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return createUser(Map.of(
                "name", "user_" + suffix,
                "email", "u" + suffix + "@test.local"));
    }
}
