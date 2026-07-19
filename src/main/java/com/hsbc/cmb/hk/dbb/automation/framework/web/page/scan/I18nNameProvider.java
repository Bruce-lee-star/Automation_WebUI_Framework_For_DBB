package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

/**
 * 国际化（i18n）可见名提供器。
 *
 * <p>把「i18n key」（如页面 {@code data-i18n="button_next"} 的属性值）解析为
 * <b>所有语言</b>的可见名候选，喂给 {@link PageElement.RolePageElement}
 * 的多 name 正则 OR 定位（{@code element(role, name1, name2, ...)}），
 * 使同一个定位器在任意语言环境下都能命中。
 *
 * <p>典型实现见 {@link ResourceBundleNameProvider}（从 {@code .properties} 资源包加载）。
 * 测试方也可自行实现（例如从后端接口 / JSON 翻译表拉取）。
 *
 * @see AccessibilityTreeExtractor#interactiveElementsAsPageElements(
 *      com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.AccessibilityTreeExtractor.AxNode,
 *      com.hsbc.cmb.hk.dbb.automation.framework.web.page.base.BasePage,
 *      I18nNameProvider)
 */
public interface I18nNameProvider {

    /**
     * 按 i18n key 返回全部语言的可见名（候选）。
     *
     * @param key i18n key，如 {@code "button_next"}；为 {@code null}/空白时返回空数组
     * @return 各语言可见名数组（去重、去空）；无匹配时返回长度 0 的数组（绝不为 {@code null}）
     */
    String[] namesForKey(String key);
}
