package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

/**
 * 配置解析器抽象（依赖倒置点，CORE-P0-1）。
 *
 * <p>core 仅定义本接口，不依赖任何具体配置框架（Serenity / Typesafe）。
 * 上层模块（web）通过 JDK {@link java.util.ServiceLoader} 在 {@code META-INF/services/} 下
 * 注册实现，使 core 在编译期与运行期均不耦合 Serenity。借此打破 core 对 Serenity 的
 * 编译期强依赖，让"core 是纯净底座"在 classpath 层面成立。</p>
 *
 * <p>语义约定：{@link #resolve(String)} 返回该来源解析出的配置值；若该来源无此键则返回
 * {@code null}，由 {@link ConfigSource} 降级到下一来源（系统属性 / 环境变量 / 默认值）。
 * 实现方<b>不得</b>在 {@code resolve} 内抛受检异常——异常由 {@link ConfigSource} 的 SPI 加载处吞掉。</p>
 */
public interface ConfigResolver {

    /**
     * 按 key 从本来源解析配置值。
     *
     * @param key 配置键（如 {@code playwright.browser.type}）
     * @return 解析出的值；本来源无此键时返回 {@code null}
     */
    String resolve(String key);
}
