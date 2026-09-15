package com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EntityBuilder 单测（评审 P-2 / 05-P-1）：验证实体构建的输入校验与动态空实体路径。
 *
 * <p>覆盖纯内存逻辑（不依赖外部 {@code *.conf} 配置文件），确保 {@code entityName}
 * 的空/空白/ null 入参按企业级契约抛出 {@link IllegalArgumentException}，且
 * {@link EntityBuilder#buildNull()} 返回可用空实体供动态装配。
 */
class EntityBuilderTest {

    @Test
    void buildNullReturnsUsableEmptyEntity() {
        Entity entity = EntityBuilder.buildNull();
        assertThat(entity).isNotNull();
    }

    @Test
    void buildWithNullNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> EntityBuilder.build((String) null));
    }

    @Test
    void buildWithEmptyNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> EntityBuilder.build(""));
    }

    @Test
    void buildWithBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> EntityBuilder.build("   "));
    }

    @Test
    void buildWithEnvOverrideBlankIsTreatedAsNoEnv() {
        // env 为空串/空白时应等价于不传 env（不抛 NPE），但 entityName 仍非法 → 抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> EntityBuilder.build("   ", "  "));
    }
}
