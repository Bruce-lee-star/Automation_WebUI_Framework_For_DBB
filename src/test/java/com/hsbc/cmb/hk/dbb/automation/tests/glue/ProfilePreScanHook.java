package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profile 预扫描 Hook — 在 Scenario 执行前预提取 switch profile 目标。
 *
 * <h3>解决的问题</h3>
 * 登录后 profile 切换的"多次切换"问题，支持 Background + Scenario 级联：
 * <pre>
 * 之前：LoginSteps 切到配置 profile → Background 步再切 → Scenario 步再切 = N 次
 * 现在：Hook 扫 Background 作为初始 target → LoginSteps 不切
 *       → Background 步发现已在目标，跳过
 *       → Scenario 步只需一次切换
 * </pre>
 *
 * <h3>优先级机制</h3>
 * <ol>
 *   <li><b>Background 的 profile</b> — 最高（Background 在登录后立即执行）</li>
 *   <li><b>Scenario 的 profile</b> — 次之（Background 无 profile 时使用）</li>
 *   <li><b>配置文件的 profile</b> — 兜底（两者都无时，由 LoginSteps 处理）</li>
 * </ol>
 * <p>
 * <b>注意：</b>Hook 只设置"初始"targetProfile。Scenario 级别的 profile
 * 由 HomeSteps.switchProfileToAndCloseReminder() 在运行时覆盖。
 *
 *
 * <h3>工作机制</h3>
 * <ol>
 *   <li>{@code @Before(order=0)} 确保在 LogonGlue 之前运行</li>
 *   <li>读取当前 scenario 所属的 .feature 文件</li>
 *   <li>先提取 Background 文本块，正则匹配 "switch profile to \"...\""</li>
 *   <li>再提取 Scenario 文本块，正则匹配 "switch profile to \"...\""</li>
 *   <li>优先级 Background &gt; Scenario &gt; config，注入 targetProfile</li>
 *   <li>后续 LoginSteps / HomeSteps 直接使用已设置的目标值</li>
 * </ol>
 *
 * @author Automation Framework
 */
public class ProfilePreScanHook {

    private static final Logger logger = LoggerFactory.getLogger(ProfilePreScanHook.class);

    /**
     * 匹配 When 步骤中的 switch profile to "PROFILE_NAME"
     * 支持：空字符串 ""（显式不指定 profile）、非空字符串
     */
    private static final Pattern SWITCH_PROFILE_PATTERN =
            Pattern.compile("switch profile to \"([^\"]*)\"");

    /**
     * 定位 Background 块起始
     */
    private static final Pattern BACKGROUND_HEADER =
            Pattern.compile("(?:^|\\n)\\s*Background:\\s*\\n");

    @Before(order = 0)
    public void preScanTargetProfile(Scenario scenario) {
        try {
            String featureUri = scenario.getUri().toString();
            Path featurePath = Paths.get(java.net.URI.create(featureUri));

            if (!Files.exists(featurePath)) {
                logger.warn("[PreScan] Feature file not found: {}", featurePath);
                return;
            }

            String featureContent = Files.readString(featurePath);

            // ── 1. 提取 Background 中的 profile（优先级最高） ──
            String backgroundProfile = extractBackgroundProfile(featureContent);

            // ── 2. 提取当前 Scenario 中的 profile（Background 无 profile 时的 fallback） ──
            String scenarioProfile = null;
            String scenarioBlock = extractScenarioBlock(featureContent, scenario.getName());
            if (scenarioBlock != null) {
                scenarioProfile = extractProfileFromBlock(scenarioBlock);
            }

            // ── 3. 按优先级选择有效 profile ──
            String effectiveProfile = null;
            String source = null;

            if (backgroundProfile != null && !backgroundProfile.isEmpty()) {
                effectiveProfile = backgroundProfile;
                source = "Background";
            } else if (scenarioProfile != null && !scenarioProfile.isEmpty()) {
                effectiveProfile = scenarioProfile;
                source = "Scenario";
            }

            if (effectiveProfile != null) {
                logger.info("[PreScan] ✓ {} profile '{}' detected for scenario '{}'",
                        source, effectiveProfile, scenario.getName());
            } else {
                logger.debug("[PreScan] No profile in Background or Scenario");
            }

        } catch (Exception e) {
            // Feature 文件读取失败不要阻断测试执行
            logger.warn("[PreScan] Failed to pre-scan profile: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Feature 文本解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 Background 文本块中提取 switch profile 目标。
     *
     * @param featureContent 完整的 .feature 文件内容
     * @return profile 名称，未找到或为空字符串时返回 null
     */
    static String extractBackgroundProfile(String featureContent) {
        Matcher bgMatcher = BACKGROUND_HEADER.matcher(featureContent);
        if (!bgMatcher.find()) {
            return null;
        }

        int bgContentStart = bgMatcher.end();

        // Background 结束于下一个 Scenario / Rule / Examples 或文件末尾
        Pattern nextBoundary = Pattern.compile(
                "\\n\\s*(?:@\\w+\\s*\\n\\s*)?(?:Scenario(?:\\s+Outline)?:|Rule:|Examples:)\\s*");
        Matcher boundaryMatcher = nextBoundary.matcher(featureContent);
        int bgEnd = featureContent.length();
        if (boundaryMatcher.find(bgContentStart)) {
            bgEnd = boundaryMatcher.start();
        }

        String backgroundBlock = featureContent.substring(bgContentStart, bgEnd);
        return extractProfileFromBlock(backgroundBlock);
    }

    /**
     * 从文本块中提取 switch profile 目标（去掉空字符串）。
     *
     * @param block 文本块（Background 或 Scenario）
     * @return 非空 profile，未找到或为空字符串时返回 null
     */
    static String extractProfileFromBlock(String block) {
        Matcher m = SWITCH_PROFILE_PATTERN.matcher(block);
        if (m.find()) {
            String profile = m.group(1);
            return (profile != null && !profile.isEmpty()) ? profile : null;
        }
        return null;
    }

    /**
     * 从 feature 文件内容中提取指定 scenario 的文本块。
     * 通过 Scenario 名称匹配，然后截取到下一个 Scenario（或文件末尾）之间的内容。
     *
     * @param featureContent 完整的 .feature 文件内容
     * @param scenarioName   Scenario 名称（如 "testing logon to dbb-1"）
     * @return scenario 文本块，未找到返回 null
     */
    static String extractScenarioBlock(String featureContent, String scenarioName) {
        Pattern scenarioHeader = Pattern.compile(
                "(?:^|\\n)\\s*Scenario:\\s*" + Pattern.quote(scenarioName) + "\\s*\\n");

        Matcher m = scenarioHeader.matcher(featureContent);
        if (!m.find()) {
            return null;
        }

        int blockStart = m.start();
        int headerEnd = m.end();

        Pattern nextBoundary = Pattern.compile(
                "\\n\\s*(?:@\\w+\\s*\\n\\s*)?(?:Scenario(?:\\s+Outline)?:|Rule:|Examples:)\\s*");
        Matcher boundaryMatcher = nextBoundary.matcher(featureContent);
        int blockEnd = featureContent.length();

        if (boundaryMatcher.find(headerEnd)) {
            blockEnd = boundaryMatcher.start();
        }

        return featureContent.substring(blockStart, blockEnd);
    }
}
