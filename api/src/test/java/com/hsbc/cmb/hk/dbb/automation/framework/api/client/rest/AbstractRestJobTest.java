package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl.RestGetJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PAR-7 api 模块补测（2026-09-17）：补齐 {@link AbstractRestJob} 中尚未被覆盖、且可<b>不触真实 HTTP</b>的逻辑。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code stripHtmlWrapper}：JSON/数组直通、HTML 包裹提取、空白/无 body 标签回退、null/empty；</li>
 *   <li>{@code applyResponse}：状态/头/体/ cookie 从 ValidatableResponse 回写 Entity（Mockito 深桩，
 *       规避 SerenityRest 在纯 JUnit 下的 classloader 约束）；</li>
 *   <li>{@code get/setValidatableResponse} 回环；</li>
 *   <li>{@code getRestAssuredConfig}：静态初始化已装配出非空配置（间接覆盖连接池/超时装配路径）。</li>
 * </ul>
 * 重试内核（{@code shouldRetry} / {@code executeWithRetry}）已由 {@code AbstractRestJobRetryTest} /
 * {@code AbstractRestJobRetryLoopTest} 覆盖，此处不复测。
 */
class AbstractRestJobTest {

    private final AbstractRestJob job = new RestGetJob();

    // ── stripHtmlWrapper ──

    @Test
    void stripHtmlWrapper_nullAndEmpty_passthrough() {
        assertThat(job.stripHtmlWrapper(null)).isNull();
        assertThat(job.stripHtmlWrapper("")).isEmpty();
        assertThat(job.stripHtmlWrapper("   \t ")).isEmpty();
    }

    @Test
    void stripHtmlWrapper_pureJson_passthrough() {
        assertThat(job.stripHtmlWrapper("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(job.stripHtmlWrapper("[1,2,3]")).isEqualTo("[1,2,3]");
    }

    @Test
    void stripHtmlWrapper_htmlWrapped_extractsBodyContent() {
        assertThat(job.stripHtmlWrapper("<html><body><div>hi</div></body></html>"))
                .isEqualTo("<div>hi</div>");
        assertThat(job.stripHtmlWrapper("<body>content</body>")).isEqualTo("content");
    }

    @Test
    void stripHtmlWrapper_noBodyTag_returnsTrimmedRaw() {
        assertThat(job.stripHtmlWrapper("<p>text</p>")).isEqualTo("<p>text</p>");
        assertThat(job.stripHtmlWrapper("  plain text  ")).isEqualTo("plain text");
    }

    // ── applyResponse ──

    @Test
    void applyResponse_writesStatusCodeCookiesHeadersAndStrippedBody() {
        ValidatableResponse resp = mock(ValidatableResponse.class, RETURNS_DEEP_STUBS);
        when(resp.extract().statusCode()).thenReturn(201);
        // 深桩下 asString() 默认返回 null，这里显式桩出 JSON 体，验证「回写 + HTML 包裹剥离」
        when(resp.extract().response().body().asString()).thenReturn("{\"ok\":true}");

        Entity entity = new Entity();
        entity.setEndpoint("/orders");
        job.applyResponse(entity, resp);

        assertThat(entity.getResponseCode()).isEqualTo(201);
        // 深桩下 cookies()/headers() 返回（可能为空的）集合，关键是不抛异常且写入非空引用
        assertThat(entity.getResponseCookies()).isNotNull();
        assertThat(entity.getResponseHeaders()).isNotNull();
        // JSON 体直通 stripHtmlWrapper；断言内容回写正确（非 null）
        assertThat(entity.getResponsePayload()).isEqualTo("{\"ok\":true}");
    }

    // ── get/setValidatableResponse 回环 ──

    @Test
    void validatableResponse_roundtrip() {
        ValidatableResponse resp = mock(ValidatableResponse.class);
        job.setValidatableResponse(resp);
        assertThat(job.getValidatableResponse()).isSameAs(resp);
    }

    // ── 静态配置装配 ──

    @Test
    void restAssuredConfig_isInitialized() {
        // 触发（或复用）类静态块中的连接池 / 超时装配，断言产出非空配置
        assertThat(AbstractRestJob.getRestAssuredConfig()).isNotNull();
    }
}
