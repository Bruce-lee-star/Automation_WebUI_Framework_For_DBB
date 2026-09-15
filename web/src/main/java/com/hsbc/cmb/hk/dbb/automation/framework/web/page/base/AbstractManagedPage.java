package com.hsbc.cmb.hk.dbb.automation.framework.web.page.base;

import com.microsoft.playwright.Page;

import java.util.function.Supplier;

/**
 * 组合式 Page Object 的轻量基类（G1，doc15 §1 G1）。
 *
 * <p><b>为什么需要它：</b>业务 Page 退化为组合式（不再继承 110 方法的 {@code BasePage} 上帝基类），
 * 但又需复用 Layer B 全部能力 + {@code @Element}/{@code @RoleElement} 字段绑定。本类把这两件事
 * 收口到框架层、只写一次：持有内部 {@link BasePage} 委托（页面上下文宿主），并在
 * {@link #setManagedPage(Supplier)} 中完成装饰 Page 注入与注解字段绑定。业务页
 * {@code extends AbstractManagedPage} 后，继承面上只有本类的 3 个方法（构造/setManagedPage/getPage），
 * 而非 110——真正消解了 Layer B 的继承面。
 *
 * <p><b>组合而非继承上帝类：</b>本类自身不继承 {@code BasePage}，仅<b>持有</b>一个 {@code BasePage}
 * 实例作为委托；所有 Layer B 方法经 {@link SerenityBasePage}（实现收敛于 {@link SerenityBasePageImpl}）转发到该委托，录制、
 * iframe/shadow 路由、可观测性、错误处理与原「继承 BasePage」路径逐字一致（零新逻辑）。
 * 这消除了旧设计中 {@code AbstractManagedPage} 对 {@link SerenityBasePageImpl} 的约 80 行逐行重复转发。
 *
 * <p><b>原生操作（Layer A）：</b>业务页经 {@link #getPage()} 拿到 {@code RecordingPageProxy} 装饰的
 * Page，直接调用 {@code click/fill/navigate/...} 即由代理自动录制，不经本类。
 *
 * @apiNote 业务 Page 应 {@code extends AbstractManagedPage}（即 {@code implements ManagedPageAware, SerenityBasePage}）。
 *       本类是框架内部组合基，非业务应扩展的"能力基类"。
 */
public abstract class AbstractManagedPage extends SerenityBasePageImpl
        implements ManagedPageAware, SerenityBasePage {

    /** 内部委托：页面上下文宿主（iframe/shadow/ensure/@Element 绑定均落在此实例）。 */
    private final BasePage page;

    /** 框架管理的（已装饰录制）Page 供应商，由 {@link #setManagedPage} 注入。 */
    private Supplier<Page> managedPage;

    public AbstractManagedPage() {
        this(new BasePage() {});
    }

    /**
     * 承接委托实例的私有构造器：Java 不允许在 {@code super()} 实参中引用实例字段，
     * 故用本构造器把同一 {@code BasePage} 实例同时传给 {@code super} 并赋给 {@link #page}。
     */
    private AbstractManagedPage(BasePage delegate) {
        super(delegate);
        this.page = delegate;
    }

    @Override
    public void setManagedPage(Supplier<Page> managedPage) {
        this.managedPage = managedPage;
        page.attachManagedPage(managedPage.get());
        BasePage.bindAnnotatedFields(this, page);
    }

    /**
     * 返回框架管理的装饰 Page（Layer A 录制由 {@code RecordingPageProxy} 自动完成）。
     * 每次调用实时解析，页面切换后自动指向新 Page。保留 override 以与原语义逐字一致
     * （直接返回惰性供应商结果，不触发 {@code ensurePageValid}）。
     */
    @Override
    public Page getPage() {
        return managedPage.get();
    }
}
