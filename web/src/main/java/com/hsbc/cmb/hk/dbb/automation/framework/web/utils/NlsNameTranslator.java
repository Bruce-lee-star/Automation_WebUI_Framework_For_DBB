package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import com.huaban.analysis.jieba.JiebaSegmenter;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 离线「中文元素名 → 英文标识符」翻译器，供代码生成器（Page / Step 生成）使用。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>纯离线、零模型权重</b>：仅依赖 jieba-analysis（分词）与 pinyin4j（拼音），
 *       不联网、不加载任何大模型，生成的工程可随框架直接分发。</li>
 *   <li><b>语义优先 + 拼音兜底</b>：先用 jieba 把中文切成语义词；命中内置「高频 UI 语义词典」
 *       （如 按钮→Btn、弹窗→Dialog、输入框→Input）则直接输出语义英文；未命中的中文词
 *       回退 pinyin4j 转拼音（首字母大写驼峰）。两者都不覆盖的纯英文/数字/符号原样保留。</li>
 *   <li><b>不是全量手写词库</b>：语义词典只收录 Web UI 自动化里高频出现的少量控件/方位词，
 *       其余一律走拼音兜底，因此不要求手写完整词表，也不受词表覆盖度限制。</li>
 *   <li>输出保证是合法 Java 标识符片段（去除空白/标点、首字符非数字），驼峰拼接；
 *       是否首字母大写由调用方通过 {@link #toIdentifier(String, boolean)} 决定。</li>
 * </ol>
 */
public final class NlsNameTranslator {

    private static final Logger log = LoggerFactory.getLogger(NlsNameTranslator.class);

    /** 高频 UI 语义词典：中文词 → 英文后缀/片段。只覆盖自动化常见控件与方位，不做全量翻译。 */
    private static final java.util.Map<String, String> UI_TERMS = new java.util.LinkedHashMap<>();
    static {
        // 控件类
        UI_TERMS.put("按钮", "Btn");
        UI_TERMS.put("按键", "Btn");
        UI_TERMS.put("弹窗", "Dialog");
        UI_TERMS.put("对话框", "Dialog");
        UI_TERMS.put("输入框", "Input");
        UI_TERMS.put("文本框", "Input");
        UI_TERMS.put("输入", "Input");
        UI_TERMS.put("链接", "Link");
        UI_TERMS.put("复选框", "Chk");
        UI_TERMS.put("勾选", "Chk");
        UI_TERMS.put("单选", "Radio");
        UI_TERMS.put("下拉", "Select");
        UI_TERMS.put("选择", "Select");
        UI_TERMS.put("文本", "Text");
        UI_TERMS.put("标签", "Label");
        UI_TERMS.put("表格", "Table");
        UI_TERMS.put("菜单", "Menu");
        UI_TERMS.put("图标", "Icon");
        UI_TERMS.put("图片", "Img");
        UI_TERMS.put("页面", "Page");
        UI_TERMS.put("子页", "SubPage");
        UI_TERMS.put("首页", "Home");
        UI_TERMS.put("登录", "Login");
        UI_TERMS.put("登出", "Logout");
        UI_TERMS.put("提交", "Submit");
        UI_TERMS.put("确认", "Confirm");
        UI_TERMS.put("取消", "Cancel");
        UI_TERMS.put("保存", "Save");
        UI_TERMS.put("搜索", "Search");
        UI_TERMS.put("关闭", "Close");
        UI_TERMS.put("打开", "Open");
        UI_TERMS.put("返回", "Back");
        UI_TERMS.put("下一步", "Next");
        UI_TERMS.put("上一步", "Prev");
        UI_TERMS.put("设置", "Setting");
        UI_TERMS.put("内容", "Content");
        UI_TERMS.put("标题", "Title");
        UI_TERMS.put("错误", "Error");
        UI_TERMS.put("成功", "Success");
        UI_TERMS.put("警告", "Warn");
        UI_TERMS.put("信息", "Info");
        UI_TERMS.put("列表", "List");
        UI_TERMS.put("项", "Item");
        UI_TERMS.put("切换", "Toggle");
        // 方位/结构类（用于 iframe 嵌套描述）
        UI_TERMS.put("内", "Inner");
        UI_TERMS.put("嵌套", "Nested");
        UI_TERMS.put("外", "Outer");
        UI_TERMS.put("主", "Main");
        UI_TERMS.put("顶", "Top");
        UI_TERMS.put("底", "Bottom");
        UI_TERMS.put("左", "Left");
        UI_TERMS.put("右", "Right");
    }

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[\\s_\\-/·•]+");
    private static final Pattern INVALID_ID_START = Pattern.compile("[^A-Za-z_$]");
    private static final Pattern INVALID_ID_CHAR = Pattern.compile("[^A-Za-z0-9_$]");

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();
    private static final HanyuPinyinOutputFormat PINYIN_FMT = new HanyuPinyinOutputFormat();
    static {
        PINYIN_FMT.setCaseType(HanyuPinyinCaseType.UPPERCASE);
        PINYIN_FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    private NlsNameTranslator() {
    }

    /**
     * 把中文（含中英文混合）元素名翻译为英文标识符片段，首字母大写（PascalCase 风格）。
     * 适合字段名、方法名等需要首字母大写的场景。
     *
     * @param raw 原始元素名（可能含中文、英文、数字、空格、标点）
     * @return 首字母大写的英文标识符片段；若翻译结果为空则返回 {@code "Element"}
     */
    public static String toIdentifier(String raw) {
        return toIdentifier(raw, true);
    }

    /**
     * 把中文元素名翻译为英文标识符片段。
     *
     * @param raw          原始元素名
     * @param upperFirst   是否首字母大写（true→PascalCase；false→camelCase）
     * @return 英文标识符片段；若翻译结果为空则返回 {@code "Element"}（upperFirst）或
     *         {@code "element"}（非 upperFirst）
     */
    public static String toIdentifier(String raw, boolean upperFirst) {
        if (raw == null || raw.isEmpty()) {
            return upperFirst ? "Element" : "element";
        }
        // 1) 粗略按空白/分隔符切分，先处理「整段已被空格/下划线分隔」的情况
        String normalized = raw.trim();
        List<String> parts = new ArrayList<>();
        for (String seg : WORD_BOUNDARY.split(normalized)) {
            if (seg.isEmpty()) continue;
            parts.addAll(translateSegment(seg));
        }
        // 2) 拼接驼峰
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (first) {
                if (upperFirst) {
                    sb.append(capitalize(p));
                } else {
                    sb.append(p); // 首段保持原大小写（通常已是小写拼音）
                }
                first = false;
            } else {
                sb.append(capitalize(p));
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            return upperFirst ? "Element" : "element";
        }
        // 3) 保证合法 Java 标识符：首字符非字母/下划线/$ 则补前缀
        if (INVALID_ID_START.matcher(result.substring(0, 1)).matches()) {
            result = "_" + result;
        }
        // 去除其余非法字符
        result = INVALID_ID_CHAR.matcher(result).replaceAll("");
        return result.isEmpty() ? (upperFirst ? "Element" : "element") : result;
    }

    /**
     * 翻译单个连续片段（内部不再含空白）：先 jieba 分词，逐词查语义词典或转拼音。
     */
    private static List<String> translateSegment(String seg) {
        List<String> out = new ArrayList<>();
        // 整段已是纯 ASCII（英文/数字/符号）→ 原样作为一段（去除符号后）
        if (!CJK.matcher(seg).find()) {
            String cleaned = INVALID_ID_CHAR.matcher(seg).replaceAll("");
            if (!cleaned.isEmpty()) out.add(cleaned);
            return out;
        }
        // 含中文：jieba 分词
        List<String> tokens;
        try {
            tokens = SEGMENTER.sentenceProcess(seg);
        } catch (Exception e) {
            log.warn("[NlsNameTranslator] jieba segment failed for '{}', fallback char-by-char", seg, e);
            tokens = java.util.Arrays.asList(seg.split(""));
        }
        for (String tok : tokens) {
            if (tok == null || tok.isEmpty()) continue;
            if (UI_TERMS.containsKey(tok)) {
                out.add(UI_TERMS.get(tok));
                continue;
            }
            if (!CJK.matcher(tok).find()) {
                // 非中文 token（英文/数字），原样保留
                String cleaned = INVALID_ID_CHAR.matcher(tok).replaceAll("");
                if (!cleaned.isEmpty()) out.add(cleaned);
                continue;
            }
            // 中文词：逐字转拼音并首字母大写
            String py = toPinyinCamel(tok);
            if (!py.isEmpty()) out.add(py);
        }
        return out;
    }

    /** 把一个中文字串转成首字母大写的拼音驼峰（如 "跳转" → "TiaoZhuan"）。 */
    private static String toPinyinCamel(String han) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < han.length(); i++) {
            char c = han.charAt(i);
            if (!CJK.matcher(String.valueOf(c)).matches()) {
                if (Character.isLetterOrDigit(c)) sb.append(c);
                continue;
            }
            try {
                String[] pys = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FMT);
                if (pys != null && pys.length > 0) {
                    String py = pys[0]; // 取第一个读音
                    if (py != null && !py.isEmpty()) {
                        sb.append(Character.toUpperCase(py.charAt(0)));
                        if (py.length() > 1) sb.append(py.substring(1).toLowerCase());
                    }
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                // 忽略无法转拼音的字符
            }
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
