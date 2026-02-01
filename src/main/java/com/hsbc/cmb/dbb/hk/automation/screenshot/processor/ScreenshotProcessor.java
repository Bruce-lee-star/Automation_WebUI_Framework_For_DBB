package com.hsbc.cmb.dbb.hk.automation.screenshot.processor;

import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfig;
import com.hsbc.cmb.dbb.hk.automation.framework.config.FrameworkConfigManager;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestStep;
import net.thucydides.model.screenshots.ScreenshotAndHtmlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.hsbc.cmb.dbb.hk.automation.framework.exceptions.ScreenshotException;

/**
 * 处理截图到报告的转换
 * 将截图文件关联到测试步骤并生成HTML报告片段
 */
public class ScreenshotProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(ScreenshotProcessor.class);
    private final File reportScreenshotDirectory;
    
    public ScreenshotProcessor() {
        this.reportScreenshotDirectory = getReportScreenshotDirectory();
        ensureDirectoryExists(reportScreenshotDirectory);
    }
    
    private File getReportScreenshotDirectory() {
        String dirPath = FrameworkConfigManager.getString(FrameworkConfig.SERENITY_REPORTS_SCREENSHOTS_DIRECTORY);
        return new File(dirPath);
    }
    
    private void ensureDirectoryExists(File directory) {
        if (!directory.exists()) {
            try {
                Files.createDirectories(directory.toPath());
                logger.debug("Created report screenshot directory: {}", directory.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to create report screenshot directory: {}", directory.getAbsolutePath(), e);
                throw new ScreenshotException("Could not create report screenshot directory: " + directory.getAbsolutePath(), e);
            }
        }
    }
    
    /**
     * 处理测试结果中的所有截图
     */
    public void processScreenshotsFor(TestOutcome testOutcome) {
        if (testOutcome == null || testOutcome.getTestSteps() == null) {
            return;
        }
        
        for (TestStep step : testOutcome.getTestSteps()) {
            processScreenshotsForStep(step);
        }
    }
    
    /**
     * 处理单个测试步骤中的所有截图
     */
    public void processScreenshotsForStep(TestStep step) {
        if (step == null || step.getScreenshots() == null) {
            return;
        }
        
        List<ScreenshotAndHtmlSource> screenshots = step.getScreenshots();
        for (ScreenshotAndHtmlSource screenshot : screenshots) {
            processScreenshot(step, screenshot);
        }
    }
    
    /**
     * 处理单个截图，确保它在报告中正确显示
     */
    private void processScreenshot(TestStep step, ScreenshotAndHtmlSource screenshot) {
        try {
            // 确保截图文件存在
            if (screenshot.getScreenshot() != null && screenshot.getScreenshot().exists()) {
                // 复制到报告目录（如果需要）
                File reportScreenshot = copyToReportDirectory(screenshot.getScreenshot());
                
                // 在步骤数据中记录截图路径
                // 注意：由于Serenity 3.9.8的API差异，我们可能需要通过其他方式设置
                logger.debug("Processed screenshot for step '{}': {}", 
                           step.getDescription(), reportScreenshot.getName());
                
                // 生成HTML代码片段
                String htmlFragment = generateHtmlFragment(reportScreenshot, step);
                logger.debug("Generated HTML fragment: {}", htmlFragment);
            } else {
                logger.warn("Screenshot file not found: {}", screenshot.getScreenshot());
            }
        } catch (IOException e) {
            logger.error("Failed to process screenshot for step '{}'", step.getDescription(), e);
        }
    }
    
    /**
     * 将截图复制到报告目录
     */
    private File copyToReportDirectory(File screenshot) throws IOException {
        Path sourcePath = screenshot.toPath();
        Path destPath = Paths.get(reportScreenshotDirectory.getAbsolutePath(), screenshot.getName());
        
        // 如果文件已经存在于报告目录中，直接返回
        if (sourcePath.equals(destPath)) {
            return screenshot;
        }
        
        // 复制文件
        Files.copy(sourcePath, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return destPath.toFile();
    }
    
    /**
     * 生成HTML代码片段，用于在报告中显示截图
     */
    private String generateHtmlFragment(File screenshot, TestStep step) {
        return String.format(
            "<div class='screenshot'>" +
            "<a href='%s' data-featherlight='%s'>" +
            "<img src='%s' width='200'/>" +
            "</a></div>",
            screenshot.getName(),
            screenshot.getName(),
            screenshot.getName()
        );
    }
    
    /**
     * 处理单个截图并返回HTML片段
     */
    public String processSingleScreenshot(ScreenshotAndHtmlSource screenshot, String stepName) {
        try {
            if (screenshot.getScreenshot() != null && screenshot.getScreenshot().exists()) {
                File reportScreenshot = copyToReportDirectory(screenshot.getScreenshot());
                return generateHtmlFragment(reportScreenshot, stepName);
            } else {
                logger.warn("Screenshot file not found: {}", screenshot.getScreenshot());
                return "<div class='screenshot'>Screenshot not available</div>";
            }
        } catch (IOException e) {
            logger.error("Failed to process screenshot for step '{}'", stepName, e);
            return "<div class='screenshot'>Screenshot processing failed</div>";
        }
    }
    
    /**
     * 生成HTML代码片段，用于在报告中显示截图
     */
    private String generateHtmlFragment(File screenshot, String stepName) {
        return String.format(
            "<div class='screenshot'>" +
            "<a href='%s' data-featherlight='%s'>" +
            "<img src='%s' width='200' title='%s'/>" +
            "</a></div>",
            screenshot.getName(),
            screenshot.getName(),
            screenshot.getName(),
            stepName
        );
    }
}