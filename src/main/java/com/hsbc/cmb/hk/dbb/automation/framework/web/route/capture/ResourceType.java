package com.hsbc.cmb.hk.dbb.automation.framework.web.route.capture;

import java.util.Locale;

/**
 * API 采集中的资源类型枚举。
 *
 * <p>统一收敛 CDP / Playwright 两套命名体系：
 * <ul>
 *   <li>CDP（{@code Network.requestWillBeSent} 的 {@code request.type}）返回首字母大写，如 {@code XHR}/{@code Fetch}/{@code Document}</li>
 *   <li>Playwright（{@code Request.resourceType()}）返回小写，如 {@code xhr}/{@code fetch}/{@code document}</li>
 * </ul>
 * 本枚举以「标准名（大写）」为唯一标识，并通过 {@link #playwrightName} 保留与 Playwright 的对应关系，
 * {@link #fromString(String)} 可忽略大小写解析两者。
 *
 * <p>{@link #API} 为特殊值：由 MOCK / MODIFY 路由投喂的请求（非浏览器真实发出）统一标记为 {@code API}。
 */
public enum ResourceType {

    XHR("xhr"),
    FETCH("fetch"),
    DOCUMENT("document"),
    SCRIPT("script"),
    STYLESHEET("stylesheet"),
    IMAGE("image"),
    FONT("font"),
    MEDIA("media"),
    WEBSOCKET("websocket"),
    MANIFEST("manifest"),
    OTHER("other"),

    /** 非浏览器真实发出的请求（MOCK / MODIFY 投喂），无对应 Playwright 类型 */
    API("api");

    /** Playwright resourceType() 返回值（小写），用于跨体系归一 */
    private final String playwrightName;

    ResourceType(String playwrightName) {
        this.playwrightName = playwrightName;
    }

    public String playwrightName() {
        return playwrightName;
    }

    /** 是否为 API 类请求（XHR / Fetch / API 投喂） */
    public boolean isApi() {
        return this == XHR || this == FETCH || this == API;
    }

    /**
     * 忽略大小写解析资源类型字符串。接受 CDP（XHR）或 Playwright（xhr）任一写法。
     *
     * @param value 原始字符串（可为 null）
     * @return 匹配到的枚举；无法识别时返回 {@link #OTHER}
     */
    public static ResourceType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return OTHER;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        // Playwright 用小写（xhr/fetch/document），CDP 首字母大写（XHR/Fetch/Document），
        // 统一转大写后枚举名一致（XHR/FETCH/DOCUMENT…）。API 特例也匹配 "API"/"api"。
        for (ResourceType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return OTHER;
    }
}
