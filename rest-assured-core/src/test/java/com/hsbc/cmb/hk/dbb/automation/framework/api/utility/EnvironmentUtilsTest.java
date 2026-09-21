package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnvironmentUtils} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：该类是 Serenity 已弃用 API（{@code SystemEnvironmentVariables}）的替代入口，
 * 被配置解析链直接消费（如 {@code ConfigProvider} 读取当前环境名）。契约是
 * 「<b>系统属性读写往返一致 + 缺失返回 null / 显式默认值</b>」。测试使用自建探针键并在结束前清理，
 * 避免污染同 JVM 的其它用例。
 */
class EnvironmentUtilsTest {

    private static final String PROBE_KEY = "dbb.envutils.probe";
    private static final String ENV_KEY = "env";

    @Test
    void setGetClearRoundTripOnInstanceApi() {
        EnvironmentUtils env = EnvironmentUtils.currentEnvironment();
        try {
            env.setProperty(PROBE_KEY, "probe-value");
            assertThat(env.getProperty(PROBE_KEY)).isEqualTo("probe-value");
            assertThat(EnvironmentUtils.getProperty(PROBE_KEY, "fallback")).isEqualTo("probe-value");
        } finally {
            env.clearProperty(PROBE_KEY);
        }
        assertThat(env.getProperty(PROBE_KEY)).isNull();
    }

    @Test
    void staticGetPropertyFallsBackToDefaultWhenAbsent() {
        assertThat(EnvironmentUtils.getProperty("dbb.envutils.absent.key", "fallback")).isEqualTo("fallback");
    }

    @Test
    void environmentNameIsReadFromSystemProperty() {
        EnvironmentUtils env = EnvironmentUtils.currentEnvironment();
        boolean hadEnv = System.getProperty(ENV_KEY) != null;
        String original = System.getProperty(ENV_KEY);
        try {
            env.setProperty(ENV_KEY, "sit-probe");
            assertThat(env.getEnvironmentName()).isEqualTo("sit-probe");
        } finally {
            if (hadEnv) {
                env.setProperty(ENV_KEY, original);
            } else {
                env.clearProperty(ENV_KEY);
            }
        }
    }

    @Test
    void environmentVariableLookupReturnsNullForUnknownKey() {
        assertThat(EnvironmentUtils.currentEnvironment()
                .getEnvironmentVariable("DBB_ENVUTILS_DEFINITELY_ABSENT_VARIABLE")).isNull();
    }
}
