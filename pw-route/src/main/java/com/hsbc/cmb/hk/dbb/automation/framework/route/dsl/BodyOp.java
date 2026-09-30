package com.hsbc.cmb.hk.dbb.automation.framework.route.dsl;

/**
 * 请求体修改操作（不可变）—— MODIFY_REQUEST 的 body 级修改描述。
 *
 * <p>{@code path} 语义由请求 Content-Type 决定（JSONPath / 表单字段名），
 * 在 IO 线程由 {@code RequestBodyModifier} 路由执行。
 *
 * <p>{@code value} 为原始类型（JSON 场景保留类型：Integer/Double/Boolean/String；
 * 表单场景由实现转字符串）。
 */
public record BodyOp(BodyOpType type, String path, Object value) {

    public enum BodyOpType {
        /** 设置：存在则更新，不存在则创建（JSON 按路径创建；表单键不存在则追加）。 */
        SET,
        /** 添加：JSON 数组追加；表单追加重复键。 */
        ADD,
        /** 删除：JSON 删除路径；表单删除全部该键。 */
        REMOVE
    }

    public BodyOp {
        if (type == null || path == null || path.isEmpty()) {
            throw new IllegalArgumentException("body op requires non-null type and non-empty path");
        }
    }
}
