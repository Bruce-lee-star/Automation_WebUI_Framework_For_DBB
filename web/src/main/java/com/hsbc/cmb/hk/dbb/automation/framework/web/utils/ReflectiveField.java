package com.hsbc.cmb.hk.dbb.automation.framework.web.utils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

/**
 * JPMS（Java 模块系统）安全的私有字段读写工具，用于消除 {@code field.setAccessible(true)}
 * 在强封装（JDK 17+）下可能抛 {@code InaccessibleObjectException} 的风险（G-8 修复）。
 *
 * <p>优先路径：{@link MethodHandles#privateLookupIn} + {@link VarHandle} 直接读写字段，
 * 不触碰 {@code setAccessible}，对模块系统透明。当目标类位于<b>命名模块</b>且未对该包开放
 * （即未配置 {@code --add-opens}）时，{@code privateLookupIn} 会失败，此时回退到
 * {@code setAccessible} + {@code Field#set/#get}（未命名模块 / classpath 下始终可用），
 * 保证行为一致并提供清晰报错。
 */
public final class ReflectiveField {

    private ReflectiveField() {
    }

    /** 写入字段值（自动处理私有访问，无需调用方 {@code setAccessible}）。 */
    public static void set(Field field, Object owner, Object value) {
        VarHandle vh = tryVarHandle(field);
        if (vh != null) {
            vh.set(owner, value);
            return;
        }
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法写入字段 " + describe(field)
                    + "（JPMS 强封装：需为包 " + field.getDeclaringClass().getPackageName()
                    + " 配置 --add-opens）", e);
        }
    }

    /** 读取字段值（自动处理私有访问，无需调用方 {@code setAccessible}）。 */
    public static Object get(Field field, Object owner) {
        VarHandle vh = tryVarHandle(field);
        if (vh != null) {
            return vh.get(owner);
        }
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法读取字段 " + describe(field)
                    + "（JPMS 强封装：需为包 " + field.getDeclaringClass().getPackageName()
                    + " 配置 --add-opens）", e);
        }
    }

    /**
     * 尝试用 {@code privateLookupIn} + {@code VarHandle} 取得字段句柄。
     * 任何不支持场景（命名模块未开放 / 校验失败）统一返回 {@code null}，交由回退路径处理。
     */
    private static VarHandle tryVarHandle(Field field) {
        try {
            return MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup())
                    .findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(Field field) {
        return field.getDeclaringClass().getName() + "#" + field.getName();
    }
}
