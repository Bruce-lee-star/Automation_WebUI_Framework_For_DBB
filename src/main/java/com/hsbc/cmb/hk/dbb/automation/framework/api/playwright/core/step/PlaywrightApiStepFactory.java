package com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.step;

import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.client.ApiContextScope;
import com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.entity.ApiRequestEntity;

/**
 * PlaywrightApiStepFactory - 创建 {@link PlaywrightApiStep} 的工厂
 * <p>
 * 仅由 {@link com.hsbc.cmb.hk.dbb.automation.framework.api.playwright.core.services.PlaywrightApiTestServices} 使用，
 * 保证步骤实例只能通过服务入口创建。
 */
public final class PlaywrightApiStepFactory {

    private PlaywrightApiStepFactory() {
    }

    public static PlaywrightApiStep createWithNullEntity() {
        return new PlaywrightApiStep(new ApiRequestEntity(), ApiContextScope.current());
    }

    public static PlaywrightApiStep createWithEntity(ApiRequestEntity entity, ApiContextScope scope) {
        return new PlaywrightApiStep(entity, scope);
    }
}
