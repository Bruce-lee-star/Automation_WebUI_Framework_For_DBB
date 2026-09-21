package com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * B-6：声明「该类所在用例需要保持洁净的状态」——即该类会注册哪些 {@link StateResolver}。
 *
 * <p>用途：把隔离需求从「隐式、散落在各 glue 的 {@code @Before}」变为「显式、可检索的声明」，
 * 便于评审与自动化检查「新增 glue 是否遗漏隔离声明」。运行时驱动仍由
 * {@link CleanStateRegistry} + 框架钩子承担（注解本身不触发行为）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresCleanState {

    /** 需要洁净的状态名（对应 {@link StateResolver#name()}）。 */
    String[] value();
}
