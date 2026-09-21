package com.hsbc.cmb.hk.dbb.automation.framework.api.config;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.EntityBuilder;
import com.typesafe.config.Config;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfigProvider} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：本类是配置解析链的入口，其<b>降级语义</b>被全框架依赖——「配置文件缺失不得抛异常，
 * 而应回退空配置 / 默认路径」「entity 配置缺失时仅用默认配置」；一旦退化为抛异常，
 * 未配置 entity 的用例会整体崩掉而非按预期跑通。故本测试固化：
 * <ul>
 *   <li><b>路径契约</b>：basePath / configBasePath / payloadBasePath 均可用且以分隔符结尾（拼接即合法路径）；</li>
 *   <li><b>资材解析</b>：空名返回空串（不抛）、缺失资材返回空串、命中资材返回存在的绝对路径；</li>
 *   <li><b>配置降级</b>：无任何配置文件时 {@code getConfig()} 仍返回非 null（空配置兜底）、
 *       未知 key 返回空配置、null entity 与「entity 文件缺失 + 未知环境」两条路径均不抛异常。</li>
 * </ul>
 * 说明：用例不引入 {@code application.conf}——那会改变 api 全部用例的配置解析环境；此处只固化
 * 「无配置时的降级行为」，这正是生产环境最需要被守住的一侧。
 */
class ConfigProviderTest {

    @Test
    void pathGettersProduceUsablePaths() {
        String base = ConfigProvider.getBasePath();
        assertThat(base).isNotBlank();

        assertThat(ConfigProvider.getConfigBasePath()).startsWith(base).endsWith(File.separator);
        assertThat(ConfigProvider.getPayloadBasePath()).startsWith(base).endsWith(File.separator);
    }

    @Test
    void getPayloadPathRejectsBlankNameAndReportsMissingFileAsEmpty() {
        assertThat(ConfigProvider.getPayloadPath(null)).isEmpty();
        assertThat(ConfigProvider.getPayloadPath("   ")).isEmpty();
        assertThat(ConfigProvider.getPayloadPath("no-such-payload-for-coverage-xyz.txt")).isEmpty();
    }

    @Test
    void getPayloadPathResolvesExistingPayloadFileToRealPath() {
        String resolved = ConfigProvider.getPayloadPath("config-provider-probe.txt");

        assertThat(resolved).isNotBlank();
        assertThat(new File(resolved)).exists();
    }

    @Test
    void getConfigFallsBackToNonNullConfigWithoutConfigFiles() {
        Config fallback = ConfigProvider.getConfig();

        assertThat(fallback).isNotNull();
        // 无 application.conf 时兜底结果仍会并入各依赖包自带的 reference.conf，故只断言
        // 「不含 api 的业务配置键」——这才是"降级为空配置"的可判定含义。
        assertThat(fallback.hasPath("paths.base-path")).isFalse();
    }

    @Test
    void getConfigByKeyReturnsEmptyConfigForAbsentKey() {
        Config sub = ConfigProvider.getConfig("dbb.absent.sub.config");

        assertThat(sub).isNotNull();
        assertThat(sub.isEmpty()).isTrue();
    }

    @Test
    void configForNullEntityLoadsDefaultSnapshotWithoutThrowing() {
        assertThat(ConfigProvider.config((Entity) null)).isNotNull();
    }

    @Test
    void configForEntityWithoutEntityFileAndUnknownEnvFallsBackToBaseConfig() {
        Entity entity = EntityBuilder.build("coverage-probe-entity");

        Config config = ConfigProvider.config(entity, "no-such-environment-for-coverage");

        assertThat(config).isNotNull();
        // 未知环境节点应被跳过（不注入环境覆盖、也不抛异常）
        assertThat(config.hasPath("no-such-environment-for-coverage")).isFalse();
    }
}
