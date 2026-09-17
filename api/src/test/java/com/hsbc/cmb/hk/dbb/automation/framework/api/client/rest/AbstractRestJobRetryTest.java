package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl.RestGetJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-3 重试判定单测（纯逻辑，不触 RestAssured，规避 api 测试 classpath 既有
 * serenity-rest-assured shaded / standalone rest-assured 双副本冲突）。
 *
 * <p>覆盖 {@link AbstractRestJob#shouldRetry(int, boolean, int, int)} 内核规则：
 * 仅当<b>幂等</b>且响应为 <b>5xx</b> 且<b>未达最大尝试次数</b>时重试；
 * 4xx（客户端错误）与非幂等写操作（POST/PATCH）一律不重试。
 */
class AbstractRestJobRetryTest {

    private final AbstractRestJob job = new RestGetJob();

    @Test
    void shouldRetry_rules() {
        // 幂等 + 5xx + 未达上限 -> 重试
        assertThat(job.shouldRetry(503, true, 1, 3)).isTrue();
        assertThat(job.shouldRetry(500, true, 1, 3)).isTrue();
        assertThat(job.shouldRetry(599, true, 2, 3)).isTrue();

        // 2xx/3xx 不重试
        assertThat(job.shouldRetry(200, true, 1, 3)).isFalse();
        assertThat(job.shouldRetry(302, true, 1, 3)).isFalse();

        // 4xx（客户端错误）绝不重试
        assertThat(job.shouldRetry(400, true, 1, 3)).isFalse();
        assertThat(job.shouldRetry(404, true, 1, 3)).isFalse();
        assertThat(job.shouldRetry(429, true, 1, 3)).isFalse();

        // 非幂等写操作（POST/PATCH）绝不重试（含 5xx 与最后一次）
        assertThat(job.shouldRetry(503, false, 1, 3)).isFalse();
        assertThat(job.shouldRetry(500, false, 3, 3)).isFalse();

        // 已达最大尝试次数 -> 不再重试
        assertThat(job.shouldRetry(503, true, 3, 3)).isFalse();
        assertThat(job.shouldRetry(500, true, 2, 2)).isFalse();
    }
}
