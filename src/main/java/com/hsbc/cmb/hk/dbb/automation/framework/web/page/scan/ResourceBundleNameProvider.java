package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * 基于 {@link ResourceBundle}（{@code .properties} 资源包）的 i18n 可见名提供器。
 *
 * <p>给定 baseName（如 {@code "messages"}）与一组目标 {@link Locale}，按 key 从各语言
 * 资源文件取出可见名（如按钮文本、链接文本），合并去重后返回，供
 * {@code element(role, name_en, name_zh, ...)} 生成<b>跨语言稳定</b>的定位器。
 *
 * <h3>资源文件约定</h3>
 * <pre>
 *   src/main/resources/messages.properties       （默认/兜底，无后缀）
 *   src/main/resources/messages_en.properties     （英文，对应 Locale.ENGLISH）
 *   src/main/resources/messages_zh.properties      （中文，对应 new Locale("zh")）
 * </pre>
 * key 与页面 {@code data-i18n} 属性值一致，例如页面有 {@code <button data-i18n="button_next">}，
 * 则资源文件里 {@code button_next=Next} / {@code button_next=下一步}。
 *
 * <h3>用法</h3>
 * <pre>{@code
 *   I18nNameProvider i18n = new ResourceBundleNameProvider(
 *           "messages", Locale.ENGLISH, new Locale("zh"));
 *   // 之后交给 AccessibilityTreeExtractor.interactiveElementsAsPageElements(tree, page, i18n)
 * }</pre>
 *
 * <p>某语言资源缺失或不包含某 key 时，静默跳过该语言/key，不影响其余。
 *
 * @see I18nNameProvider
 * @see AccessibilityTreeExtractor#interactiveElementsAsPageElements(AxNode, BasePage, I18nNameProvider)
 */
public class ResourceBundleNameProvider implements I18nNameProvider {

    /** 加载到的资源包（含兜底默认包），按构造时传入的 Locale 顺序。 */
    private final List<ResourceBundle> bundles = new ArrayList<>();

    /**
     * @param baseName 资源包基名，如 {@code "messages"}
     * @param locales  参与的多语言 Locale（如 {@code Locale.ENGLISH}、{@code new Locale("zh")}）；
     *                 传空则仅加载默认（无后缀）资源
     */
    public ResourceBundleNameProvider(String baseName, Locale... locales) {
        if (locales != null) {
            Set<Locale> seen = new HashSet<>();
            for (Locale loc : locales) {
                if (loc != null && seen.add(loc)) {
                    try {
                        bundles.add(ResourceBundle.getBundle(baseName, loc));
                    } catch (MissingResourceException e) {
                        // 该语言资源缺失：跳过，不阻断其余语言
                    }
                }
            }
        }
        // 始终加载默认（无后缀）资源作为兜底
        try {
            bundles.add(ResourceBundle.getBundle(baseName));
        } catch (MissingResourceException ignored) {
            // 连默认资源都没有：后续 namesForKey 直接返回空
        }
    }

    @Override
    public String[] namesForKey(String key) {
        if (key == null || key.isBlank()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResourceBundle bundle : bundles) {
            if (!bundle.containsKey(key)) {
                continue;
            }
            String value = bundle.getString(key);
            if (value != null && !value.isBlank() && seen.add(value)) {
                out.add(value);
            }
        }
        return out.toArray(new String[0]);
    }
}
