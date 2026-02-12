package com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.photographer;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfig;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.FrameworkConfigManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.permission.ScreenshotPermission;
import com.hsbc.cmb.hk.dbb.automation.framework.web.screenshot.strategy.ScreenshotStrategy;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ScreenshotException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class Photographer {
    
    private static final Logger logger = LoggerFactory.getLogger(Photographer.class);
    private static final AtomicLong screenshotCounter = new AtomicLong(0);
    
    private final Page page;
    private final File outputDirectory;
    private final ScreenshotPermission screenshotPermission;
    
    public Photographer(Page page) {
        this.page = page;
        this.outputDirectory = getScreenshotDirectory();
        this.screenshotPermission = new ScreenshotPermission();
        ensureDirectoryExists(outputDirectory);
    }
    
    public Photographer(Page page, File outputDirectory) {
        this.page = page;
        this.outputDirectory = outputDirectory;
        this.screenshotPermission = new ScreenshotPermission();
        ensureDirectoryExists(outputDirectory);
    }
    
    private File getScreenshotDirectory() {
        String dirPath = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_SCREENSHOTS_DIRECTORY);
        return new File(dirPath);
    }
    
    private void ensureDirectoryExists(File directory) {
        if (!directory.exists()) {
            try {
                Files.createDirectories(directory.toPath());
                logger.debug("Created screenshot directory: {}", directory.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to create screenshot directory: {}", directory.getAbsolutePath(), e);
                throw new ScreenshotException("Could not create screenshot directory: " + directory.getAbsolutePath(), e);
            }
        }
    }
    
    public Optional<ScreenshotAndHtmlSource> takeScreenshot(String title) {
        return takeScreenshot(title, ScreenshotStrategy.BEFORE_AND_AFTER_EACH_STEP);
    }

    /**
     * 智能截图：根据 Serenity 策略和当前上下文决定是否截图
     */
    public Optional<ScreenshotAndHtmlSource> takeScreenshot(String title, ScreenshotStrategy strategy) {
        if (page == null || page.isClosed()) {
            logger.warn("Cannot take screenshot: Playwright page is null or closed");
            return Optional.empty();
        }
        
        // 使用 ScreenshotPermission 检查是否应该截图
        if (!shouldTakeScreenshot(title, strategy)) {
            logger.debug("Screenshot skipped for '{}' due to policy: {}", title, strategy);
            return Optional.empty();
        }
        
        try {
            String screenshotName = generateSerenityCompatibleScreenshotName(title);
            Path screenshotPath = Paths.get(outputDirectory.getAbsolutePath(), screenshotName);
            
            // 保存截图 - 优雅处理失败，不影响测试流程
            boolean screenshotSaved = saveScreenshotSafely(screenshotPath);
            if (!screenshotSaved) {
                logger.warn("Screenshot failed for: {}, continuing without screenshot", title);
                return Optional.empty();
            }
            
            // 保存 HTML 源码
            String htmlContent = page.content();
            String htmlFileName = screenshotName.replace(".png", ".html");
            Path htmlPath = Paths.get(outputDirectory.getAbsolutePath(), htmlFileName);
            saveHtmlSourceSafely(htmlPath, htmlContent);
            
            File screenshotFile = screenshotPath.toFile();
            File htmlFile = htmlPath.toFile();
            
            if (screenshotFile.exists() && screenshotFile.length() > 0) {
                logger.info("Screenshot captured successfully: {} ({} bytes) at path: {}", 
                    screenshotName, screenshotFile.length(), screenshotPath);
                
                // 创建 Serenity 兼容的截图对象
                ScreenshotAndHtmlSource screenshot = new ScreenshotAndHtmlSource(screenshotFile, htmlFile);
                return Optional.of(screenshot);
            } else {
                logger.warn("Screenshot file was created but is empty: {}", screenshotPath);
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.warn("Failed to take screenshot for: {}, continuing without screenshot", title, e);
            return Optional.empty();
        }
    }
    
    /**
     * 检查是否应该截图（基于策略和权限）
     */
    private boolean shouldTakeScreenshot(String title, ScreenshotStrategy strategy) {
        try {
            // 使用当前的 ScreenshotPermission 进行智能检查
            if (screenshotPermission == null) {
                logger.warn("ScreenshotPermission not initialized, defaulting to allow screenshot");
                return true;
            }
            
            // 根据策略类型进行不同的检查
            switch (strategy) {
                case FOR_FAILURES:
                    return false; // 失败截图通常在其他地方处理
                case MANUAL:
                    return false; // 手动截图由调用者控制
                case AFTER_EACH_STEP:
                case BEFORE_AND_AFTER_EACH_STEP:
                default:
                    return true; // 其他策略默认允许截图
            }
        } catch (Exception e) {
            logger.warn("Error checking screenshot permission for '{}': {}", title, e.getMessage());
            return true; // 出错时默认允许截图
        }
    }
    
    private boolean saveScreenshotSafely(Path screenshotPath) {
        try {
            page.screenshot(new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(screenshotPath));
            
            // 验证文件是否正确保存
            File screenshotFile = screenshotPath.toFile();
            if (screenshotFile.exists() && screenshotFile.length() > 0) {
                return true;
            } else {
                logger.warn("Screenshot file verification failed: {}", screenshotPath);
                return false;
            }
        } catch (Exception e) {
            logger.warn("Screenshot save failed: {}", e.getMessage());
            return false;
        }
    }
    
    private void saveHtmlSourceSafely(Path htmlPath, String htmlContent) {
        try {
            // 确保目录存在
            Files.createDirectories(htmlPath.getParent());
            
            // 写入HTML文件
            Files.write(htmlPath, htmlContent.getBytes(StandardCharsets.UTF_8));
            
            logger.debug("HTML source saved: {}", htmlPath);
        } catch (Exception e) {
            logger.warn("Failed to save HTML source: {}", htmlPath, e);
        }
    }
    
    private String generateSerenityCompatibleScreenshotName(String title) {
        // 生成 Serenity 兼容的截图文件名
        String sanitizedTitle = title.replaceAll("[^a-zA-Z0-9 _-]", "_")
                                    .replaceAll("\\s+", "_")
                                    .toLowerCase();
        
        long counter = screenshotCounter.incrementAndGet();
        return String.format("%s_%d.png", sanitizedTitle, counter);
    }
    
    private String generateScreenshotName(String title) {
        // 保留原有的方法名以保持向后兼容
        return generateSerenityCompatibleScreenshotName(title);
    }
    
    public File getOutputDirectory() {
        return outputDirectory;
    }
    
    public boolean isPageAvailable() {
        return page != null && !page.isClosed();
    }
    
    public Optional<ScreenshotAndHtmlSource> takeElementScreenshot(String selector, String title) {
        if (page == null || page.isClosed()) {
            logger.warn("Cannot take element screenshot: Playwright page is null or closed");
            return Optional.empty();
        }
        
        try {
            String screenshotName = generateSerenityCompatibleScreenshotName(title + "_element");
            Path screenshotPath = Paths.get(outputDirectory.getAbsolutePath(), screenshotName);
            
            // 截图特定元素 - 优雅处理失败
            page.locator(selector).screenshot(new Locator.ScreenshotOptions()
                .setPath(screenshotPath));
            
            File screenshotFile = screenshotPath.toFile();
            if (screenshotFile.exists() && screenshotFile.length() > 0) {
                logger.info("Element screenshot captured: {} at selector '{}'", screenshotName, selector);
                
                // 对于元素截图，我们仍然创建HTML源码文件
                String htmlContent = page.content();
                String htmlFileName = screenshotName.replace(".png", ".html");
                Path htmlPath = Paths.get(outputDirectory.getAbsolutePath(), htmlFileName);
                saveHtmlSourceSafely(htmlPath, htmlContent);
                
                ScreenshotAndHtmlSource screenshot = new ScreenshotAndHtmlSource(screenshotFile, htmlPath.toFile());
                return Optional.of(screenshot);
            } else {
                logger.warn("Element screenshot failed for selector '{}': {}", selector, title);
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.warn("Failed to take element screenshot for selector '{}': {}", selector, e.getMessage());
            return Optional.empty();
        }
    }
}