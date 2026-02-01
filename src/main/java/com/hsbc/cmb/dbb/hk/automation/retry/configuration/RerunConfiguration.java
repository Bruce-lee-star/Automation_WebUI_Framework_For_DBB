package com.hsbc.cmb.dbb.hk.automation.retry.configuration;

import com.hsbc.cmb.dbb.hk.automation.framework.utils.LoggingConfigUtil;
import com.hsbc.cmb.dbb.hk.automation.retry.controller.RetryController;
import com.hsbc.cmb.dbb.hk.automation.retry.strategy.RetryDelayStrategy;
import com.hsbc.cmb.dbb.hk.automation.retry.strategy.RetryDelayStrategyFactory;
import io.cucumber.junit.CucumberOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RerunConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(RerunConfiguration.class);
    
    private static String detectCurrent() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                String fileName = element.getFileName();
                
                if (fileName != null && (fileName.endsWith("Runner.java") || 
                        fileName.endsWith("IT.java") || 
                        fileName.endsWith("Test.java"))) {
                    if (className.contains("CucumberWithSerenity") || 
                            className.contains("CucumberOptions")) {
                        logger.info("[RerunConfiguration] Current runner class from call stack: {}", className);
                        return className;
                    }
                }
            }
            
            logger.debug("[RerunConfiguration] No runner class found in call stack");
            return null;
        } catch (Exception e) {
            logger.error("[RerunConfiguration] Failed to detect runner from call stack", e);
            return null;
        }
    }
    
    private static String detectRunnerClassFromClasspath() {
        try {
            Path testJavaPath = Paths.get("src/test/java").toAbsolutePath();
            if (!Files.exists(testJavaPath)) {
                logger.warn("[RerunConfiguration] src/test/java not found");
                return null;
            }
            
            List<String> patterns = Arrays.asList(
                "**/*Runner.java",
                "**/*IT.java",
                "**/*Test.java"
            );
            
            List<Path> runnerFiles = Files.walk(testJavaPath)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> matchesAnyPattern(path.toString(), patterns))
                    .filter(path -> {
                        try {
                            String content = Files.readString(path);
                            return content.contains("CucumberWithSerenity") || 
                                   content.contains("@CucumberOptions");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
            
            if (!runnerFiles.isEmpty()) {
                Path runnerFile = runnerFiles.get(0);
                String relativePath = testJavaPath.relativize(runnerFile).toString();
                String className = relativePath.replace(File.separator, ".")
                        .replace("/", ".")
                        .replace(".java", "");
                logger.info("[RerunConfiguration] Detected runner class: {}", className);
                return className;
            }
            
            logger.warn("[RerunConfiguration] No runner class found in src/test/java");
            return null;
        } catch (Exception e) {
            logger.error("[RerunConfiguration] Failed to detect runner class", e);
            return null;
        }
    }
    
    private static boolean matchesAnyPattern(String path, List<String> patterns) {
        String normalizedPath = path.replace("\\", "/");
        String fileName = normalizedPath.substring(normalizedPath.lastIndexOf("/") + 1);
        
        for (String pattern : patterns) {
            String normalizedPattern = pattern.trim();
            String patternFileName = normalizedPattern.substring(normalizedPattern.lastIndexOf("/") + 1);
            
            if (patternFileName.contains("*")) {
                String regex = patternFileName
                        .replace("**/", "")
                        .replace("**", ".*")
                        .replace("*", "[^/]*")
                        .replace(".java", "");
                if (fileName.replace(".java", "").matches(regex)) {
                    return true;
                }
            } else {
                if (fileName.equals(patternFileName)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private static String resolveRunnerClass() {
        // Priority 1: Detect from current call stack
        String runnerFromStack = detectCurrent();
        if (runnerFromStack != null) {
            logger.info("[RerunConfiguration] Resolved runner class from call stack: {}", runnerFromStack);
            return runnerFromStack;
        }
        
        // Priority 2: Detect from classpath
        String runnerFromClasspath = detectRunnerClassFromClasspath();
        if (runnerFromClasspath != null) {
            logger.info("[RerunConfiguration] Resolved runner class from classpath: {}", runnerFromClasspath);
            return runnerFromClasspath;
        }
        
        logger.warn("[RerunConfiguration] Could not resolve runner class");
        return null;
    }
    
    // Instance fields
    private List<String> gluePackages = new ArrayList<>();
    private String rerunFile = "@target/rerun.txt";
    private String rerunLogFile = System.getProperty("rerun.log.file", "target/rerun.log");
    private String classpath;
    private String javaBinary;
    
    private List<String> plugins;
    private boolean monochrome;
    private boolean dryRun;
    private String featuresPath;
    
    private RetryController retryController;
    private RetryDelayStrategy delayStrategy;

    private static RerunConfiguration instance;

    private volatile boolean fullyInitialized = false;

    private RerunConfiguration() {
        // 延迟初始化,只在需要时才加载完整配置
    }

    public static synchronized RerunConfiguration getInstance() {
        if (instance == null) {
            instance = new RerunConfiguration();
        }
        return instance;
    }

    private void loadFromRunnerClass() {
        String effectiveRunnerClass = resolveRunnerClass();
        
        if (effectiveRunnerClass == null) {
            logger.warn("[RerunConfiguration] Runner class not specified, scanning for glue packages...");
            this.gluePackages = scanForGluePackages();
            loadDefaults();
            return;
        }
        
        try {
            Class<?> runnerClass = Class.forName(effectiveRunnerClass);
            CucumberOptions options = runnerClass.getAnnotation(CucumberOptions.class);
            
            if (options != null) {
                String[] runnerGlue = options.glue();
                if (runnerGlue != null && runnerGlue.length > 0) {
                    this.gluePackages = new ArrayList<>(Arrays.asList(runnerGlue));
                } else {
                    logger.info("[RerunConfiguration] Runner glue is empty, scanning for glue packages...");
                    this.gluePackages = scanForGluePackages();
                }
                this.plugins = new ArrayList<>(Arrays.asList(options.plugin()));
                this.monochrome = options.monochrome();
                this.dryRun = options.dryRun();
                this.featuresPath = String.join(" ", options.features());
                this.javaBinary = detectJavaBinary();
                this.classpath = computeClasspath();
                
                boolean allValid = true;
                for (String pkg : gluePackages) {
                    if (!isGluePackage(pkg)) {
                        logger.warn("[RerunConfiguration] Glue package does not contain 'glue': {}", pkg);
                        allValid = false;
                    }
                }
                
                logger.info("[RerunConfiguration] ============================================");
                logger.info("[RerunConfiguration] Configuration loaded from runner: {}", effectiveRunnerClass);
                logger.info("[RerunConfiguration] ============================================");
                logger.info("[RerunConfiguration] Features Path: {}", featuresPath);
                logger.info("[RerunConfiguration] Glue Packages: {}", gluePackages);
                logger.info("[RerunConfiguration] Plugins: {}", plugins);
                logger.info("[RerunConfiguration] Monochrome: {}", monochrome);
                logger.info("[RerunConfiguration] Dry Run: {}", dryRun);
                logger.info("[RerunConfiguration] ============================================");
            } else {
                logger.warn("[RerunConfiguration] {} does not have @CucumberOptions annotation", effectiveRunnerClass);
                loadDefaults();
            }
        } catch (ClassNotFoundException e) {
            logger.warn("[RerunConfiguration] Runner class {} not found, scanning for glue packages...", effectiveRunnerClass);
            this.gluePackages = scanForGluePackages();
            loadDefaults();
        } catch (Exception e) {
            logger.error("[RerunConfiguration] Failed to load from runner class", e);
            this.gluePackages = scanForGluePackages();
            loadDefaults();
        }
    }

    private void loadDefaults() {
        this.gluePackages = new ArrayList<>();
        this.plugins = Arrays.asList("pretty", "html:target/cucumber-rerun-report.html", 
                "json:target/cucumber-rerun-report.json", "rerun:target/rerun.txt");
        this.monochrome = true;
        this.dryRun = false;
        this.featuresPath = "src/test/resources/features";
        this.javaBinary = detectJavaBinary();
        this.classpath = computeClasspath();
        
        logger.warn("[RerunConfiguration] Using defaults - glue packages not configured. " +
                "Please set -Dcucumber.glue or -Dcucumber.glue.package.suffix");
    }

    private String computeClasspath() {
        StringBuilder classpath = new StringBuilder();
        
        String projectClasspath = System.getProperty("java.class.path", "");
        if (!projectClasspath.isEmpty()) {
            classpath.append(projectClasspath);
        } else {
            classpath.append("target/classes");
            String separator = System.getProperty("os.name", "").toLowerCase().contains("windows") ? ";" : ":";
            classpath.append(separator).append("target/test-classes");
            
            String mavenDependencies = System.getProperty("maven.runtime.classpath", "");
            if (!mavenDependencies.isEmpty()) {
                classpath.append(separator).append(mavenDependencies);
            }
        }

        String result = classpath.toString();
        // 将classpath日志降级为TRACE，减少日志噪音
        if (logger.isTraceEnabled()) {
            logger.trace("[RerunConfiguration] Computed classpath: {}...",
                result.length() > 100 ? result.substring(0, 100) + "..." : result);
        }
        return result;
    }

    private void initializeRetryComponents() {
        this.retryController = RetryController.getInstance();
        this.retryController.initialize();

        this.delayStrategy = RetryDelayStrategyFactory.createStrategy();

        logger.info("[RerunConfiguration] Initialized - Glue: {}, Strategy: {}",
            gluePackages, delayStrategy.getName());
    }

    /**
     * 延迟初始化完整配置,只在真正需要重试时调用
     */
    public synchronized void lazyInitialize() {
        if (!fullyInitialized) {
            logger.info("[RerunConfiguration] Performing lazy initialization...");
            loadFromRunnerClass();
            initializeRetryComponents();
            fullyInitialized = true;
        }
    }

    private static String getBasePackage() {
        String className = RerunConfiguration.class.getName();
        String packageName = className.substring(0, className.lastIndexOf('.'));
        return packageName.substring(0, packageName.lastIndexOf('.'));
    }

    private static List<String> parseGluePackages(String gluePackages) {
        if (gluePackages == null || gluePackages.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] packages = gluePackages.split(",");
        List<String> result = new ArrayList<>();
        for (String pkg : packages) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> scanForGluePackages() {
        List<String> gluePackages = new ArrayList<>();
        
        Path testJavaPath = Paths.get("src/test/java");
        if (!Files.exists(testJavaPath)) {
            logger.warn("[RerunConfiguration] src/test/java not found");
            return gluePackages;
        }
        
        try {
            List<Path> packageDirs = Files.walk(testJavaPath)
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String folderName = path.getFileName().toString().toLowerCase();
                        return folderName.contains("glue");
                    })
                    .collect(Collectors.toList());
            
            for (Path packageDir : packageDirs) {
                String relativePath = testJavaPath.relativize(packageDir).toString();
                String packageName = relativePath.replace(File.separator, ".");
                gluePackages.add(packageName);
            }
            
            if (!gluePackages.isEmpty()) {
                logger.info("[RerunConfiguration] Auto-scanned glue packages: {}", gluePackages);
            } else {
                logger.warn("[RerunConfiguration] No glue packages found in src/test/java");
            }
        } catch (Exception e) {
            logger.error("[RerunConfiguration] Failed to scan for glue packages", e);
        }
        
        return gluePackages;
    }

    private static boolean isGluePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        String packageLower = packageName.toLowerCase();
        return packageLower.contains("glue");
    }

    private String detectJavaBinary() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            logger.warn("java.home not set, using default java");
            return "java";
        }
        
        String os = System.getProperty("os.name", "").toLowerCase();
        String javaBinary = javaHome + File.separator + "bin" + File.separator + "java";
        
        if (os.contains("windows")) {
            javaBinary += ".exe";
        }
        
        return javaBinary;
    }

    public List<String> getGluePackages() {
        return gluePackages;
    }

    public String getGluePackage() {
        return String.join(" ", gluePackages);
    }

    public List<String> getPlugins() {
        return plugins;
    }

    public String getRerunFile() {
        return rerunFile;
    }

    public String getRerunLogFile() {
        return rerunLogFile;
    }

    public String getClasspath() {
        return classpath;
    }

    public String getJavaBinary() {
        return javaBinary;
    }

    public String getFeaturesPath() {
        return featuresPath;
    }

    public boolean isMonochrome() {
        return monochrome;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setGluePackages(List<String> gluePackages) {
        this.gluePackages = gluePackages;
    }

    public RetryController getRetryController() {
        return retryController;
    }

    public RetryDelayStrategy getDelayStrategy() {
        return delayStrategy;
    }

    public String getPluginConfigurationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[RerunConfiguration] Plugin Configuration:\n");
        sb.append("  Glue Packages: ").append(gluePackages).append("\n");
        sb.append("  Features Path: ").append(featuresPath).append("\n");
        sb.append("  Plugins: ").append(plugins).append("\n");
        sb.append("  Monochrome: ").append(monochrome).append("\n");
        sb.append("  Dry Run: ").append(dryRun).append("\n");
        sb.append("  Retry Enabled: ").append(isRetryEnabled()).append("\n");
        sb.append("  Max Retries: ").append(getMaxRetries()).append("\n");
        return sb.toString();
    }

    public boolean isRetryEnabled() {
        if (!fullyInitialized) {
            return false;
        }
        return retryController != null && retryController.isRetryEnabled();
    }

    public int getMaxRetries() {
        if (!fullyInitialized) {
            return 0;
        }
        return retryController != null ? retryController.getMaxRetries() : 0;
    }

    public long calculateRetryDelay(int attemptNumber) {
        if (!fullyInitialized) {
            return 0;
        }
        if (delayStrategy != null) {
            return delayStrategy.calculateDelay(attemptNumber);
        }
        return 0;
    }

    private List<String> buildBaseCommand() {
        List<String> command = new ArrayList<>();
        command.add(getJavaBinary());
        command.add("-cp");
        command.add(getClasspath());
        command.add("io.cucumber.core.cli.Main");
        command.add("--glue");
        command.add(getGluePackage());
        return command;
    }

    private void addPluginOptions(List<String> command) {
        for (String plugin : plugins) {
            command.add("--plugin");
            command.add(plugin);
        }
    }

    private void addCommonOptions(List<String> command) {
        if (monochrome) {
            command.add("--monochrome");
        }
        if (dryRun) {
            command.add("--dry-run");
        }
    }

    public List<String> buildCucumberCommand() {
        List<String> command = buildBaseCommand();
        addPluginOptions(command);
        addCommonOptions(command);
        command.add(getRerunFile());
        return command;
    }

    public List<String> buildInitialRunCommand() {
        List<String> command = buildBaseCommand();
        addPluginOptions(command);
        addCommonOptions(command);
        command.add(getFeaturesPath());
        return command;
    }

    public List<String> buildRerunCommand(int rerunRound, int maxRerunAttempts) {
        logger.info("[RerunConfiguration] ENTERING buildRerunCommand - rerunRound: {}, maxRerunAttempts: {}", rerunRound, maxRerunAttempts);
        logger.info("[RerunConfiguration] Configuration state - gluePackages: {}, featuresPath: {}, plugins: {}, isRetryEnabled: {}, maxRetries: {}",
            gluePackages, featuresPath, plugins, isRetryEnabled(), getMaxRetries());

        // 控制是否输出详细命令日志的系统属性，默认false（不输出详细日志）
        boolean verboseCommandLog = LoggingConfigUtil.isVerboseLoggingEnabled();

        List<String> command = buildBaseCommand();
        addPluginOptions(command);
        addCommonOptions(command);
        command.add(getRerunFile());

        if (verboseCommandLog && logger.isDebugEnabled()) {
            logger.debug("[RerunConfiguration] Base command built: {}", command);
        } else {
            // 输出摘要信息，避免输出包含完整classpath的命令列表
            logger.info("[RerunConfiguration] Base command built: java -cp <classpath-length:{}> {}",
                command.size() > 2 ? command.get(2).length() : 0,
                command.size() > 3 ? command.subList(3, Math.min(8, command.size())) : command);
        }

        List<String> systemProps = new ArrayList<>();
        systemProps.add("-Drerun.mode=true");
        systemProps.add("-Drerun.round=" + rerunRound);
        systemProps.add("-DrerunFailingTestsCount=" + maxRerunAttempts);
        systemProps.add("-Dplaywright.retry.enabled=true");

        List<String> newCommand = new ArrayList<>();
        newCommand.add(command.get(0));
        newCommand.add("-cp");
        newCommand.add(command.get(2));
        newCommand.addAll(systemProps);
        newCommand.addAll(command.subList(3, command.size()));

        if (verboseCommandLog && logger.isDebugEnabled()) {
            logger.debug("[RerunConfiguration] Final command: {}", newCommand);
        } else {
            // 输出摘要信息
            logger.info("[RerunConfiguration] Final command: java -cp <classpath-length:{}> -Drerun.mode=true -Drerun.round={} -DrerunFailingTestsCount={} {}",
                newCommand.size() > 2 ? newCommand.get(2).length() : 0,
                rerunRound, maxRerunAttempts,
                newCommand.size() > 6 ? newCommand.subList(6, Math.min(11, newCommand.size())) : newCommand);
        }
        return newCommand;
    }

    public List<String> buildCucumberCommandWithSystemProperties(int rerunRound, int maxRerunAttempts) {
        reloadFromCurrentRunner();
        return buildRerunCommand(rerunRound, maxRerunAttempts);
    }

    public void reloadFromCurrentRunner() {
        String runnerFromStack = detectCurrent();
        if (runnerFromStack != null) {
            logger.info("[RerunConfiguration] Reloading configuration from current runner in call stack: {}", runnerFromStack);
            loadFromRunnerClass(runnerFromStack);
        } else {
            logger.debug("[RerunConfiguration] No runner found in call stack during reload");
        }
    }

    private void loadFromRunnerClass(String runnerClassName) {
        try {
            Class<?> runnerClass = Class.forName(runnerClassName);
            CucumberOptions options = runnerClass.getAnnotation(CucumberOptions.class);
            
            if (options != null) {
                String[] runnerGlue = options.glue();
                if (runnerGlue != null && runnerGlue.length > 0) {
                    this.gluePackages = new ArrayList<>(Arrays.asList(runnerGlue));
                } else {
                    logger.info("[RerunConfiguration] Runner glue is empty, scanning for glue packages...");
                    this.gluePackages = scanForGluePackages();
                }
                this.plugins = new ArrayList<>(Arrays.asList(options.plugin()));
                this.monochrome = options.monochrome();
                this.dryRun = options.dryRun();
                this.featuresPath = String.join(" ", options.features());
                
                boolean allValid = true;
                for (String pkg : gluePackages) {
                    if (!isGluePackage(pkg)) {
                        logger.warn("[RerunConfiguration] Glue package does not contain 'glue': {}", pkg);
                        allValid = false;
                    }
                }
                
                logger.info("[RerunConfiguration] ============================================");
                logger.info("[RerunConfiguration] Configuration reloaded from runner: {}", runnerClassName);
                logger.info("[RerunConfiguration] ============================================");
                logger.info("[RerunConfiguration] Features Path: {}", featuresPath);
                logger.info("[RerunConfiguration] Glue Packages: {}", gluePackages);
                logger.info("[RerunConfiguration] Plugins: {}", plugins);
                logger.info("[RerunConfiguration] Monochrome: {}", monochrome);
                logger.info("[RerunConfiguration] Dry Run: {}", dryRun);
                logger.info("[RerunConfiguration] ============================================");
            } else {
                logger.warn("[RerunConfiguration] {} does not have @CucumberOptions annotation", runnerClassName);
            }
        } catch (ClassNotFoundException e) {
            logger.warn("[RerunConfiguration] Runner class {} not found during reload", runnerClassName);
        } catch (Exception e) {
            logger.error("[RerunConfiguration] Failed to reload from runner class", e);
        }
    }
}
