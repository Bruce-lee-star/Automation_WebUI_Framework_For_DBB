package com.hsbc.cmb.hk.dbb.automation.framework.web.page;

/**
 * 框架中立的矩形描述，用于在 public API 中替代 Playwright {@code BoundingBox}。
 * <p>
 * 这是「收回 Playwright 类型出 public API」策略的一部分：业务代码只看到本类的
 * {@code x / y / width / height}，永远不会接触到 {@code com.microsoft.playwright.options.BoundingBox}。
 * 作为 {@link PageElement} 的同伴值类型，与 {@code PageElement} 同包，不再寄居已退役的 driver 包。
 */
public final class ElementRect {

    private final double x;
    private final double y;
    private final double width;
    private final double height;

    public ElementRect(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return String.format("ElementRect[x=%.1f y=%.1f w=%.1f h=%.1f]", x, y, width, height);
    }
}
