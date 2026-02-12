package com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory;

import com.hsbc.cmb.hk.dbb.automation.framework.web.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 企业级PageObject工厂类
 * 
 * 功能：
 * 1. 支持多种实例生命周期策略（单例、原型、线程隔离等）
 * 2. 支持依赖注入和条件创建
 * 3. 支持创建拦截器和回调
 * 4. 提供统计和监控功能
 * 5. 线程安全，支持并发场景
 * 
 * 使用方式：
 * 在Steps类中：
 * - private BaiduPage baiduPage = PageObjectFactory.getPage(BaiduPage.class);
 * 
 * 或者使用生命周期策略：
 * - private BaiduPage baiduPage = PageObjectFactory.getPage(BaiduPage.class, LifecycleStrategy.PROTOTYPE);
 * 
 * 或者使用Builder模式：
 * - private BaiduPage baiduPage = PageObjectFactory.builder()
 *       .lifecycle(LifecycleStrategy.PROTOTYPE)
 *       .lazy(true)
 *       .build()
 *       .getPage(BaiduPage.class);
 */
public class PageObjectFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(PageObjectFactory.class);
    
    /**
     * 实例生命周期策略
     */
    public enum LifecycleStrategy {
        /**
         * 单例模式（默认）：整个应用运行期间只创建一个实例
         */
        SINGLETON,
        
        /**
         * 原型模式：每次调用getPage()都创建新实例
         */
        PROTOTYPE,
        
        /**
         * 线程隔离模式：每个线程有独立的实例
         */
        THREAD_ISOLATED,
        
        /**
         * 请求作用域：每次测试请求创建新实例（模拟）
         */
        REQUEST_SCOPED
    }
    
    /**
     * 创建配置
     */
    public static class CreationConfig {
        private final LifecycleStrategy lifecycleStrategy;
        private final boolean lazy;
        private final Map<Class<?>, Supplier<Object>> customSuppliers;
        private final List<Consumer<Object>> postCreateHooks;
        private final Map<String, Object> properties;
        
        private CreationConfig(LifecycleStrategy lifecycleStrategy, boolean lazy,
                          Map<Class<?>, Supplier<Object>> customSuppliers,
                          List<Consumer<Object>> postCreateHooks,
                          Map<String, Object> properties) {
            this.lifecycleStrategy = lifecycleStrategy;
            this.lazy = lazy;
            this.customSuppliers = customSuppliers;
            this.postCreateHooks = postCreateHooks;
            this.properties = properties;
        }
        
        public LifecycleStrategy getLifecycleStrategy() {
            return lifecycleStrategy;
        }
        
        public boolean isLazy() {
            return lazy;
        }
        
        public Map<Class<?>, Supplier<Object>> getCustomSuppliers() {
            return customSuppliers;
        }
        
        public List<Consumer<Object>> getPostCreateHooks() {
            return postCreateHooks;
        }
        
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
    
    /**
     * Builder模式 - 灵活配置PageObjectFactory
     */
    public static class Builder {
        private LifecycleStrategy lifecycleStrategy = LifecycleStrategy.SINGLETON;
        private boolean lazy = false;
        private final Map<Class<?>, Supplier<Object>> customSuppliers = new HashMap<>();
        private final List<Consumer<Object>> postCreateHooks = new CopyOnWriteArrayList<>();
        private final Map<String, Object> properties = new HashMap<>();
        
        /**
         * 设置生命周期策略
         */
        public Builder lifecycle(LifecycleStrategy strategy) {
            this.lifecycleStrategy = strategy;
            return this;
        }
        
        /**
         * 设置是否延迟初始化
         */
        public Builder lazy(boolean lazy) {
            this.lazy = lazy;
            return this;
        }
        
        /**
         * 注册自定义供应商
         */
        public <T> Builder customSupplier(Class<T> pageClass, Supplier<T> supplier) {
            @SuppressWarnings("unchecked")
            Supplier<Object> objectSupplier = (Supplier<Object>) (Supplier<?>) supplier;
            customSuppliers.put(pageClass, objectSupplier);
            return this;
        }
        
        /**
         * 添加创建后钩子
         */
        public Builder postCreateHook(Consumer<Object> hook) {
            postCreateHooks.add(hook);
            return this;
        }
        
        /**
         * 设置属性
         */
        public Builder property(String key, Object value) {
            properties.put(key, value);
            return this;
        }
        
        /**
         * 构建配置
         */
        public CreationConfig build() {
            return new CreationConfig(lifecycleStrategy, lazy, customSuppliers, postCreateHooks, properties);
        }
    }
    
    // 默认配置
    private static final CreationConfig DEFAULT_CONFIG = new Builder().build();
    
    // 存储所有PageObject实例，使用类名作为key
    private static final ConcurrentMap<Class<?>, Object> singleInstances = new ConcurrentHashMap<>();
    
    // 线程隔离实例存储
    private static final ThreadLocal<Map<Class<?>, Object>> threadInstances =
            ThreadLocal.withInitial(ConcurrentHashMap::new);
    
    // 请求作用域实例存储
    private static final ConcurrentMap<String, Map<Class<?>, Object>> requestScopedInstances =
            new ConcurrentHashMap<>();
    
    // 当前配置
    private static volatile CreationConfig currentConfig = DEFAULT_CONFIG;
    
    // 统计信息
    private static final ConcurrentMap<Class<?>, Long> creationCount = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Long> accessCount = new ConcurrentHashMap<>();
    private static final AtomicLong totalCreations = new AtomicLong(0);
    private static final AtomicLong totalAccess = new AtomicLong(0);
    
    // 私有构造函数，防止实例化
    private PageObjectFactory() {
    }
    
    /**
     * 使用Builder模式构建配置
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 设置全局配置
     */
    public static void setConfig(CreationConfig config) {
        if (config != null) {
            currentConfig = config;
            logger.info("PageObjectFactory configuration updated: {}", config.getLifecycleStrategy());
        }
    }
    
    /**
     * 重置为默认配置
     */
    public static void resetConfig() {
        currentConfig = DEFAULT_CONFIG;
        logger.info("PageObjectFactory configuration reset to default");
    }
    
    /**
     * 获取PageObject实例（使用默认配置）
     * 
     * @param pageClass PageObject的Class对象
     * @return PageObject实例
     */
    public static <T> T getPage(Class<T> pageClass) {
        return getPage(pageClass, currentConfig);
    }
    
    /**
     * 获取PageObject实例（使用指定生命周期策略）
     * 
     * @param pageClass PageObject的Class对象
     * @param strategy 生命周期策略
     * @return PageObject实例
     */
    public static <T> T getPage(Class<T> pageClass, LifecycleStrategy strategy) {
        CreationConfig config = currentConfig;
        if (config.getLifecycleStrategy() != strategy) {
            // 临时使用指定策略
            config = new Builder()
                    .lifecycle(strategy)
                    .lazy(config.isLazy())
                    .build();
        }
        return getPage(pageClass, config);
    }
    
    /**
     * 获取PageObject实例（使用指定配置）
     * 
     * @param pageClass PageObject的Class对象
     * @param config 创建配置
     * @return PageObject实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getPage(Class<T> pageClass, CreationConfig config) {
        try {
            // 统计访问次数
            accessCount.merge(pageClass, 1L, Long::sum);
            totalAccess.incrementAndGet();
            
            // 根据生命周期策略获取实例
            Object instance = getInstanceByStrategy(pageClass, config);
            
            if (instance == null) {
                // 创建新实例
                instance = createInstance(pageClass, config);
                
                // 执行创建后钩子
                executePostCreateHooks(instance, config);
                
                logger.debug("Created and cached PageObject instance for: {} (strategy: {})", 
                        pageClass.getSimpleName(), config.getLifecycleStrategy());
            } else {
                logger.debug("Reusing cached PageObject instance for: {} (strategy: {})", 
                        pageClass.getSimpleName(), config.getLifecycleStrategy());
            }
            
            return (T) instance;
        } catch (Exception e) {
            logger.error("Failed to create PageObject instance for: {}", pageClass.getSimpleName(), e);
            throw new ConfigurationException("Failed to create PageObject: " + pageClass.getSimpleName(), e);
        }
    }
    
    /**
     * 根据生命周期策略获取实例
     */
    private static Object getInstanceByStrategy(Class<?> pageClass, CreationConfig config) {
        LifecycleStrategy strategy = config.getLifecycleStrategy();
        
        switch (strategy) {
            case SINGLETON:
                return singleInstances.get(pageClass);
                
            case PROTOTYPE:
                return null;  // 原型模式每次都创建新实例
                
            case THREAD_ISOLATED:
                return threadInstances.get().get(pageClass);
                
            case REQUEST_SCOPED:
                String requestId = getCurrentRequestId();
                Map<Class<?>, Object> requestMap = requestScopedInstances.get(requestId);
                return requestMap != null ? requestMap.get(pageClass) : null;
                
            default:
                return singleInstances.get(pageClass);
        }
    }
    
    /**
     * 创建新实例
     */
    private static Object createInstance(Class<?> pageClass, CreationConfig config) throws Exception {
        // 检查是否有自定义供应商
        Supplier<Object> customSupplier = config.getCustomSuppliers().get(pageClass);
        if (customSupplier != null) {
            Object instance = customSupplier.get();
            storeInstance(pageClass, instance, config);
            return instance;
        }
        
        // 使用反射创建实例
        Object instance = pageClass.getDeclaredConstructor().newInstance();
        
        // 存储实例
        storeInstance(pageClass, instance, config);
        
        // 统计创建次数
        creationCount.merge(pageClass, 1L, Long::sum);
        totalCreations.incrementAndGet();
        
        return instance;
    }
    
    /**
     * 存储实例到对应的存储中
     */
    private static void storeInstance(Class<?> pageClass, Object instance, CreationConfig config) {
        LifecycleStrategy strategy = config.getLifecycleStrategy();
        
        switch (strategy) {
            case SINGLETON:
                singleInstances.put(pageClass, instance);
                break;
                
            case PROTOTYPE:
                // 原型模式不缓存
                break;
                
            case THREAD_ISOLATED:
                threadInstances.get().put(pageClass, instance);
                break;
                
            case REQUEST_SCOPED:
                String requestId = getCurrentRequestId();
                Map<Class<?>, Object> requestMap = requestScopedInstances
                        .computeIfAbsent(requestId, k -> new ConcurrentHashMap<>());
                requestMap.put(pageClass, instance);
                break;
        }
    }
    
    /**
     * 执行创建后钩子
     */
    private static void executePostCreateHooks(Object instance, CreationConfig config) {
        for (Consumer<Object> hook : config.getPostCreateHooks()) {
            try {
                hook.accept(instance);
            } catch (Exception e) {
                logger.warn("Post-create hook execution failed", e);
            }
        }
    }
    
    /**
     * 获取当前请求ID（简化实现）
     */
    private static String getCurrentRequestId() {
        return Thread.currentThread().getName();
    }
    
    /**
     * 预热PageObject实例（测试开始前创建）
     * 
     * @param pageClasses 需要预热的PageObject类列表
     */
    @SafeVarargs
    public static void warmUp(Class<?>... pageClasses) {
        if (pageClasses == null || pageClasses.length == 0) {
            logger.debug("No PageObject classes to warm up");
            return;
        }
        
        logger.info("Warming up {} PageObject instances", pageClasses.length);
        
        for (Class<?> pageClass : pageClasses) {
            try {
                getPage(pageClass);
                logger.debug("Warmed up: {}", pageClass.getSimpleName());
            } catch (Exception e) {
                logger.warn("Failed to warm up: {}", pageClass.getSimpleName(), e);
            }
        }
        
        logger.info("PageObject warm-up completed. Total instances: {}", getInstanceCount());
    }
    
    /**
     * 开始新的请求作用域
     */
    public static void beginRequestScope() {
        String requestId = getCurrentRequestId();
        if (!requestScopedInstances.containsKey(requestId)) {
            requestScopedInstances.put(requestId, new ConcurrentHashMap<>());
            logger.debug("Started request scope for: {}", requestId);
        }
    }
    
    /**
     * 结束当前请求作用域
     */
    public static void endRequestScope() {
        String requestId = getCurrentRequestId();
        Map<Class<?>, Object> instances = requestScopedInstances.remove(requestId);
        if (instances != null) {
            logger.debug("Ended request scope for: {}, cleaned up {} instances", 
                    requestId, instances.size());
        }
    }
    
    /**
     * 清除所有PageObject实例
     * 通常在测试套件结束时调用
     */
    public static void clearAll() {
        int singletonCount = singleInstances.size();
        int threadCount = threadInstances.get().size();
        int requestCount = requestScopedInstances.size();
        
        singleInstances.clear();
        threadInstances.get().clear();
        requestScopedInstances.clear();
        
        logger.info("Cleared all PageObject instances: {} singletons, {} thread-isolated, {} request-scoped", 
                singletonCount, threadCount, requestCount);
    }
    
    /**
     * 清除指定类型的PageObject实例
     * 
     * @param pageClass PageObject的Class对象
     */
    public static void clear(Class<?> pageClass) {
        int removed = 0;
        if (singleInstances.remove(pageClass) != null) removed++;
        if (threadInstances.get().remove(pageClass) != null) removed++;
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            if (requestMap.remove(pageClass) != null) removed++;
        }
        
        logger.debug("Cleared PageObject instance for: {} (removed {} instances)", 
                pageClass.getSimpleName(), removed);
    }
    
    /**
     * 检查指定类型的PageObject实例是否存在
     * 
     * @param pageClass PageObject的Class对象
     * @return 如果实例存在返回true，否则返回false
     */
    public static boolean hasInstance(Class<?> pageClass) {
        if (singleInstances.containsKey(pageClass)) return true;
        if (threadInstances.get().containsKey(pageClass)) return true;
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            if (requestMap.containsKey(pageClass)) return true;
        }
        
        return false;
    }
    
    /**
     * 获取当前缓存的PageObject实例数量
     * 
     * @return 实例数量
     */
    public static int getInstanceCount() {
        int count = singleInstances.size() + threadInstances.get().size();
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            count += requestMap.size();
        }
        return count;
    }
    
    /**
     * 获取统计信息
     * 
     * @return 统计信息字符串
     */
    public static String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== PageObjectFactory Statistics ===\n");
        sb.append(String.format("Total Creations: %d\n", totalCreations.get()));
        sb.append(String.format("Total Access: %d\n", totalAccess.get()));
        sb.append(String.format("Singleton Instances: %d\n", singleInstances.size()));
        sb.append(String.format("Thread-Isolated Instances: %d\n", threadInstances.get().size()));
        sb.append(String.format("Request-Scoped Instances: %d\n", requestScopedInstances.size()));
        sb.append("\nCreation Count by Class:\n");
        
        creationCount.entrySet().stream()
                .sorted(Map.Entry.<Class<?>, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    sb.append(String.format("  %s: %d creations, %d accesses\n",
                            entry.getKey().getSimpleName(),
                            entry.getValue(),
                            accessCount.getOrDefault(entry.getKey(), 0L)));
                });
        
        return sb.toString();
    }
    
    /**
     * 获取所有已注册的PageObject类
     * 
     * @return PageObject类集合
     */
    public static Set<Class<?>> getRegisteredPageClasses() {
        Set<Class<?>> classes = new HashSet<>();
        classes.addAll(singleInstances.keySet());
        classes.addAll(threadInstances.get().keySet());
        
        for (Map<Class<?>, Object> requestMap : requestScopedInstances.values()) {
            classes.addAll(requestMap.keySet());
        }
        
        return classes;
    }
    
    /**
     * 重置所有统计信息
     */
    public static void resetStatistics() {
        creationCount.clear();
        accessCount.clear();
        totalCreations.set(0);
        totalAccess.set(0);
        logger.info("PageObjectFactory statistics reset");
    }
}

