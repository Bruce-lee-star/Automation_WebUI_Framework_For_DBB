package com.hsbc.cmb.hk.dbb.automation.framework.web.listener;

import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.InitializationException;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 监听器注册表
 * 自动发现和注册所有监听器
 * 支持从指定包路径扫描监听器类
 */
public class ListenerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ListenerRegistry.class);
    private static final List<Object> registeredListeners = new CopyOnWriteArrayList<>();
    private static final Set<Class<?>> listenerClasses = new HashSet<>();
    private static boolean initialized = false;

    /**
     * 初始化监听器注册表
     * 自动扫描指定包下的所有监听器类
     *
     * @param basePackage 基础包路径
     */
    public static synchronized void initialize(String basePackage) {
        if (initialized) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "ListenerRegistry already initialized");
            return;
        }

        LoggingConfigUtil.logInfoIfVerbose(logger, "Initializing ListenerRegistry with base package: {}", basePackage);
        LoggingConfigUtil.logDebugIfVerbose(logger, "Starting listener registry initialization");
        
        try {
            Set<Class<?>> classes = scanPackage(basePackage);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Scanned {} classes in package: {}", classes.size(), basePackage);
            
            int listenerCount = 0;
            for (Class<?> clazz : classes) {
                if (isListenerClass(clazz)) {
                    // RetryScenarioListener 只在有 rerunFailingTestsCount 系统属性时才注册
                    if (RetryScenarioListener.class.isAssignableFrom(clazz)) {
                        String rerunCountStr = System.getProperty("rerunFailingTestsCount");
                        if (rerunCountStr != null && !rerunCountStr.trim().isEmpty()) {
                            registerListener(clazz);
                            listenerCount++;
                            LoggingConfigUtil.logInfoIfVerbose(logger, "RetryScenarioListener registered (rerunFailingTestsCount={})", rerunCountStr);
                        } else {
                            LoggingConfigUtil.logInfoIfVerbose(logger, "Skipping RetryScenarioListener registration (rerunFailingTestsCount not specified)");
                        }
                    } else {
                        registerListener(clazz);
                        listenerCount++;
                    }
                }
            }

            initialized = true;
            LoggingConfigUtil.logInfoIfVerbose(logger, "ListenerRegistry initialized successfully. Scanned {} classes, registered {} listeners", classes.size(), listenerCount);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Listener registry initialization completed");
        } catch (Exception e) {
            logger.error("Failed to initialize ListenerRegistry", e);
            throw new InitializationException("Failed to initialize ListenerRegistry", e);
        }
    }

    /**
     * 扫描指定包下的所有类
     *
     * @param basePackage 基础包路径
     * @return 包下的所有类集合
     * @throws IOException IO异常
     */
    private static Set<Class<?>> scanPackage(String basePackage) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        String packagePath = basePackage.replace('.', '/');
        
        // 获取类加载器
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(packagePath);

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String protocol = resource.getProtocol();

            if ("file".equals(protocol)) {
                // 从文件系统加载
                String filePath = resource.getFile().replaceAll("%20", " ");
                classes.addAll(scanDirectory(new File(filePath), basePackage));
            } else if ("jar".equals(protocol)) {
                // 从JAR文件加载
                String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                classes.addAll(scanJar(new File(jarPath), basePackage));
            }
        }

        return classes;
    }

    /**
     * 扫描目录下的所有类
     *
     * @param directory   目录
     * @param packageName 包名
     * @return 类集合
     */
    private static Set<Class<?>> scanDirectory(File directory, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        
        if (!directory.exists() || !directory.isDirectory()) {
            return classes;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 递归扫描子目录
                classes.addAll(scanDirectory(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                // 加载类
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className));
            }
        }

        return classes;
    }

    /**
     * 扫描JAR文件中的所有类
     *
     * @param jarFile     JAR文件
     * @param packageName 包名
     * @return 类集合
     */
    private static Set<Class<?>> scanJar(File jarFile, String packageName) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        String packagePath = packageName.replace('.', '/');

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")) {
                    // 加载类
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    classes.add(Class.forName(className));
                }
            }
        }

        return classes;
    }

    /**
     * 检查类是否是监听器类
     *
     * @param clazz 类对象
     * @return 是否是监听器类
     */
    private static boolean isListenerClass(Class<?> clazz) {
        try {
            Class<? extends Annotation> listenClass = Class.forName("net.thucydides.core.annotations.Listen").asSubclass(Annotation.class);
            if (clazz.isAnnotationPresent(listenClass)) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "{} is a listener (has @Listen annotation)", clazz.getName());
                return true;
            }
        } catch (ClassNotFoundException e) {
        }

        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getPackage() != null && 
                (iface.getPackage().getName().startsWith("net.thucydides.core.events") || 
                 iface.getPackage().getName().startsWith("net.thucydides.core.steps") ||
                 iface.getPackage().getName().startsWith("net.thucydides.core.annotations") ||
                 iface.getName().equals("io.cucumber.plugin.EventListener"))) {
                LoggingConfigUtil.logDebugIfVerbose(logger, "{} is a listener (implements: {})", clazz.getName(), iface.getName());
                return true;
            }
        }

        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            try {
                if (method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.BeforeScenario").asSubclass(Annotation.class)) ||
                    method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.AfterScenario").asSubclass(Annotation.class)) ||
                    method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.BeforeStep").asSubclass(Annotation.class)) ||
                    method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.AfterStep").asSubclass(Annotation.class)) ||
                    method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.BeforeFeature").asSubclass(Annotation.class)) ||
                    method.isAnnotationPresent(Class.forName("net.thucydides.core.annotations.AfterFeature").asSubclass(Annotation.class))) {
                    LoggingConfigUtil.logDebugIfVerbose(logger, "{} is a listener (has listener annotations)", clazz.getName());
                    return true;
                }
            } catch (ClassNotFoundException e) {
            }
        }

        return false;
    }

    /**
     * 注册监听器类
     *
     * @param listenerClass 监听器类
     */
    public static synchronized void registerListener(Class<?> listenerClass) {
        if (listenerClasses.contains(listenerClass)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Listener class already registered: {}", listenerClass.getName());
            return;
        }

        try {
            Object listener;
            try {
                listener = listenerClass.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                java.lang.reflect.Constructor<?>[] constructors = listenerClass.getDeclaredConstructors();
                if (constructors.length == 0) {
                    throw new InitializationException("No constructors found for listener class: " + listenerClass.getName());
                }
                java.lang.reflect.Constructor<?> constructor = constructors[0];
                constructor.setAccessible(true);
                listener = constructor.newInstance();
            }
            registeredListeners.add(listener);
            listenerClasses.add(listenerClass);
            
            LoggingConfigUtil.logInfoIfVerbose(logger, "Registered listener: {}", listenerClass.getName());
        } catch (IllegalAccessException e) {
            try {
                java.lang.reflect.Constructor<?>[] constructors = listenerClass.getDeclaredConstructors();
                if (constructors.length == 0) {
                    throw new InitializationException("No constructors found for listener class: " + listenerClass.getName());
                }
                java.lang.reflect.Constructor<?> constructor = constructors[0];
                constructor.setAccessible(true);
                Object listener = constructor.newInstance();
                registeredListeners.add(listener);
                listenerClasses.add(listenerClass);
                LoggingConfigUtil.logInfoIfVerbose(logger, "Registered listener (after setting accessible): {}", listenerClass.getName());
            } catch (Exception ex) {
                logger.error("Failed to instantiate listener after setting accessible: {}", listenerClass.getName(), ex);
            }
        } catch (ReflectiveOperationException e) {
            logger.error("Failed to instantiate listener: {}", listenerClass.getName(), e);
        }
    }

    /**
     * 注册监听器实例
     *
     * @param listener 监听器实例
     */
    public static synchronized void registerListener(Object listener) {
        Class<?> listenerClass = listener.getClass();
        if (registeredListeners.contains(listener)) {
            LoggingConfigUtil.logDebugIfVerbose(logger, "Listener already registered: {}", listenerClass.getName());
            return;
        }

        registeredListeners.add(listener);
        listenerClasses.add(listenerClass);
        
        LoggingConfigUtil.logInfoIfVerbose(logger, "Registered listener: {}", listenerClass.getName());
    }

    /**
     * 获取所有注册的监听器
     *
     * @return 监听器列表
     */
    public static List<Object> getRegisteredListeners() {
        return Collections.unmodifiableList(registeredListeners);
    }

    /**
     * 获取所有监听器类
     *
     * @return 监听器类集合
     */
    public static Set<Class<?>> getListenerClasses() {
        return Collections.unmodifiableSet(listenerClasses);
    }

    /**
     * 清理监听器注册表
     */
    public static synchronized void cleanup() {
        LoggingConfigUtil.logInfoIfVerbose(logger, "Cleaning up ListenerRegistry");
        LoggingConfigUtil.logDebugIfVerbose(logger, "Starting listener registry cleanup");
        registeredListeners.clear();
        listenerClasses.clear();
        initialized = false;
        LoggingConfigUtil.logDebugIfVerbose(logger, "Listener registry cleanup completed");
        LoggingConfigUtil.logInfoIfVerbose(logger, "ListenerRegistry cleaned up");
    }

    /**
     * 检查是否已初始化
     *
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 扫描指定包前缀下的所有类（用于动态发现）
     *
     * @param packagePrefix 包前缀，如 "com.hsbc.cmb.dbb.hk.automation"
     * @return 包含监听器接口的类集合
     * @throws IOException IO异常
     * @throws ClassNotFoundException 类未找到异常
     */
    public static synchronized Set<Class<?>> scanPackages(String packagePrefix) throws IOException, ClassNotFoundException {
        Set<Class<?>> allClasses = new HashSet<>();
        String packagePath = packagePrefix.replace('.', '/');
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(packagePath);

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String protocol = resource.getProtocol();

            if ("file".equals(protocol)) {
                String filePath = resource.getFile().replaceAll("%20", " ");
                allClasses.addAll(scanDirectoryRecursive(new File(filePath), packagePrefix));
            } else if ("jar".equals(protocol)) {
                String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                allClasses.addAll(scanJarRecursive(new File(jarPath), packagePrefix));
            }
        }

        // 过滤出包含监听器接口的类
        Set<Class<?>> listenerClasses = new HashSet<>();
        for (Class<?> clazz : allClasses) {
            if (isListenerClass(clazz)) {
                listenerClasses.add(clazz);
            }
        }

        LoggingConfigUtil.logDebugIfVerbose(logger, "Scanned {} classes, found {} listener classes in package prefix: {}", 
            allClasses.size(), listenerClasses.size(), packagePrefix);
        return listenerClasses;
    }
    
    /**
     * 递归扫描目录下的所有类
     *
     * @param directory   目录
     * @param packageName 包名
     * @return 类集合
     */
    private static Set<Class<?>> scanDirectoryRecursive(File directory, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        
        if (!directory.exists() || !directory.isDirectory()) {
            return classes;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                classes.addAll(scanDirectoryRecursive(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className));
            }
        }

        return classes;
    }
    
    /**
     * 递归扫描JAR文件中的所有类
     *
     * @param jarFile     JAR文件
     * @param packageName 包名
     * @return 类集合
     */
    private static Set<Class<?>> scanJarRecursive(File jarFile, String packageName) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        String packagePath = packageName.replace('.', '/');

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    classes.add(Class.forName(className));
                }
            }
        }

        return classes;
    }
    
    /**
     * 扫描指定包前缀下所有包含指定字符串的包
     * 例如：scanPackagesContaining("com.hsbc.cmb.dbb.hk.automation", "listener") 
     * 会找到 com.web.automation.core.listener, com.web.automation.serenity.listener 等
     *
     * @param packagePrefix 包前缀，如 "com.hsbc.cmb.dbb.hk.automation"
     * @param packageContains 包名包含的字符串，如 "listener"
     * @return 包含指定字符串的包名列表
     * @throws IOException IO异常
     * @throws ClassNotFoundException 类未找到异常
     */
    public static synchronized List<String> scanPackagesContaining(String packagePrefix, String packageContains) throws IOException, ClassNotFoundException {
        List<String> packages = new ArrayList<>();
        String packagePath = packagePrefix.replace('.', '/');
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(packagePath);

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            String protocol = resource.getProtocol();

            if ("file".equals(protocol)) {
                String filePath = resource.getFile().replaceAll("%20", " ");
                packages.addAll(findPackagesContaining(new File(filePath), packagePrefix, packageContains));
            } else if ("jar".equals(protocol)) {
                String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                packages.addAll(findPackagesInJarContaining(new File(jarPath), packagePrefix, packageContains));
            }
        }

        LoggingConfigUtil.logDebugIfVerbose(logger, "Found {} packages containing '{}' under '{}'", packages.size(), packageContains, packagePrefix);
        return packages;
    }
    
    /**
     * 在目录中查找所有包含指定字符串的包
     *
     * @param directory      目录
     * @param basePackage    基础包名
     * @param packageContains 包名包含的字符串
     * @return 包名列表
     */
    private static List<String> findPackagesContaining(File directory, String basePackage, String packageContains) {
        List<String> packages = new ArrayList<>();
        
        if (!directory.exists() || !directory.isDirectory()) {
            return packages;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return packages;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                String currentPackage = basePackage + "." + file.getName();
                if (file.getName().contains(packageContains)) {
                    packages.add(currentPackage);
                    LoggingConfigUtil.logDebugIfVerbose(logger, "Found listener package: {}", currentPackage);
                }
                packages.addAll(findPackagesContaining(file, currentPackage, packageContains));
            }
        }

        return packages;
    }
    
    /**
     * 在JAR文件中查找所有包含指定字符串的包
     *
     * @param jarFile        JAR文件
     * @param basePackage    基础包名
     * @param packageContains 包名包含的字符串
     * @return 包名列表
     * @throws IOException IO异常
     */
    private static List<String> findPackagesInJarContaining(File jarFile, String basePackage, String packageContains) throws IOException {
        List<String> packages = new ArrayList<>();
        String basePath = basePackage.replace('.', '/');

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            Set<String> foundPackages = new HashSet<>();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(basePath) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    String packageName = getPackageName(className);
                    String lastPart = packageName.substring(packageName.lastIndexOf('.') + 1);
                    
                    if (lastPart.contains(packageContains) && !foundPackages.contains(packageName)) {
                        foundPackages.add(packageName);
                        packages.add(packageName);
                        LoggingConfigUtil.logDebugIfVerbose(logger, "Found listener package in JAR: {}", packageName);
                    }
                }
            }
        }

        return packages;
    }
    
    /**
     * 获取类的包名
     *
     * @param className 类名
     * @return 包名
     */
    private static String getPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }
}