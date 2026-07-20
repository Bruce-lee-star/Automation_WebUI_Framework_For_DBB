package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * 基于 {@link ResourceBundle}（.properties 资源包）的 i18n 可见名提供器。
 *
 * <p>支持两种 NLS（National Language Support）组织方式：
 * <ol>
 *   <li><b>共享 baseName（同目录不同后缀）</b>：
 *       {@code new ResourceBundleNameProvider("messages", Locale.ENGLISH, new Locale("zh"))}
 *       会从 {@code messages_en.properties} / {@code messages_zh.properties} 加载，兼容旧约定。</li>
 *   <li><b>每语言独立 NLS（baseName/目录不同）</b>：
 *       {@code new ResourceBundleNameProvider(Map.of(Locale.ENGLISH, "i18n/en/messages", new Locale("zh"), "i18n/zh/messages"))}
 *       每个语言加载自己专属的资源包，<b>key 可不对齐</b>——切语言即切换到该语言专属的 NLS。</li>
 * </ol>
 *
 * <p>{@link #namesForKey(String)} 返回该 key 在<b>所有已加载语言 NLS</b>中的可见名（去重），
 * 用于生成<b>跨语言稳定</b>的定位器 {@code element(role, name_en, name_zh, ...)}（底层正则 OR）。
 * 这与「当前运行在哪国语言」无关——无论切到哪种语言，定位器都含全部语言 name，故都能命中。
 *
 * <h3>资源文件约定（每语言独立 NLS 示例）</h3>
 * <pre>
 *   src/main/resources/i18n/en/messages.properties     （英文 NLS，独立目录）
 *   src/main/resources/i18n/zh/messages.properties      （中文 NLS，独立目录，key 可与 en 不对齐）
 * </pre>
 * key 与页面 {@code data-i18n} 属性值一致。某语言 NLS 缺失该 key 时静默跳过（不阻断跨语言合并）。
 *
 * @see I18nNameProvider
 * @see AccessibilityTreeExtractor#interactiveElementsAsPageElements(AxNode, BasePage, I18nNameProvider)
 */
public class ResourceBundleNameProvider implements I18nNameProvider {

    private static final Logger logger = LoggerFactory.getLogger(ResourceBundleNameProvider.class);

    /** 单语言 NLS 记录：locale（本提供器记录的「语言身份」） + 已加载资源包。 */
    private static final class Nls {
        final Locale locale;
        final ResourceBundle bundle;
        Nls(Locale locale, ResourceBundle bundle) {
            this.locale = locale;
            this.bundle = bundle;
        }
    }

    /** 已加载的全部语言 NLS（含兜底默认包），按加载顺序。 */
    private final List<Nls> nlsList = new ArrayList<>();

    /**
     * 共享 baseName 构造：baseName + 各 Locale 后缀（兼容旧约定）。
     *
     * @param baseName 资源包基名，如 {@code "messages"}
     * @param locales  参与的多语言 Locale；传空则仅加载默认（无后缀）资源
     */
    public ResourceBundleNameProvider(String baseName, Locale... locales) {
        if (baseName == null) {
            throw new IllegalArgumentException("baseName must not be null");
        }
        if (locales != null) {
            Set<Locale> seen = new HashSet<>();
            for (Locale loc : locales) {
                if (loc != null && seen.add(loc)) {
                    addBundle(baseName, loc);
                }
            }
        }
        addBundle(baseName, Locale.ROOT);   // 默认（无后缀）资源兜底
    }

    /**
     * 每语言独立 NLS 构造：每个 Locale 映射到自己专属的 baseName（可不同目录/文件）。
     * <p>切语言即切到对应 NLS；不同语言 key 集合可不对齐，缺失的 key 在该语言下静默跳过，
     * 不影响跨语言合并（{@link #namesForKey}）。
     *
     * @param localeToBaseName Locale → 该语言 NLS 的 baseName（如 {@code "i18n/zh/messages"}）；
     *                         {@code null}/空则无任何语言包，{@link #namesForKey} 恒返回空
     */
    public ResourceBundleNameProvider(Map<Locale, String> localeToBaseName) {
        if (localeToBaseName != null) {
            Set<String> seen = new HashSet<>();
            for (Map.Entry<Locale, String> e : localeToBaseName.entrySet()) {
                Locale loc = e.getKey();
                String base = e.getValue();
                if (loc != null && base != null && seen.add(base + "|" + loc)) {
                    addBundle(base, loc);
                }
            }
        }
    }

    private void addBundle(String baseName, Locale loc) {
        try {
            nlsList.add(new Nls(loc, ResourceBundle.getBundle(baseName, loc)));
        } catch (MissingResourceException e) {
            // 该语言 NLS 缺失：跳过，不阻断其余语言
            logger.debug("[i18n] NLS bundle not found: baseName='{}', locale='{}' -> {}",
                    baseName, loc, e.getMessage());
        }
    }

    @Override
    public String[] namesForKey(String key) {
        if (key == null || key.isBlank()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Nls nls : nlsList) {
            if (!nls.bundle.containsKey(key)) {
                continue;
            }
            String value = nls.bundle.getString(key);
            if (value != null && !value.isBlank() && seen.add(value)) {
                out.add(value);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 仅返回<b>指定语言 NLS</b>中该 key 的可见名（不合并其他语言）。
     * <p>用于「切语言后单独看当前语言」的场景，或诊断某语言 NLS 是否缺失该 key。
     *
     * @param key i18n key；{@code null}/空白或 loc 为 {@code null} 时返回空数组
     * @param loc 目标语言 Locale
     * @return 该语言 NLS 的 name 数组（去重、去空）；该语言无此 key 时返回长度 0 的数组
     */
    public String[] namesForKeyInLocale(String key, Locale loc) {
        if (key == null || key.isBlank() || loc == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Nls nls : nlsList) {
            if (!loc.equals(nls.locale)) {
                continue;
            }
            if (!nls.bundle.containsKey(key)) {
                continue;
            }
            String value = nls.bundle.getString(key);
            if (value != null && !value.isBlank() && seen.add(value)) {
                out.add(value);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 反向查找：给定某语言可见名（如 AX 树当前语言文本），返回该 key 在所有已加载语言 NLS 中的 name（跨语言）。
     * <p>用于 HTML 标签<b>未必带 i18n 属性</b>的场景：没有语言无关 key 时，用可见文本在 NLS 资源里
     * 反查出 key，再 {@link #namesForKey(String)} 跨语言合并。文本不在任何 NLS 中则返回空数组。
     */
    @Override
    public String[] namesForValue(String visibleText) {
        if (visibleText == null || visibleText.isBlank()) {
            return new String[0];
        }
        // 1) 找到包含该可见文本（某语言 NLS 值）的 key
        String matchedKey = null;
        for (Nls nls : nlsList) {
            for (String key : nls.bundle.keySet()) {
                if (visibleText.equals(nls.bundle.getString(key))) {
                    matchedKey = key;
                    break;
                }
            }
            if (matchedKey != null) {
                break;
            }
        }
        if (matchedKey == null) {
            return new String[0];   // 不在任何 NLS 中，无法反查跨语言 name
        }
        // 2) 返回该 key 在所有语言 NLS 的 name（跨语言合并）
        return namesForKey(matchedKey);
    }
}
