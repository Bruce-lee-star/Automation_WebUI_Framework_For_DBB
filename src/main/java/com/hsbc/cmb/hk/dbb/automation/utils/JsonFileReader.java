package com.hsbc.cmb.hk.dbb.automation.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON 文件读取工具
 * 用于从文件系统读取 Mock 数据等 JSON 文件
 * 支持缓存机制，提高性能
 */
public class JsonFileReader {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonFileReader.class);
    
    // 文件内容缓存
    private static final Map<String, String> fileCache = new HashMap<>();
    
    // 是否启用缓存
    private static boolean cacheEnabled = true;
    
    /**
     * 从文件读取 JSON 内容
     * 
     * @param filePath 文件路径（支持相对路径和绝对路径）
     * @return JSON 字符串内容
     */
    public static String readJsonFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("File path is null or empty");
            return null;
        }
        
        // 检查缓存
        if (cacheEnabled && fileCache.containsKey(filePath)) {
            logger.debug("Reading from cache: {}", filePath);
            return fileCache.get(filePath);
        }
        
        try {
            Path path = resolveFileSystemPath(filePath);
            
            // 检查文件是否存在
            if (!Files.exists(path)) {
                logger.error("File not found: {}", path.toAbsolutePath());
                return null;
            }
            
            // 检查是否为文件
            if (!Files.isRegularFile(path)) {
                logger.error("Path is not a regular file: {}", path.toAbsolutePath());
                return null;
            }
            
            // 读取文件内容
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            
            // 验证是否为空
            if (content.trim().isEmpty()) {
                logger.warn("File is empty: {}", filePath);
                return null;
            }
            
            // 验证 JSON 格式（简单检查）
            if (!isValidJsonFormat(content)) {
                logger.warn("File content may not be valid JSON: {}", filePath);
            }
            
            // 存入缓存
            if (cacheEnabled) {
                fileCache.put(filePath, content);
                logger.debug("Cached file content: {}", filePath);
            }
            
            logger.info("Successfully read JSON file: {} ({} bytes)", filePath, content.length());
            return content;
            
        } catch (IOException e) {
            logger.error("Failed to read JSON file: {}", filePath, e);
            return null;
        }
    }
    
    /**
     * 解析文件系统路径（仅用于用户显式提供的路径或项目根路径兼容）。
     * <p>不含 classpath Resources 加载——Resources 由 {@link #readFromClasspath(String)} 负责。
     */
    private static Path resolveFileSystemPath(String filePath) {
        Path path = Paths.get(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        // 相对路径统一基于项目根（user.dir）解析，单一基准，避免散乱猜测
        return Paths.get(System.getProperty("user.dir")).resolve(filePath);
    }
    
    /**
     * 简单的 JSON 格式验证
     */
    private static boolean isValidJsonFormat(String content) {
        String trimmed = content.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
    
    /**
     * 启用缓存
     */
    public static void enableCache() {
        cacheEnabled = true;
        logger.info("File cache enabled");
    }
    
    /**
     * 禁用缓存
     */
    public static void disableCache() {
        cacheEnabled = false;
        logger.info("File cache disabled");
    }
    
    /**
     * 清除缓存
     */
    public static void clearCache() {
        fileCache.clear();
        logger.info("File cache cleared");
    }
    
    /**
     * 清除指定文件的缓存
     */
    public static void clearCache(String filePath) {
        fileCache.remove(filePath);
        logger.debug("Cleared cache for: {}", filePath);
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return fileCache.size();
    }
    
    /**
     * 检查文件是否存在
     */
    public static boolean fileExists(String filePath) {
        try {
            Path path = resolveFileSystemPath(filePath);
            return Files.exists(path) && Files.isRegularFile(path);
        } catch (Exception e) {
            logger.debug("Error checking file existence: {}", filePath, e);
            return false;
        }
    }
    
    /**
     * 读取 JSON 文件并验证内容
     * 
     * @param filePath 文件路径
     * @return JSON 内容，如果文件不存在或格式无效则返回 null
     */
    public static String readAndValidateJsonFile(String filePath) {
        if (!fileExists(filePath)) {
            logger.error("JSON file does not exist: {}", filePath);
            return null;
        }
        
        String content = readJsonFile(filePath);
        
        if (content == null) {
            logger.error("Failed to read JSON file or file is empty: {}", filePath);
            return null;
        }
        
        if (!isValidJsonFormat(content)) {
            logger.error("Invalid JSON format in file: {}", filePath);
            return null;
        }
        
        return content;
    }
    
    /**
     * 读取 Mock 数据文件（专用方法）。
     *
     * <p>加载策略（<b>路径完全由调用方决定，框架不预设任何约定目录</b>）：
     * <ol>
     *   <li><b>classpath</b>——按传入值原样解析。打包后唯一可靠的方式。</li>
     *   <li><b>文件系统</b>——classpath 未命中时，回退到基于项目根（{@code user.dir}）解析。</li>
     * </ol>
     *
     * <p>两种输入形态的差异<b>只有一处</b>：不含路径分隔符时会额外尝试追加 {@code .json}
     * 后缀（便于直接传 {@code "login-response"}）；含路径分隔符时严格按传入路径解析，
     * 不做任何拼接或猜测。
     *
     * <p>因此文件若放在 {@code src/test/resources/mocks/} 下，请显式传入
     * {@code "mocks/login-response.json"}。框架不猜测 {@code mocks/} 这类约定子目录，
     * 否则同一份代码在不同工程结构下行为不一致，且查找失败时难以定位。
     *
     * @param fileName mock 文件路径（相对 classpath 或项目根；含目录时需由调用方显式给出）
     * @return JSON 内容；若均找不到或非合法 JSON 则返回 null
     */
    public static String readMockData(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            logger.error("Mock file name is null or empty");
            return null;
        }

        // 文件名已含路径（用户显式指定目录）：classpath 资源优先，文件系统兼容
        if (fileName.contains("/") || fileName.contains("\\")) {
            String fromResources = readFromClasspath(fileName);
            if (fromResources != null) return fromResources;
            String fromFs = readFromFilesystem(fileName);
            if (fromFs != null) return fromFs;
            logger.error("Mock data file not found: {}", fileName);
            return null;
        }

        // classpath 优先；不含路径时额外尝试 .json 后缀（这是两种输入形态的唯一差异）
        String fromResources = readFromClasspath(fileName);
        if (fromResources != null) return fromResources;
        fromResources = readFromClasspath(fileName + ".json");
        if (fromResources != null) return fromResources;

        // 兼容：项目根路径文件系统（按文件名）
        String fromFs = readFromFilesystem(fileName);
        if (fromFs != null) return fromFs;
        fromFs = readFromFilesystem(fileName + ".json");
        if (fromFs != null) return fromFs;

        logger.error("Mock data file not found: {}. Expected in classpath or project-root (with optional .json).", fileName);
        return null;
    }

    /**
     * 从 classpath Resources 加载资源内容（默认编码 UTF-8）。
     *
     * @return 内容字符串；资源不存在或读取失败返回 null
     */
    private static String readFromClasspath(String resourcePath) {
        try (InputStream in = JsonFileReader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                logger.debug("Classpath resource not found: {}", resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                logger.warn("Classpath resource is empty: {}", resourcePath);
                return null;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (!isValidJsonFormat(content)) {
                logger.warn("Classpath resource may not be valid JSON: {}", resourcePath);
            }
            logger.info("Loaded mock data from classpath: {} ({} bytes)", resourcePath, content.length());
            return content;
        } catch (IOException e) {
            logger.error("Failed to read classpath resource: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * 从文件系统（用户目录 / 项目根路径）加载 JSON 文件。
     *
     * @return 内容字符串；文件不存在或读取失败返回 null
     */
    private static String readFromFilesystem(String filePath) {
        Path path = resolveFileSystemPath(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            logger.debug("File not found on filesystem: {}", path.toAbsolutePath());
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                logger.warn("File is empty: {}", filePath);
                return null;
            }
            if (!isValidJsonFormat(content)) {
                logger.warn("File content may not be valid JSON: {}", filePath);
            }
            logger.info("Loaded mock data from filesystem: {} ({} bytes)", path.toAbsolutePath(), content.length());
            return content;
        } catch (IOException e) {
            logger.error("Failed to read file: {}", filePath, e);
            return null;
        }
    }
}
