package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据「多次拾取 = 多个 step」的序列生成 Serenity {@code @Step} 业务步骤类源码（第二步）。
 *
 * <p>与 {@link RoleElementPageGenerator} 共用字段命名（{@link RoleElementPageGenerator#assignFields}），
 * 保证 Tab1（页面元素）与 Tab2（步骤代码）引用的是同一组字段名。每次「开始拾取 → 停止拾取」
 * 对应一个 step：step 内的元素按顺序生成操作调用（按 role 自动推断：button/link→click、
 * textbox→type、checkbox/radio→check，其余→click），一个 step 生成一个 {@code @Step} 方法。
 *
 * <p>产物为草稿，人工 review 后再合入主干。
 */
public final class RoleElementStepGenerator {

    private RoleElementStepGenerator() {}

    /** 按角色 / 策略推断元素操作（自动推断，无需用户手动选择）。 */
    private static String operationFor(RoleEntry e) {
        // 悬停（hover）交互优先：录制为 locator.hover()，对齐 page.pause() 对 hover 动作的录制。
        if (e.isHover()) {
            return "hover()";
        }
        // 双击（dblclick）：录制为 locator.doubleClick()，对齐 page.pause() 对 doubleClick 动作的录制。
        // 优先于角色推断——双击语义由用户交互决定，不因元素角色（link/button 等）退化为单击。
        if (e.isDblClick()) {
            return "doubleClick()";
        }
        if (e.isRoleStrategy()) {
            String r = (e.getRole() == null ? "" : e.getRole()).toLowerCase();
            switch (r) {
                case "textbox":
                case "searchbox":
                case "spinbutton":
                    return "type(\"" + escapeJava(e.getValue()) + "\")";
                case "checkbox":
                case "radio":
                    // 复选框/单选：按录制时实际勾选状态选择 check()/uncheck()（对齐 page.pause() 的 check/uncheck 信号）。
                    // checked 为 null 时（非复选框未捕获）保守走默认 check()。
                    if (e.getChecked() != null) {
                        return e.getChecked() ? "check()" : "uncheck()";
                    }
                    return "check()";
                case "combobox":
                case "listbox":
                    // 下拉选择（含原生 <select> 与自定义列表）：录制时拿到了选中项，
                    // 对齐 page.pause() 的 selectOption 信号，生成 selectByVisibleText("选项文本")。
                    // 未捕获到选中项时（仅点击展开未选）降级为 click()，由人工补充具体选项。
                    if (e.isSelect() && e.getOptionText() != null) {
                        return "selectByVisibleText(\"" + escapeJava(e.getOptionText()) + "\")";
                    }
                    return "click()";
                default:
                    return "click()";   // button / link / tab / menuitem / switch ...
            }
        }
        // 非角色策略：placeholder 通常是输入框；其余（text/title/id/css 等，含 data-i18n 走 @Element 的 css 选择器）默认 click
        if ("placeholder".equals(e.getStrategy())) {
            return "type(\"" + escapeJava(e.getValue()) + "\")";
        }
        return "click()";
    }

    /** 转义 Java 字符串字面量中的特殊字符（\" \\ \n \r \t），null 视为空串。 */
    private static String escapeJava(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * 生成 Step 类源码。
     *
     * @param steps        每个内层 List 是一次「开始 → 停止」拾取出的元素（一个 step）
     * @param allEntries   所有 step 的并集（去重后对应页面字段），用于字段命名
     * @param packageName  生成页类的包名（step 类放在其 {@code .steps} 子包下）
     * @param pageClassName 页类名（step 类通过 {@code PageObjectFactory.getPage} 引用）
     * @param stepClassName step 类名
     * @return 完整 Java 类源码
     */
    public static String generate(List<List<RoleEntry>> steps, List<RoleEntry> allEntries,
                                  String packageName, String pageClassName, String stepClassName) {
        List<RoleElementPageGenerator.GeneratedField> specs =
                RoleElementPageGenerator.assignFields(allEntries);
        Map<String, String> keyToField = new LinkedHashMap<>();
        for (RoleElementPageGenerator.GeneratedField s : specs) {
            keyToField.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
        }

        String pageVar = decapitalize(pageClassName);
        String stepPkg = packageName + ".steps";

        StringBuilder methods = new StringBuilder();
        int[] npIdx = {0};   // 弹窗触发生成的新页面变量名计数器（newPage / newPage2 ...）
        if (steps == null || steps.isEmpty()) {
            methods.append("    // 还没有任何 step：请在面板点「开始拾取」→ 点击元素 → 「停止拾取」生成一个 step\n");
        } else {
            int stepIdx = 0;
            for (List<RoleEntry> step : steps) {
                stepIdx++;
                boolean sawPopup = false;
                methods.append("    @Step\n");
                methods.append("    public void step").append(stepIdx).append("() {\n");
                if (step == null || step.isEmpty()) {
                    methods.append("        // 该 step 未拾取任何元素\n");
                } else {
                    for (RoleEntry e : step) {
                        String field = keyToField.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;   // 不在页面字段中（理论上不会发生）
                        // 目标表达式：页字段（+ 一组元素时的 .nth(index) 消歧）
                        String target = pageVar + "." + field;
                        if (e.getIndex() >= 0) {
                            target += ".nth(" + e.getIndex() + ")";
                        }
                        // 原生对话框（alert/confirm/prompt）：前置插桩，对齐 page.pause() 的
                        // onceDialog 前置信号——必须在触发它的动作之前挂载监听，否则默认 dismiss 会让
                        // confirm 流程异常。走框架封装 acceptAlert()/dismissAlert()（内部 page.onceDialog）。
                        // 方案1：alert 默认 accept；confirm/prompt 默认 dismiss。
                        if (e.isDialog()) {
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert()" : "dismissAlert()";
                            methods.append("        ").append(pageVar).append(".").append(dlgMethod)
                                    .append("; // 处理原生对话框(").append(e.getDialogType()).append(")\n");
                        }
                        String op = target + "." + operationFor(e);
                        if (e.isDownload()) {
                            // 下载（anchor download 属性 / 文件 URL / JS 触发）：用框架封装的
                            // waitForDownload(trigger, timeoutSecs) 等待下载完成，对齐 page.pause()
                            // 的 waitForDownload 录制。与弹窗同时发生时嵌套：waitForDownload(switchToNewPage)。
                            if (e.isPopup()) {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() -> {\n")
                                        .append("            ").append(pageVar).append(".switchToNewPage(() ->\n")
                                        .append("                    ").append(op).append(", 15);\n")
                                        .append("        });\n");
                            } else {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() ->\n")
                                        .append("                ").append(op).append(");\n");
                            }
                        } else if (e.isPopup()) {
                            // 弹窗链接（target=_blank）：用框架封装的 switchToNewPage(trigger, timeoutSecs)
                            // 一步完成“点击 + 等待新页面 + 切换页对象上下文”，并把新页面赋给 Page 变量，
                            // 后续操作在新页面执行；step 末统一“切回默认页”，使上下文回到默认 page（对齐用户预期）。
                            // 对齐 page.pause() 的 waitForPopup 语义，但走框架原生 API（无需手写 Playwright）。
                            String npVar = nextNewPageVar(npIdx);
                            sawPopup = true;
                            methods.append("        Page ").append(npVar).append(" = ").append(pageVar)
                                    .append(".switchToNewPage(() ->\n")
                                    .append("                ").append(op).append(", 15);\n");
                        } else {
                            methods.append("        ").append(op).append(";\n");
                        }
                    }
                }
                if (sawPopup) {
                    // 本 step 触发过弹窗并切到了新页面：关闭弹窗页，由 BasePage 的 page.onClose 自动切回默认页
                    // （复用录制层 RoleElementPicker 语义，不再手写 switchToPage(0)）。
                    methods.append("        ").append(pageVar).append(".closeCurrentPage(); // 关闭弹窗页，onClose 自动切回默认页\n");
                }
                methods.append("    }\n\n");
            }
        }

        return "package " + stepPkg + ";\n\n"
                + "import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n"
                + "import " + packageName + "." + pageClassName + ";\n"
                + "import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n"
                + "public class " + stepClassName + " {\n\n"
                + "    private " + pageClassName + " " + pageVar
                + " = PageObjectFactory.getPage(" + pageClassName + ".class);\n\n"
                + methods
                + "}\n";
    }

    /**
     * 多页面生成：每个被跟踪页面各生成一段 {@code @Step} 方法，并各自声明对应的 Page 字段。
     * stepsByPage 的 key 为页面类名、value 为该页的 step 列表（每个内层 List 是一次 start→stop）；
     * entriesByPage 的 key 为页面类名、value 为该页的全部字段来源（与对应 Page 类一致）。
     * 最终合并为一个 Step 类：每个页面一个 Page 字段，step 方法用对应页字段引用元素
     * （对齐多页面拾取：元素落到对应页代码、steps 也调用对应页）。
     *
     * @param stepsByPage   页面类名 → 该页的 step 列表
     * @param entriesByPage 页面类名 → 该页的字段来源
     * @param packageName   页类包名（step 类放在其 {@code .steps} 子包下）
     * @param stepClassName step 类名
     * @return 完整 Java 类源码
     */
    /**
     * 多页面生成（含页面级操作，如关闭页面）。
     *
     * @param opsByPage 页面类名 → 该页的页面级操作列表（如 {@code ["close"]}），每个操作生成独立 @Step 方法
     */
    public static String generateMulti(LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
                                        LinkedHashMap<String, List<RoleEntry>> entriesByPage,
                                        LinkedHashMap<String, List<String>> opsByPage,
                                        String packageName, String stepClassName) {
        if (opsByPage == null) opsByPage = new LinkedHashMap<>();
        // 收集所有涉及到的页面类：元素字段来源（entriesByPage）+ 各 step 内 pick 的归属页
        // + 关闭操作标记页（opsByPage / close 标记）。保证每被引用的 Page 类都有字段声明，
        // 即使它没有任何元素 pick（例如仅做了“关闭”操作的弹窗页）。
        LinkedHashSet<String> allPages = new LinkedHashSet<>();
        allPages.addAll(entriesByPage.keySet());
        allPages.addAll(opsByPage.keySet());
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            allPages.add(en.getKey());
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty())
                            ? en.getKey() : e.getPageClass();
                    allPages.add(pc);
                }
            }
        }
        // 合并“仅含关闭操作”的 step 进上一个 step：关闭当前页（含整页跳转后关闭根页）不另成 @Step 方法，
        // 使一次“开始→停止 / 封装为步骤”始终落在同一个 step 内。
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            en.setValue(mergeCloseOnlySteps(en.getValue()));
        }

        // 每个页面类 -> (定位键 -> 字段名)，并生成该页字段声明。
        // 字段命名来源取该页已拾取元素（entriesByPage 已由 __rolePicks 覆盖全部 pick），与 Tab1 页面生成保持一致。
        Map<String, Map<String, String>> pageFields = new LinkedHashMap<>();
        Map<String, String> pageVar = new LinkedHashMap<>();     // pageClass -> 唯一变量名
        StringBuilder fields = new StringBuilder();
        Set<String> usedVars = new HashSet<>();
        for (String className : allPages) {
            List<RoleEntry> pageEntries = entriesByPage.get(className);
            if (pageEntries == null) pageEntries = new ArrayList<>();
            List<RoleElementPageGenerator.GeneratedField> specs = RoleElementPageGenerator.assignFields(pageEntries);
            Map<String, String> keyToField = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField s : specs) {
                keyToField.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
            }
            pageFields.put(className, keyToField);

            String base = decapitalize(className);
            String uniqueVar = base;
            int v = 2;
            while (usedVars.contains(uniqueVar)) uniqueVar = base + (v++);
            usedVars.add(uniqueVar);
            pageVar.put(className, uniqueVar);

            fields.append("    private ").append(className).append(" ").append(uniqueVar)
                    .append(" = PageObjectFactory.getPage(").append(className).append(".class);\n\n");
        }

        // 渲染 step 方法：一个 start→stop = 一个 @Step 方法（唯一的 step 边界）。
        // 方法内每个 pick 按其“自身 _pageClass”选取对应页变量与字段——跨页拾取（默认页 + 弹窗页）
        // 因此落在同一 step 内，不再被弹窗打开/关闭拆成多个 step（用户明确要求“只有一个条件：开始-停止”）。
        // 关闭页面标记（_closeOp）内联渲染为 closeCurrentPage()，同样属于该 step。
        StringBuilder methods = new StringBuilder();
        int[] npIdx = {0};
        int stepIdx = 0;
        boolean any = false;
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            String stepPageClass = en.getKey();
            for (List<RoleEntry> step : en.getValue()) {
                stepIdx++;
                any = true;
                StringBuilder m = new StringBuilder();
                m.append("    @Step\n");
                m.append("    public void step").append(stepIdx).append("() {\n");
                boolean sawPopup = false;
                String popupTargetVar = null;   // 弹窗目标页对象变量：交由它接管并在其上 closeCurrentPage
                if (step == null || step.isEmpty()) {
                    m.append("        // 该 step 未拾取任何元素\n");
                } else {
                    for (RoleEntry e : step) {
                        String pc = (e.getPageClass() == null || e.getPageClass().isEmpty())
                                ? stepPageClass : e.getPageClass();
                        String var = pageVar.get(pc);
                        if (var == null) var = pageVar.get(stepPageClass);
                        if (var == null) continue;
                        if (e.isCloseOp()) {
                            // 关闭当前页（弹窗）：仅在“本 step 没有对应弹窗打开”（如同标签整页跳转后直接关闭根页）
                            // 时由此渲染；若本 step 含弹窗打开（sawPopup），关闭已由下方基于“打开页”统一兜底，
                            // 避免重复生成且避免在错误页变量上调用 closeCurrentPage（弹窗页实例引用并非被关的弹窗）。
                            if (sawPopup) continue;
                            m.append("        ").append(var)
                                    .append(".closeCurrentPage(); // 关闭当前页（弹窗），onClose 自动切回默认页\n");
                            continue;
                        }
                        Map<String, String> kf = pageFields.get(pc);
                        String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;
                        String target = var + "." + field;
                        if (e.getIndex() >= 0) target += ".nth(" + e.getIndex() + ")";
                        // 原生对话框（alert/confirm/prompt）：前置插桩，对齐 page.pause() 的
                        // onceDialog 前置信号——必须在触发它的动作之前挂载监听，否则默认 dismiss 会让
                        // confirm 流程异常。走框架封装 acceptAlert()/dismissAlert()（内部 page.onceDialog）。
                        // 方案1：alert 默认 accept；confirm/prompt 默认 dismiss。
                        if (e.isDialog()) {
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert()" : "dismissAlert()";
                            methods.append("        ").append(pageVar).append(".").append(dlgMethod)
                                    .append("; // 处理原生对话框(").append(e.getDialogType()).append(")\n");
                        }
                        String op = target + "." + operationFor(e);
                        if (e.isDownload()) {
                            if (e.isPopup()) {
                            sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                            m.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                    .append("            ").append(var).append(".switchToNewPage(() ->\n")
                                    .append("                    ").append(op).append(", 15);\n")
                                    .append("        });\n");
                            } else {
                                m.append("        ").append(var).append(".waitForDownload(() ->\n")
                                        .append("                ").append(op).append(");\n");
                            }
                        } else if (e.isPopup()) {
                            // 弹窗链接（target=_blank）：用框架封装的 switchToNewPage 切换页对象上下文，
                            // 并把新页面赋给 Page 变量（保留引用）；再把新页面绑到“目标页对象”
                            // （如 privacyAndSecurityPage），使后续操作/关闭都落在目标页对象而非打开页。
                            sawPopup = true;
                            String npVar = nextNewPageVar(npIdx);
                            m.append("        Page ").append(npVar).append(" = ").append(var)
                                    .append(".switchToNewPage(() ->\n")
                                    .append("                ").append(op).append(", 15);\n");
                            String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                            if (popupTarget != null) {
                                m.append("        ").append(popupTarget).append(".switchToPage(").append(npVar)
                                        .append("); // 新页面交由 ").append(popupTarget).append(" 接管\n");
                                if (popupTargetVar == null) popupTargetVar = popupTarget;
                            } else if (popupTargetVar == null) {
                                popupTargetVar = var;
                            }
                        } else {
                            m.append("        ").append(op).append(";\n");
                        }
                    }
                }
                // 弹窗打开（sawPopup）：无论其 onClose 是否成功把 _closeOp 推回父页快照，都基于“打开页”统一补
                // closeCurrentPage，保证“打开弹窗→关闭弹窗”闭环生成（修复：onClose 未捕获关闭时关闭步骤丢失）。
                // switchToNewPage 后打开页实例的当前页引用即弹窗，故在打开页上调用 closeCurrentPage 语义正确。
                if (sawPopup && popupTargetVar != null) {
                    m.append("        ").append(popupTargetVar)
                            .append(".closeCurrentPage(); // 关闭弹窗页（").append(popupTargetVar)
                            .append(" 接管），onClose 自动切回默认页\n");
                }
                m.append("    }\n\n");
                methods.append(m);
            }
        }
        if (!any) {
            methods.append("    // 还没有任何 step：请在面板点「开始拾取」→ 点击元素 → 「停止拾取」生成一个 step\n");
        }
        // import：每个页面类 + PageObjectFactory
        StringBuilder imports = new StringBuilder("import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n");
        for (String className : allPages) {
            imports.append("import ").append(packageName).append(".").append(className).append(";\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n");
        return "package " + packageName + ".steps;\n\n"
                + imports
                + "public class " + stepClassName + " {\n\n"
                + fields
                + methods
                + "}\n";
    }

    /**
     * 按页视图生成 Step 代码：每个被跟踪页面各生成一份“完整可编译”的 Step 类视图
     * （类外壳 + 全部页字段声明 + 全部 import + 仅该页归属的 step/ops 方法）。
     * 与 {@link #generateMulti} 区别：不合并成一个类，而是按页各出一份，用于面板“步骤代码”Tab 按页分栏对照
     * （对齐“页面元素”Tab 按 pageClass 分栏的布局）。
     * 单页场景即唯一一份完整类；多页场景主页视图含跨页 step（引用弹窗页字段），弹窗页视图含其 close 操作。
     *
     * @return 页面类名 → 该页视角的完整 Step 类源码（LinkedHashMap 保序）
     */
    public static LinkedHashMap<String, String> generatePerPage(
            LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
            LinkedHashMap<String, List<RoleEntry>> entriesByPage,
            LinkedHashMap<String, List<String>> opsByPage,
            String packageName, String stepClassName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (opsByPage == null) opsByPage = new LinkedHashMap<>();
        // 收集全部涉及页面（同 generateMulti：字段来源 + 各 step 内 pick 归属页 + 关闭操作标记页）
        LinkedHashSet<String> allPages = new LinkedHashSet<>();
        allPages.addAll(entriesByPage.keySet());
        allPages.addAll(opsByPage.keySet());
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            allPages.add(en.getKey());
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? en.getKey() : e.getPageClass();
                    allPages.add(pc);
                }
            }
        }
        if (allPages.isEmpty()) return out;
        // 合并“仅含关闭操作”的 step 进上一个 step，保持单次封装/拾取 = 一个 step（关闭当前页内联其中）。
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            en.setValue(mergeCloseOnlySteps(en.getValue()));
        }

        // 字段声明 + 变量名（全页共享，保证每份视图可独立编译——引用其它页字段时声明已存在）
        Map<String, Map<String, String>> pageFields = new LinkedHashMap<>();
        Map<String, String> pageVar = new LinkedHashMap<>();
        StringBuilder fields = new StringBuilder();
        Set<String> usedVars = new HashSet<>();
        for (String className : allPages) {
            List<RoleEntry> pageEntries = entriesByPage.get(className);
            if (pageEntries == null) pageEntries = new ArrayList<>();
            List<RoleElementPageGenerator.GeneratedField> specs = RoleElementPageGenerator.assignFields(pageEntries);
            Map<String, String> keyToField = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField s : specs) {
                keyToField.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
            }
            pageFields.put(className, keyToField);
            String base = decapitalize(className);
            String uniqueVar = base;
            int v = 2;
            while (usedVars.contains(uniqueVar)) uniqueVar = base + (v++);
            usedVars.add(uniqueVar);
            pageVar.put(className, uniqueVar);
            fields.append("    private ").append(className).append(" ").append(uniqueVar)
                    .append(" = PageObjectFactory.getPage(").append(className).append(".class);\n\n");
        }

        // import（全页）
        StringBuilder imports = new StringBuilder("import net.serenitybdd.annotations.Step;\n"
                + "import com.microsoft.playwright.Page;\n\n");
        for (String className : allPages) {
            imports.append("import ").append(packageName).append(".").append(className).append(";\n");
        }
        imports.append("import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;\n\n");
        // 预计算：哪些页是弹窗的目标页（其关闭已内联到“打开弹窗的 step”里），
        // 避免下方按页视图再生成独立的 close 方法（重复且独立方法拿不到新页面引用）。
        Set<String> inlinedTargetVars = new LinkedHashSet<>();
        for (List<List<RoleEntry>> steps : stepsByPage.values()) {
            if (steps == null) continue;
            for (List<RoleEntry> st : steps) {
                if (st == null) continue;
                for (RoleEntry e : st) {
                    if (!e.isPopup()) continue;
                    String pc0 = e.getPageClass();
                    String openVar = (pc0 == null || pc0.isEmpty()) ? "" : pageVar.getOrDefault(pc0, "");
                    String t = inferPopupTargetVar(st, openVar, pageVar, opsByPage);
                    if (t != null) inlinedTargetVars.add(t);
                }
            }
        }

        String clsHeader = "package " + packageName + ".steps;\n\n" + imports
                + "public class " + stepClassName + " {\n\n" + fields;

        // 每页一份视图：类外壳 + 字段/import 共享 + 仅该页的 step/ops 方法
        for (String pc : allPages) {
            StringBuilder methods = new StringBuilder();
            int[] npIdx = {0};
            int stepIdx = 0;
            boolean any = false;
            if (stepsByPage.containsKey(pc)) {
                for (List<RoleEntry> step : stepsByPage.get(pc)) {
                    stepIdx++;
                    any = true;
                    methods.append("    @Step\n");
                    methods.append("    public void step").append(stepIdx).append("() {\n");
                    boolean sawPopup = false;
                    String popupTargetVar = null;   // 弹窗目标页对象变量：交由它接管并在其上 closeCurrentPage
                    if (step == null || step.isEmpty()) {
                        methods.append("        // 该 step 未拾取任何元素\n");
                    } else {
                        for (RoleEntry e : step) {
                            String epc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? pc : e.getPageClass();
                            String var = pageVar.get(epc);
                            if (var == null) var = pageVar.get(pc);
                            if (var == null) continue;
                            if (e.isCloseOp()) {
                                // 关闭当前页：仅在“本 step 没有对应弹窗打开”时渲染；含弹窗打开时由下方基于
                                // “打开页”统一兜底，避免重复/错页（弹窗页实例引用并非被关的弹窗）。
                                if (sawPopup) continue;
                                methods.append("        ").append(var)
                                        .append(".closeCurrentPage(); // 关闭当前页（弹窗），onClose 自动切回默认页\n");
                                continue;
                            }
                            Map<String, String> kf = pageFields.get(epc);
                            String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                            if (field == null) continue;
                            String target = var + "." + field;
                            if (e.getIndex() >= 0) target += ".nth(" + e.getIndex() + ")";
                            // 原生对话框（alert/confirm/prompt）：前置插桩，对齐 page.pause() 的
                        // onceDialog 前置信号——必须在触发它的动作之前挂载监听，否则默认 dismiss 会让
                        // confirm 流程异常。走框架封装 acceptAlert()/dismissAlert()（内部 page.onceDialog）。
                        // 方案1：alert 默认 accept；confirm/prompt 默认 dismiss。
                        if (e.isDialog()) {
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert()" : "dismissAlert()";
                            methods.append("        ").append(pageVar).append(".").append(dlgMethod)
                                    .append("; // 处理原生对话框(").append(e.getDialogType()).append(")\n");
                        }
                        String op = target + "." + operationFor(e);
                            if (e.isDownload()) {
                                if (e.isPopup()) {
                                    sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                                    methods.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                            .append("            ").append(var).append(".switchToNewPage(() ->\n")
                                            .append("                    ").append(op).append(", 15);\n")
                                            .append("        });\n");
                                } else {
                                    methods.append("        ").append(var).append(".waitForDownload(() ->\n")
                                            .append("                ").append(op).append(");\n");
                                }
                            } else if (e.isPopup()) {
                                sawPopup = true;
                                String npVar = nextNewPageVar(npIdx);
                                methods.append("        Page ").append(npVar).append(" = ").append(var)
                                        .append(".switchToNewPage(() ->\n")
                                        .append("                ").append(op).append(", 15);\n");
                                String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                                if (popupTarget != null) {
                                    methods.append("        ").append(popupTarget).append(".switchToPage(").append(npVar)
                                            .append("); // 新页面交由 ").append(popupTarget).append(" 接管\n");
                                    if (popupTargetVar == null) popupTargetVar = popupTarget;
                                } else if (popupTargetVar == null) {
                                    popupTargetVar = var;
                                }
                            } else {
                                methods.append("        ").append(op).append(";\n");
                            }
                        }
                    }
                    // 弹窗打开（sawPopup）：无论 onClose 是否成功登记 _closeOp，都基于“打开页”统一补 closeCurrentPage，
                    // 保证“打开弹窗→关闭弹窗”闭环生成（修复：onClose 未捕获关闭时关闭步骤丢失）。
                    if (sawPopup && popupTargetVar != null) {
                        methods.append("        ").append(popupTargetVar)
                                .append(".closeCurrentPage(); // 关闭弹窗页（").append(popupTargetVar)
                                .append(" 接管），onClose 自动切回默认页\n");
                    }
                    methods.append("    }\n\n");
                }
            }
            if (opsByPage.containsKey(pc)) {
                for (String op : opsByPage.get(pc)) {
                    if ("close".equals(op)) {
                        String var = pageVar.get(pc);
                        if (var == null) continue;
                        // 该页是弹窗目标页：关闭已内联到“打开弹窗的 step”中（含 switchToPage 绑定），
                        // 此处跳过独立方法，避免重复生成且独立方法拿不到弹窗引用。
                        if (inlinedTargetVars.contains(var)) continue;
                        any = true;
                        methods.append("    @Step\n");
                        methods.append("    public void close").append(pc).append("() {\n");
                        methods.append("        ").append(var)
                                .append(".closeCurrentPage(); // 关闭当前页（弹窗），onClose 自动切回默认页\n");
                        methods.append("    }\n\n");
                    }
                }
            }
            if (!any) methods.append("    // 该页面暂无 step\n");
            out.put(pc, clsHeader + methods + "}\n");
        }
        return out;
    }

    /** 多页面生成（无页面级操作，向后兼容）。 */
    public static String generateMulti(LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage,
                                        LinkedHashMap<String, List<RoleEntry>> entriesByPage,
                                        String packageName, String stepClassName) {
        return generateMulti(stepsByPage, entriesByPage, new LinkedHashMap<>(), packageName, stepClassName);
    }

    /**
     * 推断弹窗打开后应由哪个 Page 对象接管（目标页变量）。
     * <p>优先取 step 内弹窗 link 之后、归属页不同于打开页的首个元素所在页（跨页同 step 场景，
     * 弹窗内继续拾取的元素即归属弹窗页）；否则取 {@code opsByPage} 中标记了 {@code close} 且与打开页不同的
     * 首个页面（弹窗关闭 op 的 pageClass 即弹窗页，由 {@code onClose} 在弹窗关闭时按当前页写入）。
     * <p>这样生成的代码会把新页面绑到目标页对象（如 {@code privacyAndSecurityPage}）并在其上关闭，
     * 而非复用打开页（{@code loginPage}），对齐“弹窗落到独立页对象”的语义。
     */
    private static String inferPopupTargetVar(List<RoleEntry> step, String openVar,
            Map<String, String> pageVar, LinkedHashMap<String, List<String>> opsByPage) {
        if (step != null) {
            boolean pastPopup = false;
            for (RoleEntry e : step) {
                if (e.isPopup()) { pastPopup = true; continue; }
                if (pastPopup) {
                    // 弹窗之后的元素（含关闭标记 _closeOp）归属页若与打开页不同，即弹窗目标页对象。
                    String pc = e.getPageClass();
                    if (pc != null && !pc.isEmpty()) {
                        String v = pageVar.get(pc);
                        if (v != null && !v.equals(openVar)) return v;
                    }
                }
            }
        }
        if (opsByPage != null) {
            for (Map.Entry<String, List<String>> en : opsByPage.entrySet()) {
                if (en.getValue() != null && en.getValue().contains("close")) {
                    String v = pageVar.get(en.getKey());
                    if (v != null && !v.equals(openVar)) return v;
                }
            }
        }
        return null;
    }

    /** 首字母小写（userName ← UserName），用于页面字段实例名。 */
    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 一个 step 是否“仅由关闭操作（_closeOp）构成”——这类 step 是“关闭当前页”的标记，不应单独成方法。 */
    private static boolean isCloseOnlyStep(List<RoleEntry> step) {
        if (step == null || step.isEmpty()) return false;
        for (RoleEntry e : step) if (!e.isCloseOp()) return false;
        return true;
    }

    /**
     * 把“仅含关闭操作”的 step 合并进【上一个 step】，使“开始→停止 / 封装为步骤”过程中
     * 关闭当前页（含同标签整页跳转后直接关闭根页）仍落在同一个 @Step 方法内，不拆成多个 step。
     * 合并后关闭操作按原 _pageClass 内联渲染（调用对应页对象的 closeCurrentPage()）。
     * 若没有前置 step 可并入（极少见，如会话仅有关闭操作），则保留为独立 step。
     */
    private static List<List<RoleEntry>> mergeCloseOnlySteps(List<List<RoleEntry>> steps) {
        if (steps == null) return new ArrayList<>();
        List<List<RoleEntry>> out = new ArrayList<>();
        List<RoleEntry> last = null;
        for (List<RoleEntry> st : steps) {
            if (st == null) continue;
            if (isCloseOnlyStep(st)) {
                if (last != null) { last.addAll(st); continue; }
                // 无前置 step 可并入：保留为独立 step
            }
            out.add(st);
            last = st;
        }
        return out;
    }

    /**
     * 生成“新页面”变量名：首次为 {@code newPage}，其后 {@code newPage2 / newPage3 ...}。
     * 供弹窗触发 {@code Page x = page.switchToNewPage(...)} 使用，使新页面可被引用、生成代码可读。
     *
     * @param idx 计数器（用 int[] 以便在循环内自增），idx[0] 为已生成个数
     */
    private static String nextNewPageVar(int[] idx) {
        idx[0]++;
        return idx[0] == 1 ? "newPage" : "newPage" + idx[0];
    }
}
