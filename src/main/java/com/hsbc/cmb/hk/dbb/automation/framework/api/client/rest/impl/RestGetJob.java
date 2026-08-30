package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.AbstractRestJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET 请求实现。
 *
 * <p>⭐ 修复 P2-24：本类原先与 RestPostJob / RestPutJob / RestPatchJob / RestDeleteJob
 * <b>逐字重复</b>约 40 行（构建请求规格、body、代理、请求/响应日志、响应回写），
 * 唯一差异只是调用哪个 HTTP 方法。现仅保留该差异点，公共流程统一上提至
 * {@link AbstractRestJob#execute(Entity, java.util.function.Function)}。
 */
public class RestGetJob extends AbstractRestJob {

    public static final Logger LOGGER = LoggerFactory.getLogger(RestGetJob.class);

    @Override
    public void perform(final Entity entity) {
        execute(entity, spec -> spec.when().get(entity.getEndpoint()).then());
    }
}
