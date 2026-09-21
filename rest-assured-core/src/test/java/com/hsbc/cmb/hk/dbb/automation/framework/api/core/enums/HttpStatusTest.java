package com.hsbc.cmb.hk.dbb.automation.framework.api.core.enums;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpStatus} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：该枚举是「响应状态码断言」的支撑数据——码值与原因短语一旦写错（例如把 302 写成
 * 「Found」之外的短语、或与另一常量撞码），断言层会给出**错误的结论**（把失败判为通过）；
 * 分类谓词（{@code isSuccess}/{@code isClientError}/…）被断言与报告逻辑直接消费，语义必须与码段严格一致。
 * 故本测试固化三类不变量：<b>逐项自洽</b>（码段有界、原因非空、码不重复、分类谓词与码段一致且互斥）、
 * <b>查找语义</b>（{@code fromCode} 全量可往返、未知码快速失败）、<b>边界与呈现</b>（{@code matches}、
 * {@code toString} 格式）。
 */
class HttpStatusTest {

    @Test
    void everyConstantCarriesCodeReasonAndConsistentCategory() {
        Set<Integer> seenCodes = new HashSet<>();
        for (HttpStatus status : HttpStatus.values()) {
            int code = status.getCode();

            assertThat(code).as("状态码应在合法区间: %s", status.name()).isBetween(100, 599);
            assertThat(status.getReason()).as("原因短语不得为空: %s", status.name()).isNotBlank();
            assertThat(seenCodes.add(code)).as("状态码不得重复: %s", code).isTrue();

            // 分类谓词必须与码段一致（互斥的五段），且 isError 恰为 4xx/5xx 之并
            boolean informational = code < 200;
            boolean success = code >= 200 && code < 300;
            boolean redirection = code >= 300 && code < 400;
            boolean clientError = code >= 400 && code < 500;
            boolean serverError = code >= 500;

            assertThat(status.isInformational()).as("isInformational: %s", status.name()).isEqualTo(informational);
            assertThat(status.isSuccess()).as("isSuccess: %s", status.name()).isEqualTo(success);
            assertThat(status.isRedirection()).as("isRedirection: %s", status.name()).isEqualTo(redirection);
            assertThat(status.isClientError()).as("isClientError: %s", status.name()).isEqualTo(clientError);
            assertThat(status.isServerError()).as("isServerError: %s", status.name()).isEqualTo(serverError);
            assertThat(status.isError()).as("isError: %s", status.name()).isEqualTo(clientError || serverError);

            // 分类互斥：恰有一个成立（isError 是并集，不参与互斥）
            int hits = (informational ? 1 : 0) + (success ? 1 : 0) + (redirection ? 1 : 0)
                    + (clientError ? 1 : 0) + (serverError ? 1 : 0);
            assertThat(hits).as("分类必须恰好命中一类: %s", status.name()).isEqualTo(1);
        }
    }

    @Test
    void fromCodeFindsEveryConstantByItsCode() {
        for (HttpStatus status : HttpStatus.values()) {
            assertThat(HttpStatus.fromCode(status.getCode())).isSameAs(status);
        }
    }

    @Test
    void fromCodeRejectsUnknownCodeFastWithActionableMessage() {
        assertThatThrownBy(() -> HttpStatus.fromCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void matchesComparesByCodeOnly() {
        assertThat(HttpStatus.OK.matches(200)).isTrue();
        assertThat(HttpStatus.OK.matches(201)).isFalse();
        assertThat(HttpStatus.NOT_FOUND.matches(404)).isTrue();
        assertThat(HttpStatus.NOT_FOUND.matches(200)).isFalse();
    }

    @Test
    void toStringIsCodeFollowedByReason() {
        assertThat(HttpStatus.OK.toString()).isEqualTo("200 OK");
        assertThat(HttpStatus.CREATED.toString()).isEqualTo("201 Created");
        assertThat(HttpStatus.INTERNAL_SERVER_ERROR.toString()).isEqualTo("500 Internal Server Error");
    }
}
