package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 框架自有无障碍规则命中的节点模型，替代 {@code com.deque.html.axecore.results.CheckedNode}。
 * <p>
 * 由 {@link AxeCoreScriptProvider} 从 {@code axe.run} 返回的 JSON 映射而来。不可变（线程安全）。
 */
public final class AxeNode {

    private final List<String> target;
    private final String failureSummary;
    private final String html;

    public AxeNode(List<String> target, String failureSummary, String html) {
        this.target = target != null ? Collections.unmodifiableList(new ArrayList<>(target)) : Collections.emptyList();
        this.failureSummary = failureSummary;
        this.html = html;
    }

    public List<String> getTarget() { return target; }

    public String getFailureSummary() { return failureSummary; }

    public String getHtml() { return html; }
}
