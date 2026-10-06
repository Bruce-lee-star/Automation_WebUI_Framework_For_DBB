package com.hsbc.cmb.hk.dbb.automation.framework.api.client;


import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.AbstractRestJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiJob.class);

    private Entity entity;

    private AbstractRestJob restJob = null;


    public Entity getEntity() {
        return entity;
    }

    /**
     * Set the entity for this API job
     * Entity can be null for dynamic configuration mode
     *
     * @param entity the entity to set (can be null)
     */
    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    public AbstractRestJob getRestJob() {
        return restJob;
    }

    public void setRestJob(AbstractRestJob restJob) {
        this.restJob = restJob;
    }
}
