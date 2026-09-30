package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

/**
 * SessionManager —— 兼容门面（2026-09-27 重构）。
 *
 * <p><b>本类不再承载任何会话逻辑</b>：所有实现已下沉到新内核 {@link SessionStore}。
 * 本类仅保留对外<b>公开 / 包级 / 私有方法签名</b>（含反射测试依赖的
 * {@code acquireOrAwait} / {@code completeLoginGuard}），逐一对 {@link SessionStore} 透明委托，
 * 使 84+ 调用点与既有单测（反射/包级访问）零改动继续编译。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>sessionKey 由业务层传入，<b>不含 browserType</b>；</li>
 *   <li>有效期判定（cookie 过期为主、本地年龄兜底、可选探针）与缓存/持久化/单飞/会话复用
 *       全部在 {@link SessionStore} 内实现；</li>
 *   <li>{@code prepareSession(sessionKey)} 作为 {@code restoreSession(sessionKey)} 的兼容别名，
 *       供业务侧 {@code LoginSteps} 旧调用继续工作。</li>
 * </ul>
 *
 * @see SessionStore 新内核（有效期/cache/单飞/持久化/会话复用）
 */
public final class SessionManager {

    private SessionManager() {
    }

    // ===================== 统计 =====================
    public static long getSingleFlightTakeoverCount() {
        return SessionStore.getSingleFlightTakeoverCount();
    }

    // ===================== homeUrl =====================
    public static String getHomeUrl(String sessionKey) {
        return SessionStore.getHomeUrl(sessionKey);
    }

    /** 取本线程最近一次承载登录态的会话 homeUrl（meta 来源）；无则返回 {@code null}。 */
    public static String getHomeUrl() {
        return SessionStore.getHomeUrl();
    }

    public static void releaseSessionGate() {
        SessionStore.releaseSessionGate();
    }

    public static void resetCurrentSession() {
        SessionStore.resetCurrentSession();
    }

    public static void resetAllForTest() {
        SessionStore.resetAllForTest();
    }

    // ===================== 入口 =====================
    /**
     * 兼容别名：等价于 {@link #restoreSession(String)}。
     * 业务侧 {@code LoginSteps} 仍调用 {@code prepareSession(sessionKey)}，保持该入口可用。
     */
    public static boolean prepareSession(String sessionKey) {
        return SessionStore.restoreSession(sessionKey);
    }

    public static boolean restoreSession(String sessionKey) {
        return SessionStore.restoreSession(sessionKey);
    }

    public static boolean restoreSession(String sessionKey, Runnable leaderAction) {
        return SessionStore.restoreSession(sessionKey, leaderAction);
    }

    public static void saveSession(String sessionKey, String homeUrl) {
        SessionStore.saveSession(sessionKey, homeUrl);
    }

    public static boolean clearSession(String sessionKey) {
        return SessionStore.clearSession(sessionKey);
    }

    public static int clearAllSessions() {
        return SessionStore.clearAllSessions();
    }

    public static String loadHomeUrl(String sessionKey) {
        return SessionStore.loadHomeUrl(sessionKey);
    }

    // ===================== 包级 / 私有（单测 / 反射） =====================
    static boolean isValidStorageStateJson(String json) {
        return SessionStore.isValidStorageStateJson(json);
    }

    static void purgeSessionFiles(String sessionKey) {
        SessionStore.purgeSessionFiles(sessionKey);
    }

    static void acquireSessionGate(String sessionKey) {
        SessionStore.acquireSessionGate(sessionKey);
    }

    /**
     * 单飞协调入口（反射测试依赖，保持私有 + 签名不变）。
     * 返回 {@code null} 表示本线程是 leader；非 null 表示 follower 已等到 leader 结束。
     */
    private static Object acquireOrAwait(String sessionKey) {
        return SessionStore.acquireOrAwait(sessionKey);
    }

    /**
     * 释放单飞守卫（反射测试依赖，保持私有 + 签名不变）。
     */
    private static void completeLoginGuard(String sessionKey, boolean success) {
        SessionStore.completeLoginGuard(sessionKey, success);
    }
}
