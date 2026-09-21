package com.hsbc.cmb.hk.dbb.automation.framework.route.body;

/**
 * 请求体编解码与字段改写策略。
 *
 * <p>按 {@link #supports(String) Content-Type} 分发，将不同编码（JSON / urlencoded / multipart …）
 * 的「修改 / 新增 / 删除」统一抽象为 {@link #transform(byte[], BodyFieldOps, boolean)}。
 *
 * <p>设计约束（企业级）：
 * <ul>
 *   <li><b>不破坏未知载荷</b>：解析失败必须返回原始字节（绝不允许抛出或截断），交由上层安全降级。</li>
 *   <li><b>编码保真</b>：改写后保持原 Content-Type 与字符编码（如 urlencoded 重新百分号编码）。</li>
 *   <li><b>顺序保真</b>：表单字段 / 多段顺序在改写后维持原序（用 {@code LinkedHashMap} / 列表）。</li>
 *   <li><b>无副作用</b>：纯函数式，不修改入参字节数组。</li>
 * </ul>
 */
public interface BodyCodec {

    /**
     * 该 codec 是否处理此 Content-Type。
     * @param contentType 已规范化的 Content-Type（小写、已去除参数）；可能为 null
     */
    boolean supports(String contentType);

    /**
     * 解析体字节，按给定字段操作改写后返回新字节。
     *
     * @param raw     原始体字节（可能为空）
     * @param ops     字段操作（key 语义由具体 codec 定义）
     * @param fallback 解析失败是否退化（JSON 用；表单类一般传 false，解析失败直接返回 raw）
     * @return 改写后的体字节；无操作 / 解析失败 / 非本编码返回 {@code raw} 本身（引用或内容等价）
     */
    byte[] transform(byte[] raw, BodyFieldOps ops, boolean fallback);
}
