package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl.RestGetJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl.RestPostJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-3 重试内核端到端测试（Mockito 驱动 {@code executeWithRetry}，不发起真实 HTTP，
 * 从而绕开 SerenityRest 在纯 JUnit 下的 classloader 约束——该约束仅影响真实响应提取，
 * 不影响重试决策/循环逻辑；生产链路跑在 Serenity runner 下正常）。
 *
 * <p>覆盖：幂等 5xx 重试至上限、4xx 不重试、非幂等 5xx 不重试、
 * 幂等网络异常重试至上限后抛出、非幂等网络异常立即抛出。
 */
class AbstractRestJobRetryLoopTest {

    @BeforeAll
    static void shrinkBackoff() {
        // 缩小退避以加速测试（ConfigProvider 首次加载生效；若已被其它用例加载缓存则仅变慢，不影响正确性）
        System.setProperty("test.retry.delay", "20");
    }

    private AbstractRestJob idempotentJob() {
        return new RestGetJob();
    }

    private AbstractRestJob nonIdempotentJob() {
        return new RestPostJob();
    }

    private Entity entity() {
        Entity entity = new Entity();
        entity.setBaseUri("http://localhost");
        entity.setEndpoint("/x");
        return entity;
    }

    private ValidatableResponse stubResponse(int statusCode) {
        ValidatableResponse response = mock(ValidatableResponse.class, RETURNS_DEEP_STUBS);
        when(response.extract().statusCode()).thenReturn(statusCode);
        return response;
    }

    @Test
    void idempotentGet_retriesOn5xx_untilMaxAttempts() {
        ValidatableResponse resp = stubResponse(503);
        AtomicInteger calls = new AtomicInteger();
        Function<RequestSpecification, ValidatableResponse> invoker =
                spec -> {
                    calls.incrementAndGet();
                    return resp;
                };

        ValidatableResponse result = idempotentJob().executeWithRetry(entity(), invoker, true);

        assertThat(calls.get()).isEqualTo(3); // 默认 3 次（2 次重试）
        assertThat(result.extract().statusCode()).isEqualTo(503);
    }

    @Test
    void idempotentGet_doesNotRetryOn4xx() {
        ValidatableResponse resp = stubResponse(400);
        AtomicInteger calls = new AtomicInteger();
        Function<RequestSpecification, ValidatableResponse> invoker =
                spec -> {
                    calls.incrementAndGet();
                    return resp;
                };

        ValidatableResponse result = idempotentJob().executeWithRetry(entity(), invoker, true);

        assertThat(calls.get()).isEqualTo(1); // 4xx 立即返回
        assertThat(result.extract().statusCode()).isEqualTo(400);
    }

    @Test
    void nonIdempotentPost_doesNotRetryOn5xx() {
        ValidatableResponse resp = stubResponse(503);
        AtomicInteger calls = new AtomicInteger();
        Function<RequestSpecification, ValidatableResponse> invoker =
                spec -> {
                    calls.incrementAndGet();
                    return resp;
                };

        ValidatableResponse result = nonIdempotentJob().executeWithRetry(entity(), invoker, false);

        assertThat(calls.get()).isEqualTo(1); // 非幂等写操作不重试，避免重复提交
        assertThat(result.extract().statusCode()).isEqualTo(503);
    }

    @Test
    void idempotentGet_retriesOnNetworkError_thenThrows() {
        AtomicInteger calls = new AtomicInteger();
        Function<RequestSpecification, ValidatableResponse> invoker =
                spec -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("connection refused");
                };

        assertThatThrownBy(() -> idempotentJob().executeWithRetry(entity(), invoker, true))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
        assertThat(calls.get()).isEqualTo(3); // 幂等方法网络错误重试至上限
    }

    @Test
    void nonIdempotentPost_doesNotRetryOnNetworkError() {
        AtomicInteger calls = new AtomicInteger();
        Function<RequestSpecification, ValidatableResponse> invoker =
                spec -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("connection refused");
                };

        assertThatThrownBy(() -> nonIdempotentJob().executeWithRetry(entity(), invoker, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
        assertThat(calls.get()).isEqualTo(1); // 非幂等写操作网络错误不重试
    }
}
