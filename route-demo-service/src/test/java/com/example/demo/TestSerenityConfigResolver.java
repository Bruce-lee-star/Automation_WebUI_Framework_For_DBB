package com.example.demo;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 测试期 {@link ConfigResolver} SPI 实现：从 classpath 的 {@code serenity.properties} 读取配置，
 * 模拟框架 web 模块的 {@code SerenityConfigResolver}（真实环境经 Serenity 合并配置源读取）。
 *
 * <p>本 demo 仅依赖 framework-route（不含 web），classpath 上无 Serenity 配置源 SPI，
 * 故此处以最小实现补齐：使「用配置文件驱动框架写库」（配置写在 serenity.properties，
 * 而非用例代码里 setProperty）在本 demo 同样成立。
 *
 * <p>经 {@code META-INF/services} 注册，由 core 的 {@code ConfigSource} 经 JDK {@link java.util.ServiceLoader}
 * 发现。语义与接口约定一致：无此键返回 {@code null}，由 {@code ConfigSource} 降级到下一来源。
 */
public class TestSerenityConfigResolver implements ConfigResolver {

    private static final Properties PROPERTIES = load();

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = TestSerenityConfigResolver.class.getClassLoader()
                .getResourceAsStream("serenity.properties")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
        } catch (Exception e) {
            // 无配置文件 / 读取失败时降级为空：ConfigSource 继续走环境变量/默认值，不阻断测试。
        }
        return props;
    }

    @Override
    public String resolve(String key) {
        return PROPERTIES.getProperty(key);
    }
}
