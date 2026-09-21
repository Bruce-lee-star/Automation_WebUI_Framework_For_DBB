package com.hsbc.cmb.hk.dbb.automation.framework.web.accessibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 框架自有无障碍规则结果模型，替代 {@code com.deque.html.axecore.results.Rule}。
 * <p>
 * 由 {@link AxeCoreScriptProvider} 从 {@code axe.run} 返回的 JSON 映射而来，使结果模型与
 * Deque axe-core 的 Java 包装器版本完全解耦（axe-core 版本改由自带 {@code axe.min.js} 独立管理）。
 * 不可变（线程安全）：字段经构造注入，集合视图不可修改。
 */
public final class AxeRule {

    private final String id;
    private final String impact;
    private final String description;
    private final String help;
    private final String helpUrl;
    private final List<AxeNode> nodes;

    public AxeRule(String id, String impact, String description, String help, String helpUrl, List<AxeNode> nodes) {
        this.id = id;
        this.impact = impact;
        this.description = description;
        this.help = help;
        this.helpUrl = helpUrl;
        this.nodes = nodes != null ? Collections.unmodifiableList(new ArrayList<>(nodes)) : Collections.emptyList();
    }

    public String getId() { return id; }

    public String getImpact() { return impact; }

    public String getDescription() { return description; }

    public String getHelp() { return help; }

    public String getHelpUrl() { return helpUrl; }

    public List<AxeNode> getNodes() { return nodes; }
}
