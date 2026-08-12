package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.BaiduPage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class BaiduSteps {

    private static final Logger logger = LoggerFactory.getLogger(BaiduSteps.class);
    private final BaiduPage baiduPage = PageObjectFactory.getPage(BaiduPage.class);

    @Step
    public void openTheBaiduSite() {
        baiduPage.navigateTo("https://www.baidu.com/");
        baiduPage.waitForTimeout(2000);
    }

    @Step
    public void searchKeywords(String keywords) {
        baiduPage.searchInput.fill(keywords);
        baiduPage.searchBtn.click();
        baiduPage.waitForTimeout(3000);
        // 等待搜索结果出现
        baiduPage.resultItems.waitForCount(1, 10);
        logger.info("Search results loaded: {} items found", baiduPage.resultItems.size());
    }

    /**
     * 点击第一个搜索结果，用 page.waitForPopup() 捕获新 tab。
     * <p>waitForPopup 是 Playwright 原生 API，在 click 触发新窗口打开时自动捕获 Popup/Page，
     * 零时序问题，比 context.waitForPage() 更精准。
     */
    @Step
    public void ctrlClickFirstResult() {
        baiduPage.resultItems.waitForCount(1, 10);
        Locator firstLink = baiduPage.getPage().locator("h3.t a").first();
        // waitForPopup 在 click 触发新 tab 时自动返回新 Page 实例
        Page popup = baiduPage.getPage().waitForPopup(firstLink::click);
        logger.info("New tab captured via waitForPopup: {}", popup.url());
        // 直接切到捕获的新 Page，不经过 switchToNewPage 轮询
        baiduPage.switchToPage(popup);
        baiduPage.waitForTimeout(2000);
    }

    /**
     * 注册触发操作并等待新 Tab 打开，基于 Playwright 原生 context.waitForPage()。
     *
     * <pre>{@code
     * baiduSteps.switchToNewPage(() -> baiduPage.someLink.click());
     * }</pre>
     */
    @Step
    public void switchToNewPage(Runnable trigger) {
        baiduPage.waitForNewPage(trigger, 15);
        logger.info("Switched to new page: {}", baiduPage.getPage().url());
        baiduPage.waitForTimeout(2000);
    }

    /**
     * 仅等待新 Tab（前序步骤已触发打开操作）。
     * <p>先检查是否已有新页面（快速路径），若无则注册 Playwright 事件监听等待。
     */
    @Step
    public void switchToNewPage() {
        baiduPage.waitForNewPage(15);
        logger.info("Switched to new page: {}", baiduPage.getPage().url());
        baiduPage.waitForTimeout(2000);
    }

    @Step
    public void waitForSomeTime() {
        baiduPage.waitForTimeout(3000);
        if(baiduPage.getPageSource().contains("Playwright")){
            assertThat(baiduPage.pageTitle.isVisible(), equalTo(true));
        }else{
            assertThat(baiduPage.seleniumTitle.getText(), containsString("Selenium"));
        }
    }
}
