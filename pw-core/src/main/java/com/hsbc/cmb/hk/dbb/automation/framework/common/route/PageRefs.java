package com.hsbc.cmb.hk.dbb.automation.framework.common.route;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Page 引用的「身份归一」工具（核心层，零 Playwright / web 依赖）。
 *
 * <p><b>为什么需要它（缺陷根因）</b>：route 的 PAGE 级规则按<b>对象同一性</b>筛选适用页面
 * （{@code pageRef == reqPage}）。但业务取页路径可能返回<b>装饰代理</b>
 * （典型：web 的 {@code RecordingPageProxy} —— JDK 动态代理，用于录制原生操作），
 * 而 dispatch 侧的 {@code reqPage} 取自 {@code route.request().frame().page()}，是<b>原生 Page</b>。
 * 二者指向同一实际页面却非同一对象 → 规则被判「不适用本页」→ 请求被放行到真实后端
 * （表现为 MOCK / MODIFY / DELAY 全部静默失效）。
 *
 * <p>本类把「装饰代理 → 底层真实对象」的归一逻辑下沉到核心层，使 route 侧可在<b>不依赖 web 模块</b>
 * 的前提下正确比较页面身份（同时满足 ArchUnit 分层约束）。
 *
 * <p>归一约定：若对象是 JDK 动态代理，则尝试其 {@link InvocationHandler} 上的
 * {@code target() / getTarget() / unwrap() / getUnwrap() / delegate() / getDelegate()} 访问器
 * （任一可用即可），并递归解开多层代理（上限 8 层，防御异常代理链）。
 *
 * @apiNote framework-internal：框架内部类型，非公开 API。
 */
public final class PageRefs {

    private static final Logger LOGGER = LoggerFactory.getLogger(PageRefs.class);

    /** 装饰器暴露真实对象的方法名约定（按序尝试）。 */
    private static final String[] TARGET_ACCESSORS = {
            "target", "getTarget", "unwrap", "getUnwrap", "delegate", "getDelegate"
    };

    /** 解开层数上限（防御异常代理链导致无限递归）。 */
    private static final int MAX_UNWRAP_DEPTH = 8;

    private PageRefs() {
    }

    /**
     * 解开 JDK 动态代理装饰器，返回底层真实对象。
     *
     * <p>非代理、或代理未暴露可识别访问器时<b>原样返回</b>（零行为变更的保守降级）。
     *
     * @param ref 任意引用（可为 {@code null}）
     * @return 底层真实对象；无法解开时为入参本身
     */
    public static Object unwrap(Object ref) {
        Object current = ref;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH && current != null; depth++) {
            if (!Proxy.isProxyClass(current.getClass())) {
                return current;
            }
            Object next = readTarget(Proxy.getInvocationHandler(current));
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * 两个 Page 引用是否指向<b>同一个真实页面</b>。
     *
     * <p>先按对象同一性快速判定（零开销），不相等时再解开装饰代理比较底层对象。
     *
     * @param a 引用 A
     * @param b 引用 B
     * @return true 表示指向同一真实页面
     */
    public static boolean isSamePage(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return unwrap(a) == unwrap(b);
    }

    /** 从 InvocationHandler 读取被委托的真实对象（按约定方法名逐个尝试）。 */
    private static Object readTarget(InvocationHandler handler) {
        if (handler == null) {
            return null;
        }
        for (String accessor : TARGET_ACCESSORS) {
            try {
                Method method = handler.getClass().getMethod(accessor);
                // handler 实现类可能是包私有/私有的（如 RecordingHandler）→ 必须放开可访问性
                method.setAccessible(true);
                Object target = method.invoke(handler);
                if (target != null) {
                    return target;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 该访问器不存在、不可访问或调用失败 → 尝试下一个（保守降级，不影响业务）
                LOGGER.debug("[PageRefs] accessor {} failed, trying next: {}",
                        accessor, ignored.toString());
            }
        }
        return null;
    }
}
