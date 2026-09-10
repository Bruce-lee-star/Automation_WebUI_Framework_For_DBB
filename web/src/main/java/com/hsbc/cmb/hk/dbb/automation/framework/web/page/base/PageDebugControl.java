package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 页面调试控制（企业级封装单元，WEB-P1-2 Phase 6 收口）。
 *
 * <p>承载 BasePage 的「本地可调试环境识别」与「安全 {@code pause()} 控制」逻辑，
 * 避免调试能力散落在门面类中。原 BasePage 相关代码逐字迁移，行为零变更。
 *
 * <h3>Java 三大特性取舍（呼应 WEB-P1-6 复盘）</h3>
 * <ul>
 *   <li><b>封装 ✅</b>：调试环境判定与 pause 副作用收敛到本单元，日志统一路由回
 *       {@link BasePage#logger} 以保持生产溯源一致。</li>
 *   <li><b>继承 ❌</b>：本类为 {@code final}，不存在可替换子类层次（组合优于继承）。</li>
 *   <li><b>多态 🔶</b>：纯静态工具式封装，单一实现，无多实现需求，不强行引入接口。</li>
 * </ul>
 *
 * @apiNote Framework-internal — 由 BasePage 委派调用，业务代码请勿直接依赖。
 */
final class PageDebugControl {

    private static final Logger logger = LoggerFactory.getLogger(BasePage.class);

    private PageDebugControl() {
        // 纯静态工具单元，禁止实例化
    }

    /**
     * 判断当前是否为可调试本地环境。
     * @return true=本地允许 pause；false=Jenkins/BrowserStack 禁止暂停
     */
    static boolean isDebugEnvironment() {
        // 1. 识别 BrowserStack 云端环境
        boolean isBsEnv = System.getenv().containsKey("BROWSERSTACK_USERNAME")
                || System.getenv().containsKey("BROWSERSTACK_ACCESS_KEY");

        // 2. 识别 Jenkins CI 环境
        boolean isJenkinsEnv = System.getenv().containsKey("JENKINS_HOME")
                || System.getProperty("ci", "false").equalsIgnoreCase("true");

        // 云端/CI 直接判定为非调试环境
        return !isBsEnv && !isJenkinsEnv;
    }

    /**
     * 安全暂停方法：本地 IDE 正常 pause 调试；Jenkins / BrowserStack 自动跳过，杜绝流程阻塞。
     * <p>当且仅当为可调试本地环境时才访问页面 {@code pause()}，避免在非调试环境触发页面初始化副作用。
     *
     * @param bp 当前页面对象（用于获取 Playwright Page）
     */
    static void pause(BasePage bp) {
        if (isDebugEnvironment()) {
            try {
                bp.getPage().pause();
            } catch (Exception e) {
                logger.warn("Page pause failed, skip debug pause", e);
            }
        } else {
            logger.warn("[Security Control] Jenkins/BrowserStack environment, auto skip pause() to avoid block");
        }
    }
}
