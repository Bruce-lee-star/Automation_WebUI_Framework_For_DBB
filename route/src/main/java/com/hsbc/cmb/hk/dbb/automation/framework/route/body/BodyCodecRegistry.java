package com.hsbc.cmb.hk.dbb.automation.framework.route.body;

import java.util.List;

/**
 * 请求体编解码注册表——按 Content-Type 分发到具体 {@link BodyCodec}。
 *
 * <p>作用：
 * <ul>
 *   <li>将「支持哪些编码」集中管理，新增编码（如 XML / 纯文本占位符）只需注册实现，无需改动 {@code ModifyHandler}。</li>
 *   <li>{@link #tryTransform} 返回 {@code null} 表示无 codec 处理该 Content-Type，由调用方走原始（raw）降级路径。</li>
 * </ul>
 *
 * <p>注：JSON 体因含 ParseOnce / 类型保持 / 字符串退化等专用逻辑，仍由 {@code ModifyHandler} 内联处理；
 * 表单类（urlencoded / multipart）走本注册表，后续可平滑将 JSON 也纳入同一抽象。
 */
public final class BodyCodecRegistry {

    private static final List<BodyCodec> CODECS = List.of(
            FormUrlEncodedBodyCodec.INSTANCE,
            MultipartBodyCodec.INSTANCE);

    private BodyCodecRegistry() {
    }

    /**
     * 尝试按 Content-Type 改写请求体。
     *
     * @return 改写后字节；若无 codec 支持该 Content-Type 则返回 {@code null}（调用方降级）
     */
    public static byte[] tryTransform(String contentType, byte[] raw, BodyFieldOps ops, boolean fallback) {
        if (contentType == null) {
            return null;
        }
        String ct = contentType.toLowerCase();
        for (BodyCodec codec : CODECS) {
            if (codec.supports(ct)) {
                return codec.transform(raw, ops, fallback);
            }
        }
        return null;
    }
}
