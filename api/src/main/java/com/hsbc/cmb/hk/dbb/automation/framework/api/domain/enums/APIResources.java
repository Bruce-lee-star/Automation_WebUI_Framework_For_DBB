package com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.Constants;

public enum APIResources {

    BASE_URI{
        @Override
        public String toString() {
            String baseUri = System.getProperty(Constants.FULL_HOST_URL);
            if (baseUri == null || baseUri.isEmpty()) {
                baseUri = System.getenv(Constants.FULL_HOST_URL);
            }

            if (baseUri == null || baseUri.isEmpty()) {
                baseUri = ConfigProvider.getConfig(ConfigKeys.API_BASE_URI.toString()).getString(ConfigKeys.DEFAULT.toString());
            }
            return baseUri;
        }
    },

    BASE_PATH{
        @Override
        public String toString() {
            final String entity = System.getProperty(Constants.ENTITY.toString());
            if (entity == null || entity.isEmpty()) {
                return ConfigProvider.getConfig(ConfigKeys.API_BASE_PATH.toString()).getString(ConfigKeys.DEFAULT.toString());
            }
            return ConfigProvider.getConfig(ConfigKeys.API_BASE_PATH.toString()).getString(ConfigKeys.DEFAULT.toString());
        }
    }
}
