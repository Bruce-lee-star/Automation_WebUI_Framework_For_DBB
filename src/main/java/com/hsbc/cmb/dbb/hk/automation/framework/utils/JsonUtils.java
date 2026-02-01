package com.hsbc.cmb.dbb.hk.automation.framework.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON工具类
 */
public class JsonUtils {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 将对象转换为JSON字符串
     */
    public static String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert to JSON", e);
        }
    }
    
    /**
     * 将JSON字符串转换为对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }
    
    /**
     * 将JSON字符串转换为Map
     */
    public static Map<String, Object> fromJson(String json) {
        return fromJson(json, Map.class);
    }
    
    /**
     * 将Map转换为JSON字符串
     */
    public static String toJson(Map<String, Object> map) {
        return toJson((Object) map);
    }
}