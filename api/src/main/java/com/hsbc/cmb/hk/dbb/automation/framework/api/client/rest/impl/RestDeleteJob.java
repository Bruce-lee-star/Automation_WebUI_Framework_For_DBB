package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.AbstractRestJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DELETE 请求实现。
 *
 * <p>⭐ 修复 P2-24：公共流程已上提至
 * {@link AbstractRestJob#execute(Entity, java.util.function.Function)}，
 * 本类只保留 HTTP 方法这一唯一差异点，详细说明见 {@link RestGetJob}。
 */
public class RestDeleteJob extends AbstractRestJob {

    public static final Logger LOGGER = LoggerFactory.getLogger(RestDeleteJob.class);

    @Override
    public void perform(final Entity entity) {
        execute(entity, spec -> spec.when().delete(entity.getEndpoint()).then());
    }
}
