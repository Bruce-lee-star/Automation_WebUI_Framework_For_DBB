package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 文本标准化工具类——统一的 Unicode 文本标准化管道。
 *
 * 管道步骤（按顺序）：
 * 1. NFKC 规范化 — 一次搞定 en-dash/em-dash→-、smart-quotes→"'、NBSP→空格、全角→半角
 * 2. 删除零宽空格等格式控制字符（\p{Cf}）
 * 3. 合并所有空白（空格/换行/制表）为单个空格
 * 4. 去掉中英文标点前的空格
 * 5. 首尾去空
 *
 * 使用场景：BasePage.waitForElementTextEquals、PageElement.getText()、文本比对等。
 * 避免在 BasePage 和 PageElement 中重复定义相同的 Pattern 常量和 normalize 逻辑。
 *
 * @since 1.0.0-FINANCIAL-GRADE
 */
public final class TextNormalizer {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([.,!?;:。，！？；：])");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cf}");

    private TextNormalizer() {
        // 工具类，禁止实例化
    }

    /**
     * 标准化文本。
     *
     * @param raw 原始文本，可为 null
     * @return 标准化后的文本，null 输入返回空字符串
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // 1. NFKC 规范化
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        // 2. 删除格式控制字符
        normalized = CONTROL_CHARS.matcher(normalized).replaceAll("");
        // 3. 合并空白
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ");
        // 4. 去掉标点前空格
        normalized = SPACE_BEFORE_PUNCT.matcher(normalized).replaceAll("$1");
        // 5. trim
        return normalized.trim();
    }
}
