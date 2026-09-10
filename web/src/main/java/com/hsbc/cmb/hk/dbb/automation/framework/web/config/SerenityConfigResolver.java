package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;
import net.thucydides.model.environment.SystemEnvironmentVariables;

/**
 * Serenity 配置解析器（{@link ConfigResolver} 的 SPI 实现，下沉 web —— CORE-P0-1）。
 *
 * <p>core 的 {@link ConfigSource} 通过 JDK {@link java.util.ServiceLoader} 发现本实现，
 * 用于解析 Serenity 合并配置源（serenity.conf / serenity.properties / 系统属性 / 环境变量）。
 * 借此 core 在编译期与运行期均不耦合 Serenity，恢复"core 是纯净底座"的依赖边界。</p>
 *
 * <p>实现语义：返回 Serenity 合并源中 key 的值；若该来源无此键返回 {@code null}，
 * 由 {@link ConfigSource} 降级到下一来源或默认值（与原 {@code SystemEnvironmentVariables
 * .currentEnvironmentVariables().getProperty(key, defaultValue)} 行为一致）。</p>
 */
public class SerenityConfigResolver implements ConfigResolver {

    @Override
    public String resolve(String key) {
        return SystemEnvironmentVariables.currentEnvironmentVariables().getProperty(key, null);
    }
}
