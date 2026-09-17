package com.hsbc.cmb.hk.dbb.automation.tests.api;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-7 约定守卫：JSON Schema 文件放在<b>用例模块</b>{@code src/test/resources/schemas/} 下，
 * 由 Runner 的 classpath 加载（而非框架模块的 resources）。
 *
 * <p>这个位置约定是真实工程风险点：schema 若误放在框架模块的 {@code src/main/resources}，
 * 用例侧仍能加载但会与框架发布物耦合；若放在错误的测试 resources 路径下，则加载失败——
 * 而「加载失败必须失败快」正是 P-7 的显式设计（否则会退化成永真断言）。本测试同时锁定
 * 「用例模块 resources 上的 schema 可被发现」与「缺失即失败快」两条语义。
 */
class SchemaResourceOnRunnerClasspathTest {

    private static final String SCHEMA = "schemas/smoke-hello.json";

    @Test
    void schemaInTestResources_isLoadableFromRunnerClasspath() {
        assertTrue(SchemaResourceOnRunnerClasspathTest.class.getClassLoader().getResource(SCHEMA) != null,
                "用例模块 src/test/resources/schemas/ 下的 schema 必须可被 Runner classpath 发现：" + SCHEMA);

        JsonSchemaValidator.preload(SCHEMA); // 缺失/不可解析会抛 IllegalStateException
    }

    @Test
    void missingSchema_failsFast_notSilentlyPassing() {
        assertThrows(IllegalStateException.class,
                () -> JsonSchemaValidator.assertMatches("{\"message\":\"hi\"}", "schemas/absent.json"),
                "schema 缺失必须失败快（否则断言静默通过，比没有校验更危险）");
    }
}
