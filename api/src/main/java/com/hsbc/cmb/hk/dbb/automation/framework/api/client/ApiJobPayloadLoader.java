package com.hsbc.cmb.hk.dbb.automation.framework.api.client;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.ConfigProvider;
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 请求负载职责：承载 {@link AbstractApiJobHelper} 的负载加载与字段修改方法（loadPayload / modifyFieldsInRequestPayload）。
 * <p>Phase 5 / T5-6 保行为拆分产物——逻辑与原类逐字等价，仅通过 {@code owner} 访问 Entity，
 * 日志前缀沿用 {@link AbstractApiJobHelper#LOGGER}。</p>
 */
final class ApiJobPayloadLoader {

    private final ApiJob owner;

    ApiJobPayloadLoader(ApiJob owner) {
        this.owner = owner;
    }

    public void loadPayload(final String fileName) {
        // 1. First layer validation: File name not null/empty
        if (fileName == null || fileName.trim().isEmpty()) {
            AbstractApiJobHelper.LOGGER.error("Payload file name is null or empty.", new IllegalArgumentException("File name cannot be null/empty"));
            return;
        }
        String cleanFileName = fileName.trim();

        // 2. Second layer validation: Entity not null (core fix for NPE)
        Entity entity = owner.getEntity();
        if (entity == null) {
            AbstractApiJobHelper.LOGGER.error("Entity object is NULL! Cannot set payload to a null Entity instance.");
            return;
        }

        // 3. Third layer validation: Get and validate payload path
        String payloadPath = ConfigProvider.getPayloadPath(cleanFileName);
        if (payloadPath == null || payloadPath.trim().isEmpty()) {
            AbstractApiJobHelper.LOGGER.error("Payload file path is empty for file: [{}]", cleanFileName);
            return;
        }
        // Standardize path (eliminate redundant .\ characters completely)
        String normalizedPath = null;
        try {
            normalizedPath = new File(payloadPath).getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get canonical path for payload file: " + payloadPath, e);
        }
        File payloadFile = new File(normalizedPath);

        // 4. Fourth layer validation: File exists and is a regular file
        if (!payloadFile.exists()) {
            AbstractApiJobHelper.LOGGER.error("Payload file NOT found: [{}]", normalizedPath);
            return;
        }
        if (!payloadFile.isFile()) {
            AbstractApiJobHelper.LOGGER.error("Specified path is a directory, not a file: [{}]", normalizedPath);
            return;
        }

        // 5. Safely read file content (catch all IO exceptions)
        try {
            // Read using Java NIO (avoid unclosed streams) with configured encoding
            String encoding = FrameworkConfig.getPayloadEncoding();
            String content = new String(
                    Files.readAllBytes(payloadFile.toPath()),
                    Charset.forName(encoding)
            );
            // 6. Fifth layer validation: File content not empty
            if (content == null || content.trim().isEmpty()) {
                AbstractApiJobHelper.LOGGER.warn("Payload file [{}] is empty (path: {})", cleanFileName, normalizedPath);
                entity.setRequestPayload(""); // Prevent subsequent NPE
                return;
            }

            // 7. Parse JSON and set to Entity (final step)
            DocumentContext requestPayload = JsonPath.parse(content);
            entity.setRequestPayload(requestPayload.jsonString());
            AbstractApiJobHelper.LOGGER.info("loaded payload file successfully: [{}]\n  {}",
                    cleanFileName, content);

        } catch (IOException e) {
            String errorMsg = String.format("IO error reading payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            AbstractApiJobHelper.LOGGER.error(errorMsg, e);
            // 抛出RuntimeException，携带自定义消息和原始异常
            throw new RuntimeException(errorMsg, e);

        } catch (InvalidJsonException e) {
            String errorMsg = String.format("Invalid JSON format in payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            AbstractApiJobHelper.LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);

        } catch (JsonPathException e) {
            String errorMsg = String.format("JSONPath parse error for payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            AbstractApiJobHelper.LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);

        } catch (Exception e) {
            String errorMsg = String.format("Unexpected error loading payload file: [%s] (path: %s)", cleanFileName, normalizedPath);
            AbstractApiJobHelper.LOGGER.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Modify single field in request payload (support any path format + array index)
     */
    public void modifyFieldsInRequestPayload(String fieldPath, Object fieldValue) {
        try {
            String originalJson = owner.getEntity().getRequestPayload();

            // 1. 解析为 DocumentContext（核心：使用 Jackson 解析器）
            DocumentContext doc = JsonPath.using(Configuration.builder()
                            .jsonProvider(new JacksonJsonProvider()) // 强制用 Jackson 解析器
                            .mappingProvider(new JacksonMappingProvider())
                            .build())
                    .parse(originalJson);

            // 2. 直接修改（无需 TypeRef）
            doc.set(fieldPath, fieldValue); // 支持任意路径：name、user.info.id、user.addresses[0].city

            // 3. 获取修改后的 JSON
            String modifiedJson = doc.jsonString();
            owner.getEntity().setRequestPayload(modifiedJson);
        } catch (InvalidPathException e) {
            // 路径格式非法（如 "user.info[abc].city" 索引非数字）
            String errorMsg = String.format("Invalid field path format: [%s]", fieldPath);
            AbstractApiJobHelper.LOGGER.error("[Path Format Error] {}", errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        } catch (Exception e) {
            // 兜底异常（捕获所有未预期的错误）
            String errorMsg = String.format("Unexpected error when modifying field [%s]", fieldPath);
            AbstractApiJobHelper.LOGGER.error("[Unexpected Error] {}", errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }
}
