package com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.impl;

import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.AbstractRestJob;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import net.serenitybdd.rest.SerenityRest;
import io.restassured.http.Headers;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class RestDeleteJob extends AbstractRestJob {

    public static final Logger LOGGER = LoggerFactory.getLogger(RestDeleteJob.class);

    @Override
    public void perform(final Entity entity) {
        final RequestSpecification requestSpecification = SerenityRest.given()
                .baseUri(entity.getBaseUri())
                .basePath(entity.getBasePath())
                .config(AbstractRestJob.getRestAssuredConfig())
                .headers(entity.getRequestHeaders())
                .pathParams(entity.getPathParams())
                .queryParams(entity.getQueryParams())
                .formParams(entity.getFormParams())
                .cookies(entity.getCookies());

        if(StringUtils.isNotBlank(entity.getRequestPayload())){
            requestSpecification.body(entity.getRequestPayload());
        }

        if(StringUtils.isNotBlank(entity.getProxyHost())){
            requestSpecification.proxy(entity.getProxyHost(), entity.getProxyPort(), entity.getProxySchema());
        }

        if(entity.isApiRequestResponseLogsEnabled()){
            requestSpecification.log().all();
        }

        ValidatableResponse response = requestSpecification.when().delete(entity.getEndpoint()).then();

        if(entity.isApiRequestResponseLogsEnabled()){
            response.log().all();
        }

        this.setValidatableResponse(response);
        entity.setResponseCode(response.extract().statusCode());
        entity.setResponseCookies(response.extract().response().cookies());
        entity.setResponsePayload(stripHtmlWrapper(response.extract().response().body().asString()));
        Headers headers = response.extract().response().headers();
        if(headers != null){
            Map<String, String> responseHeader = new HashMap<>();
            headers.forEach(it -> responseHeader.put(it.getName(), it.getValue()));
            entity.setResponseHeaders(responseHeader);
        }
    }
}
