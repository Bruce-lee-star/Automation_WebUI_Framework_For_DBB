package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

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
        // 勾选类元素（checkbox/radio）优先于 hover：用户点击 checkbox/radio 的语义是「设置勾选状态」，
        // 而 focusin 聚焦可能把点击误标为 hover（__rolePickFocus 以 isHover=true 记录、时序先于 click），
        // 若 hover 优先会让点击 checkbox 误生成 locator.hover() 而非 setChecked()。故先判定勾选角色。
        if (e.isRoleStrategy()) {
            String chkR = (e.getRole() == null ? "" : e.getRole()).toLowerCase();
            if ("checkbox".equals(chkR) || "radio".equals(chkR)) {
                if (e.getSetCheckedTarget() != null) {
                    return "setChecked(" + e.getSetCheckedTarget() + ")";
                }
                if (e.getChecked() != null) {
                    return e.getChecked() ? "check()" : "uncheck()";
                }
                return "check()";
            }
        }
        // 悬停（hover）交互：录制为 locator.hover()，对齐 page.pause() 对 hover 动作的录制。
        // （勾选类已在上面优先处理，此处仅剩 link/button/heading 等普通可悬停元素）
        if (e.isHover()) {
            return "hover()";
        }
        // 拖拽源优先：录制为 locator.dragTo(target)，对齐 page.pause() 的 source.dragTo(target)。
        // 拖拽语义由手势决定，覆盖角色推断（拖拽源本身可能是 button/link，但不应 click）。
        if (e.getDragDstKey() != null) {
            return "__DRAGTO__";
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
                    // 对齐 page.pause() 的 fill() 录制：整值替换（清空原值再写入），比逐字符 type() 更快更稳，
                    // 也不会与已有值叠加或被输入法干扰。文本框语义为「输入」，即使无值也生成 fill("")（留待补全），
                    // 而非误生成 click()（点击输入框无业务意义，易与聚焦/触发混淆）。
                    return "fill(\"" + escapeJava(e.getValue() == null ? "" : e.getValue()) + "\")";
                case "checkbox":
                case "radio":
                    // 对齐 page.pause() 的 setChecked 语义：按「目标」勾选状态选择 setChecked(bool)，
                    // 在目标已满足时幂等跳过，避免对已勾选元素再次 check / 已未勾选再次 uncheck 造成误 toggle。
                    // setCheckedTarget 为 null 时（未捕获目标状态）保守退化 check()/uncheck()（绝对目标状态）。
                    if (e.getSetCheckedTarget() != null) {
                        return "setChecked(" + e.getSetCheckedTarget() + ")";
                    }
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
            // 同样对齐 page.pause 的 fill 语义：输入框即使无值也生成 fill("")（输入语义，留待补全）。
            return "fill(\"" + escapeJava(e.getValue() == null ? "" : e.getValue()) + "\")";
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
            for (List<RoleEntry> rawStep : steps) {
                stepIdx++;
                // 按勾选动态序号（seq）升序排布：用户勾选顺序即步骤执行顺序；
                // seq 为 0（未编号）的元素保持原拾取相对顺序，保证幂等可重复生成。
                List<RoleEntry> step = new java.util.ArrayList<>(rawStep);
                final List<RoleEntry> src = rawStep;
                // seq 升序；seq 相等（如未编号的 needHelp 与跨页 closeMarker 均为 0）时，
                // 以原列表索引作二级稳定键，确保 closeMarker 紧邻被关闭页最后一个元素，
                // 避免 ArrayList.sort 在相等键上的不稳定重排导致 closeCurrentPage 顺序错乱。
                step.sort(java.util.Comparator.comparingInt((RoleEntry e) -> (e == null ? 0 : e.getSeq()))
                        .thenComparingInt(e -> src.indexOf(e)));
                boolean sawPopup = false;
                methods.append("    @Step\n");
                methods.append("    public void step").append(stepIdx).append("() {\n");
                if (step == null || step.isEmpty()) {
                    methods.append("        // 该 step 未拾取任何元素\n");
                } else {
                    List<String> lastFp = null; // 上一个元素的 iframe 路径（主框架为 null/空）
                    for (RoleEntry e : step) {
                        String field = keyToField.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;   // 不在页面字段中（理论上不会发生）
                        // iframe 上下文切换：若当前元素所在 iframe 链与上一个不同，先回主框架再逐层切入，
                        // 保证元素操作落在正确的 frame 上下文（生成独立的切换 step，而非缺失）。
                        List<String> fp = e.getFramePath();
                        if (!sameFramePath(/* 当前 iframe 链 */ fp, lastFp)) {
                            // 计算与上一个元素 iframe 链的差异，最小化切换动作。设 lastFp=旧链，fp=新链：
                            // 情形 A 嵌套加深：旧链是新链的前缀（如 [frameOne] → [frameOne, frameTwo]，
                            //   即 common == lastFp.size() 且新链更长）：在当前上下文基础上「补切」新增层。
                            // 情形 B 分叉：新链不是旧链的延伸（如 [frameOne] → [frameTwo]）：直接对【新链全部段】
                            //   逐层 switchToFrame（PageObject.switchToFrame 内部用 page.frame(name) 全局精确查找，
                            //   从任一 frame 上下文都能直接切到目标 frame，无需先回主框架，故省略多余的 switchToDefaultContent）。
                            // 情形 C 回主框架：旧链非空、新链为空：仅 switchToDefaultContent。
                            // 最小化切换：分叉/回退到 iframe 时不再先回主框架再重切，减少来回穿插时的冗余切换。
                            int common = commonPrefixLen(fp, lastFp);
                            boolean deepen = lastFp != null && fp != null && !fp.isEmpty()
                                    && common == lastFp.size() && fp.size() > lastFp.size();
                            boolean backToMain = (fp == null || fp.isEmpty());
                            if (lastFp != null && !deepen && backToMain) {
                                // 仅「回到主框架」情形需 switchToDefaultContent；分叉到另一 iframe 直接切即可。
                                methods.append("        ").append(pageVar)
                                        .append(".switchToDefaultContent(); // 退出当前 iframe，回到主框架\n");
                            }
                            if (fp != null && !fp.isEmpty()) {
                                int start = deepen ? common : 0; // 加深只切新增层；回退/分叉从新链首段重切
                                for (int i = start; i < fp.size(); i++) {
                                    String fsel = fp.get(i);
                                    String name = frameNameOf(fsel);
                                    if (name != null) {
                                        methods.append("        ").append(pageVar).append(".switchToFrame(\"")
                                                .append(escapeJavaString(name)).append("\"); // 切入 iframe: ").append(fsel).append("\n");
                                    } else {
                                        methods.append("        ").append(pageVar).append(".switchToFrame(\"")
                                                .append(escapeJavaString(fsel)).append("\"); // 切入 iframe (CSS)\n");
                                    }
                                }
                            }
                            lastFp = (fp == null) ? null : new ArrayList<>(fp);
                        }
                        // open shadowRoot 显式切换（对齐 page.pause() 的 >>> shadow 穿透录制）：
                        // 元素位于 shadow 内时，在 frame 切换之后进入各层 open shadow；操作结束后退出全部 shadow。
                        // shadowPath 为自顶向下的宿主 CSS 选择器链，逐层 switchToShadow 进入。
                        List<String> shadowPath = e.getShadowPath();
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            for (String host : shadowPath) {
                                methods.append("        ").append(pageVar).append(".switchToShadow(\"")
                                        .append(escapeJavaString(host)).append("\"); // 进入 shadow: ")
                                        .append(host).append("\n");
                            }
                        }
                        // 目标表达式：页字段（+ 一组元素时的 .nth(index) 消歧）
                        String target = pageVar + "." + field;
                        if (e.getIndex() >= 0) {
                            target += ".nth(" + e.getIndex() + ")";
                        }
                        // 原生对话框（alert/confirm/prompt）：对齐 page.pause() 的 onDialog 录制——监听
                        // 必须在"触发它的动作闭包内"同步注册，否则 confirm 在注册前已被浏览器默认 dismiss，
                        // 且多元素连续点击时前置的 onceDialog 会误作用到后续元素。框架封装的
                        // acceptAlert(trigger)/dismissAlert(trigger) 正是"在触发动作内注册一次性监听"的语义，
                        // 因此以独立 step 形式包裹触发动作（先注册监听再触发），而非裸点击。alert 默认 accept，
                        // confirm/prompt 默认 dismiss。
                        String op = target + "." + operationFor(e);
                        // 拖拽源：operationFor 返回 __DRAGTO__ 占位，展开为 dragTo(目标字段)（对齐 page.pause 的 source.dragTo）。
                        // 目标元素定位签名经 keyToField 反查为字段名，拼成 pageVar.fieldName。
                        if (op.endsWith(".__DRAGTO__")) {
                            String dstField = keyToField.get(e.getDragDstKey());
                            String dstRef = (dstField != null) ? pageVar + "." + dstField : "/* 拖拽目标未定位 */";
                            op = target + ".dragTo(" + dstRef + ")";
                        } else if (e.getPressKey() != null) {
                            // 键盘序列（对齐 page.pause 的 press("Enter")）：先 fill/click 基础动作，再 press 实质按键。
                            op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                        }
                        if (e.isDialog()) {
                            String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";
                            // 独立 step：先注册对话框处理器，再触发点击（时序正确，不再被误读为“裸点击”）
                            op = pageVar + "." + dlgMethod + "(() -> " + op + ") // 处理 " + dlgType + " 弹窗\n        ";
                        }
                        if (e.isDownload()) {
                            // 下载（anchor download 属性 / 文件 URL / JS 触发）：用框架封装的
                            // waitForDownload(trigger, timeoutSecs) 等待下载完成，对齐 page.pause()
                            // 的 waitForDownload 录制。与弹窗同时发生时嵌套：waitForDownload(waitForNewPage)。
                            if (e.isPopup()) {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() -> {\n")
                                        .append("            ").append(pageVar).append(".waitForNewPage(() ->\n")
                                        .append("                    ").append(op).append(", 15);\n")
                                        .append("        });\n");
                            } else {
                                methods.append("        ").append(pageVar).append(".waitForDownload(() ->\n")
                                        .append("                ").append(op).append(");\n");
                            }
                        } else if (e.isPopup()) {
                            // 弹窗链接（target=_blank）：用框架封装的 waitForNewPage(trigger, timeoutSecs)
                            // 一步完成“点击 + 等待新页面 + 切换页对象上下文”，并把新页面赋给 Page 变量，
                            // 后续操作在新页面执行；step 末统一“切回默认页”，使上下文回到默认 page（对齐用户预期）。
                            // 对齐 page.pause() 的 waitForPopup 语义，但走框架原生 API（无需手写 Playwright）。
                            String npVar = nextNewPageVar(npIdx);
                            sawPopup = true;
                            methods.append("        Page ").append(npVar).append(" = ").append(pageVar)
                                    .append(".waitForNewPage(() ->\n")
                                    .append("                ").append(op).append(", 15);\n");
                        } else {
                            methods.append("        ").append(op).append(";\n");
                        }
                        // 退出该元素所在的全部 open shadow，回到 DOM/iframe 上下文（与进入对称）。
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            methods.append("        ").append(pageVar)
                                    .append(".switchToDefaultShadow(); // 退出 shadow: ")
                                    .append(String.join(" > ", shadowPath)).append("\n");
                        }
                    }
                }
                if (sawPopup) {
                    // 本 step 触发过弹窗并切到了新页面：关闭弹窗页，由 BasePage 的 page.onClose 自动切回默认页
                    // （复用录制层 RoleElementPicker 语义，不再手写 switchToPage(0)）。
                    methods.append("        ").append(pageVar).append(".closeCurrentPage();\n");
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
                + "    private final " + pageClassName + " " + pageVar
                + " = PageObjectFactory.getPage(" + pageClassName + ".class);\n\n"
                + methods
                + "}\n";
    }

    /**
     * 生成 Step 类源码（向后兼容 4 参重载）。
     * step 类名复用 {@code packageName}，避免破坏既有调用方。
     *
     * @param steps         每个内层 List 是一次「开始 → 停止」拾取出的元素（一个 step）
     * @param allEntries    所有 step 的并集（去重后对应页面字段），用于字段命名
     * @param packageName   生成页类的包名（step 类放在其 {@code .steps} 子包下）
     * @param pageClassName 页类名（step 类通过 {@code PageObjectFactory.getPage} 引用）
     * @return 完整 Java 类源码
     */
    public static String generate(List<List<RoleEntry>> steps, List<RoleEntry> allEntries,
                                  String packageName, String pageClassName) {
        return generate(steps, allEntries, packageName, pageClassName, packageName);
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

        // 每个【页面实例】(pageClass#instanceId) -> (定位键 -> 字段名)，并生成该实例字段声明。
        // 同 pageClass 被打开多次（同页多标签）时，每个实例生成独立变量（loginPage / loginPage2 …），
        // 使生成的 step 能区分并独立切换/关闭不同实例，满足"同一页面被打开多次，来回切换"的需求。
        // 字段命名来源取该页已拾取元素（entriesByPage 已由 __rolePicks 覆盖全部 pick），与 Tab1 页面生成保持一致。
        Map<String, Map<String, String>> pageFields = new LinkedHashMap<>();
        Map<String, String> pageVar = new LinkedHashMap<>();     // pageClass#instanceId -> 唯一变量名
        Map<String, String> pageVarClass = new LinkedHashMap<>(); // pageClass#instanceId -> 页面类名
        StringBuilder fields = new StringBuilder();
        Set<String> usedVars = new HashSet<>();
        // 收集全部涉及的页面实例键（pageClass#instanceId），覆盖元素字段来源 + 各 step 拾取 + 关闭标记页。
        LinkedHashSet<String> allInstances = new LinkedHashSet<>();
        for (String className : allPages) {
            List<RoleEntry> pes = entriesByPage.get(className);
            if (pes != null) for (RoleEntry e : pes) allInstances.add(className + "#" + e.getPageInstanceId());
            else allInstances.add(className + "#1");
        }
        for (Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            for (List<RoleEntry> step : en.getValue()) {
                if (step == null) continue;
                for (RoleEntry e : step) {
                    String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? en.getKey() : e.getPageClass();
                    allInstances.add(pc + "#" + e.getPageInstanceId());
                }
            }
        }
        for (String instKey : allInstances) {
            int hash = instKey.lastIndexOf('#');
            String className = instKey.substring(0, hash);
            int instId = Integer.parseInt(instKey.substring(hash + 1));
            // 取该实例的实际拾取元素（同 pageClass 下按 instanceId 过滤）
            List<RoleEntry> allPageEntries = entriesByPage.get(className);
            List<RoleEntry> pageEntries = new ArrayList<>();
            if (allPageEntries != null) {
                for (RoleEntry e : allPageEntries) {
                    if (e.getPageInstanceId() == instId) pageEntries.add(e);
                }
            }
            List<RoleElementPageGenerator.GeneratedField> specs = RoleElementPageGenerator.assignFields(pageEntries);
            Map<String, String> keyToField = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField f : specs) {
                keyToField.put(RoleElementPageGenerator.locatorKey(f.entry), f.fieldName);
            }
            pageFields.put(instKey, keyToField);

            // 变量名：首个实例用 pageClass 去首字母小写；后续实例追加序号（loginPage / loginPage2 …）
            String base = decapitalize(className) + (instId > 1 ? instId : "");
            String uniqueVar = base;
            int v = 2;
            while (usedVars.contains(uniqueVar)) uniqueVar = base + (v++);
            usedVars.add(uniqueVar);
            pageVar.put(instKey, uniqueVar);
            pageVarClass.put(instKey, className);

            fields.append("    private final ").append(className).append(" ").append(uniqueVar)
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
            for (List<RoleEntry> rawStep : en.getValue()) {
                stepIdx++;
                any = true;
                // 按勾选动态序号（seq）升序排布：用户勾选顺序即步骤执行顺序；
                // seq 为 0（未编号）的元素保持原拾取相对顺序，保证幂等可重复生成。
                List<RoleEntry> step = new java.util.ArrayList<>(rawStep);
                final List<RoleEntry> src = rawStep;
                // seq 升序；seq 相等（如未编号的 needHelp 与跨页 closeMarker 均为 0）时，
                // 以原列表索引作二级稳定键，确保 closeMarker 紧邻被关闭页最后一个元素，
                // 避免 ArrayList.sort 在相等键上的不稳定重排导致 closeCurrentPage 顺序错乱。
                step.sort(java.util.Comparator.comparingInt((RoleEntry e) -> (e == null ? 0 : e.getSeq()))
                        .thenComparingInt(e -> src.indexOf(e)));
                StringBuilder m = new StringBuilder();
                m.append("    @Step\n");
                m.append("    public void step").append(stepIdx).append("() {\n");
                boolean sawPopup = false;
                boolean renderedCloseOp = false;
                String popupTargetVar = null;   // 弹窗目标页对象变量：交由它接管并在其上 closeCurrentPage
                String activeVar = null;        // 当前激活页对象变量：用于跨页上下文自动切换检测
                if (step == null || step.isEmpty()) {
                    m.append("        // 该 step 未拾取任何元素\n");
                } else {
                    List<String> lastFp = null; // 上一个元素的 iframe 路径（主框架为 null/空）
                    for (RoleEntry e : step) {
                        String pc = (e.getPageClass() == null || e.getPageClass().isEmpty())
                                ? stepPageClass : e.getPageClass();
                        // 按 (pageClass#instanceId) 实例键取对应页面变量：同页多次打开时各实例变量独立。
                        String instKey = pc + "#" + e.getPageInstanceId();
                        String var = pageVar.get(instKey);
                        if (var == null) var = pageVar.get(pc + "#1");
                        if (var == null) var = pageVar.get(stepPageClass + "#" + e.getPageInstanceId());
                        if (var == null) var = pageVar.get(stepPageClass + "#1");
                        if (var == null) continue;
                        // 跨页上下文自动切换：维护"当前激活页变量"activeVar。
                        // 弹窗打开后 activeVar 指向新页；当后续元素属于【打开页】（原页面）时，
                        // 说明新页操作已结束、需切回原页才能操作其元素——此时先 closeCurrentPage 切回，
                        // 再生成该元素的操作（而非把 closeCurrentPage 推到 step 末尾，否则原页元素会夹在新页上下文里）。
                        if (activeVar == null) activeVar = var;
                        if (popupTargetVar != null && activeVar.equals(popupTargetVar) && !var.equals(popupTargetVar)) {
                            // 从弹窗新页切回打开页：先关闭新页（onClose 自动切回其父页/打开页），再操作原页元素。
                            m.append("        ").append(popupTargetVar)
                                    .append(".closeCurrentPage(); // 切回打开页（").append(var).append("）\n");
                            renderedCloseOp = true;
                            activeVar = null;     // 关闭后回到打开页，下一轮重新锚定
                            popupTargetVar = null;
                        }
                        if (e.isCloseOp()) {
                            // 关闭当前页（弹窗/新页）：在 step 序列中该关闭操作发生的位置内联渲染 closeCurrentPage，
                            // 使“打开新页 -> 在新页操作 -> 关闭 -> 自动切回 active 父页”的顺序与用户实际操作一致。
                            // 不再 defer 到 step 末尾（旧逻辑会把旧页元素夹在新页切换与关闭之间，造成错位）。
                            // closeCurrentPage 的 onClose 会自动把 Page 上下文切回打开它的父页（当前 active 页）。
                            String cv = (popupTargetVar != null) ? popupTargetVar : var;
                            m.append("        ").append(cv)
                                    .append(".closeCurrentPage();\n");
                            renderedCloseOp = true;
                            continue;
                        }
                        Map<String, String> kf = pageFields.get(instKey);
                        if (kf == null) kf = pageFields.get(pc + "#1");
                        String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                        if (field == null) continue;
                        // iframe 上下文切换：若当前元素所在 iframe 链与上一个不同，先回主框架再逐层切入，
                        // 保证元素操作落在正确的 frame 上下文（避免“切换 iframe 没有切换 step”的问题）。
                        List<String> fp = e.getFramePath();
                        if (!sameFramePath(fp, lastFp)) {
                            // 计算与上一个元素 iframe 链的差异，最小化切换动作（对齐 page 对象版逻辑）：
                            // - 嵌套加深（[frameOne] → [frameOne, frameTwo]）：只补切新增层，不回主框架；
                            // - 分叉（[a] → [b] / [a,b] → [a,c]）：直接对【新链全部段】逐层 switchToFrame
                            //   （page.frame(name) 全局精确查找，无需先回主框架）；
                            // - 回到主框架：仅 switchToDefaultContent。
                            int common = commonPrefixLen(fp, lastFp);
                            boolean deepen = lastFp != null && fp != null && !fp.isEmpty()
                                    && common == lastFp.size() && fp.size() > lastFp.size();
                            boolean backToMain = (fp == null || fp.isEmpty());
                            if (lastFp != null && !deepen && backToMain) {
                                // 仅「回到主框架」情形需 switchToDefaultContent；分叉到另一 iframe 直接切即可。
                                m.append("        ").append(var)
                                        .append(".switchToDefaultContent(); // 退出当前 iframe，回到主框架\n");
                            }
                            if (fp != null && !fp.isEmpty()) {
                                int start = deepen ? common : 0;
                                for (int i = start; i < fp.size(); i++) {
                                    String fsel = fp.get(i);
                                    String name = frameNameOf(fsel);
                                    if (name != null) {
                                        // name 精确查找（对齐 page.pause 的 frameLocator("iframe[name=...]")，
                                        // 框架 switchToFrame(name) 内部用 page.frame(name) 精确匹配，最稳健）。
                                        // 说明：page object 里的 iframe 多为【已存在】的静态元素，直接切即可；
                                        // 若遇动态/异步加载的 iframe，可改用 switchToFrameAndWait(trigger, name)
                                        // ——该 API 采用 onFrameAttached 事件监听（监听器范式，对标 waitForPopup）。
                                        m.append("        ").append(var).append(".switchToFrame(\"")
                                                .append(escapeJavaString(name)).append("\");\n");
                                    } else {
                                        // 退化：id（#x）/ nth-of-type 等选择器，按 CSS 选择器切入。
                                        m.append("        ").append(var).append(".switchToFrame(\"")
                                                .append(escapeJavaString(fsel)).append("\");\n");
                                    }
                                }
                            }
                            lastFp = (fp == null) ? null : new ArrayList<>(fp);
                        }
                        // open shadowRoot 显式切换（对齐 page.pause() 的 >>> shadow 穿透录制）：
                        // 元素位于 shadow 内时，在 frame 切换之后进入各层 shadow；操作结束后退出全部 shadow。
                        List<String> shadowPath = e.getShadowPath();
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            for (String host : shadowPath) {
                                m.append("        ").append(var).append(".switchToShadow(\"")
                                        .append(escapeJavaString(host)).append("\"); // 进入 shadow: ")
                                        .append(host).append("\n");
                            }
                        }
                        String target = var + "." + field;
                        if (e.getCount() > 1 && e.getIndex() >= 0) target += ".nth(" + e.getIndex() + ")";
                        // 原生对话框（alert/confirm/prompt）：对齐 page.pause() 的 onDialog 录制——监听
                        // 必须在"触发它的动作闭包内"同步注册，否则 confirm 在注册前已被浏览器默认 dismiss，
                        // 且多元素连续点击时前置的 onceDialog 会误作用到后续元素。框架封装的
                        // acceptAlert(trigger)/dismissAlert(trigger) 正是"在触发动作内注册一次性监听"的语义，
                        // 因此直接把对话框处理包裹进触发动作，而非放在动作之前。alert 默认 accept，
                        // confirm/prompt 默认 dismiss。
                        String op = target + "." + operationFor(e);
                        // 拖拽源：operationFor 返回 __DRAGTO__ 占位，展开为 dragTo(目标字段)（对齐 page.pause 的 source.dragTo）。
                        // 目标元素定位签名在所有页字段中反查（支持跨页拖拽），拼成 <页变量>.<字段名>。
                        if (op.endsWith(".__DRAGTO__")) {
                            String dstRef = findFieldRefAcrossPages(e.getDragDstKey(), pageFields, pageVar);
                            op = target + ".dragTo(" + dstRef + ")";
                        } else if (e.getPressKey() != null) {
                            // 键盘序列（对齐 page.pause 的 press("Enter")）：先 fill/click 基础动作，再 press 实质按键。
                            op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                        }
                        if (e.isDialog()) {
                            String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                            String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";
                            // 独立 step：先注册对话框处理器，再触发点击（时序正确，不再被误读为“裸点击”）
                            op = var + "." + dlgMethod + "(() -> " + op + ") // 处理 " + dlgType + " 弹窗\n        ";
                        }
                        if (e.isDownload()) {
                            if (e.isPopup()) {
                            sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                            m.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                    .append("            ").append(var).append(".waitForNewPage(() ->\n")
                                    .append("                    ").append(op).append(", 15);\n")
                                    .append("        });\n");
                            } else {
                                m.append("        ").append(var).append(".waitForDownload(() ->\n")
                                        .append("                ").append(op).append(");\n");
                            }
                        } else if (e.isPopup()) {
                            // 弹窗链接（target=_blank）：由“目标页对象”（如 privacyAndSecurityPage）触发并接管新页，
                            // 避免“打开页先接管、再 switchToPage 交给目标页”的语义歧义与打开页引用错位。
                            // 触发点击的元素仍属打开页（var），故闭包内用 var + "." + op；waitForNewPage 宿主用 popupTarget。
                            sawPopup = true;
                            lastFp = null; // 弹窗打开新页，frame 栈与旧页不同，重置以避免误切回旧 frame
                            String npVar = nextNewPageVar(npIdx);
                            String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                            if (popupTarget != null) {
                                m.append("        Page ").append(npVar).append(" = ").append(popupTarget)
                                        .append(".waitForNewPage(() ->\n")
                                        .append("                ").append(op).append(", 15);\n");
                                m.append("        ").append(popupTarget).append(".switchToPage(")
                                        .append(npVar).append("); // 显式切换到新页面\n");
                                if (popupTargetVar == null) popupTargetVar = popupTarget;
                                activeVar = popupTarget; // 当前激活页切到新页，便于后续检测"回到打开页"时切回
                            } else {
                                m.append("        Page ").append(npVar).append(" = ").append(var)
                                        .append(".waitForNewPage(() ->\n")
                                        .append("                ").append(op).append(", 15);\n");
                                if (popupTargetVar == null) popupTargetVar = var;
                            }
                        } else {
                            m.append("        ").append(op).append(";\n");
                        }
                        // 退出该元素所在的全部 shadow，回到 DOM/iframe 上下文（与进入对称）。
                        if (shadowPath != null && !shadowPath.isEmpty()) {
                            m.append("        ").append(var)
                                    .append(".switchToDefaultShadow(); // 退出 shadow: ")
                                    .append(String.join(" > ", shadowPath)).append("\n");
                        }
                    }
                }
                // 弹窗打开（sawPopup）：无论其 onClose 是否成功把 _closeOp 推回父页快照，都基于“打开页”统一补
                // closeCurrentPage，保证“打开弹窗→关闭弹窗”闭环生成（修复：onClose 未捕获关闭时关闭步骤丢失）。
                // waitForNewPage 后打开页实例的当前页引用即弹窗，故在打开页上调用 closeCurrentPage 语义正确。
                if (sawPopup && popupTargetVar != null && !renderedCloseOp) {
                    m.append("        ").append(popupTargetVar)
                            .append(".closeCurrentPage();\n");
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

    /** 判断两个 iframe 路径是否相同（顺序敏感；null 与空列表视为等价，都表示主框架）。 */
    private static boolean sameFramePath(List<String> a, List<String> b) {
        if (a == null) a = java.util.Collections.emptyList();
        if (b == null) b = java.util.Collections.emptyList();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    /** 两个 iframe 路径的最长公共前缀长度（段级相等，顺序敏感）。用于最小化 frame 切换：
     *  如 [frameOne] 与 [frameOne, frameTwo] 的公共前缀长度为 1，只需补切第 2 段。 */
    private static int commonPrefixLen(List<String> a, List<String> b) {
        if (a == null) a = java.util.Collections.emptyList();
        if (b == null) b = java.util.Collections.emptyList();
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) break;
            i++;
        }
        return i;
    }

    /** 转义 Java 双引号字符串字面量中的反斜杠与双引号，避免生成非法源码。 */
    private static String escapeJavaString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 从 iframe 选择器串中提取一个可用于 {@code page.frame(label)} 精确匹配的稳定标签，实现通用 frame 段解析。
     * 支持形态：
     * <ul>
     *   <li>{@code iframe[name="x"]} / {@code frame[name='x']} → 提取 name（优先，Playwright 按 name 精确匹配）</li>
     *   <li>{@code iframe#id} / {@code #id} → 提取 id（Playwright 按 id 精确匹配）</li>
     * </ul>
     * 对于 {@code iframe[src=...]}、{@code iframe.login}、{@code iframe:nth-of-type(2)}、裸 {@code iframe} 等
     * 无法被 page.frame(label) 精确匹配的形态返回 null，交由调用方走 {@code switchToFrame(CSS)} 兜底
     * （BasePage 内部再按 CSS 选择器定位，src/class/nth 等复杂形态在此通道生效）。
     */
    private static String frameNameOf(String fsel) {
        if (fsel == null) return null;
        Matcher m;
        m = FRAME_NAME.matcher(fsel);
        if (m.find()) return m.group(1);
        m = FRAME_ID.matcher(fsel);
        if (m.find()) return m.group(1);
        return null;
    }
    private static final java.util.regex.Pattern FRAME_NAME =
            java.util.regex.Pattern.compile("(?:iframe|frame)\\s*\\[\\s*name\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\]");
    private static final java.util.regex.Pattern FRAME_ID =
            java.util.regex.Pattern.compile("#([\\w-]+)");

    /** 在所有页字段映射中反查拖拽目标的字段引用（支持跨页拖拽，对齐 page.pause 的 source.dragTo(target)）。
     *  找到则拼成 {@code <页变量>.<字段名>}，否则返回注释提示。 */
    private static String findFieldRefAcrossPages(String dragDstKey,
                                                  Map<String, Map<String, String>> pageFields,
                                                  Map<String, String> pageVar) {
        if (dragDstKey == null) return "/* 拖拽目标未定位 */";
        for (Map.Entry<String, Map<String, String>> en : pageFields.entrySet()) {
            String field = en.getValue().get(dragDstKey);
            if (field != null) {
                String pv = pageVar.get(en.getKey());
                return (pv != null ? pv : "page") + "." + field;
            }
        }
        return "/* 拖拽目标未定位 */";
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
            fields.append("    private final ").append(className).append(" ").append(uniqueVar)
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
                    boolean renderedCloseOp = false;
                    List<String> lastFp = null; // 上一个元素的 iframe 路径（主框架为 null/空）
                    String popupTargetVar = null;   // 弹窗目标页对象变量：交由它接管并在其上 closeCurrentPage
                    String activeVar = null;        // 当前激活页对象变量：用于跨页上下文自动切换检测（对齐 generateMulti）
                    if (step == null || step.isEmpty()) {
                        methods.append("        // 该 step 未拾取任何元素\n");
                    } else {
                        for (RoleEntry e : step) {
                            String epc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? pc : e.getPageClass();
                            String var = pageVar.get(epc);
                            if (var == null) var = pageVar.get(pc);
                            if (var == null) continue;
                            // 【修复"closeCurrentPage 顺序错位"】跨页上下文自动切换检测：
                            // 维护当前激活页变量 activeVar。弹窗打开后 activeVar 指向新页(popupTargetVar)；
                            // 当后续元素属于【打开页】(原页面)时，说明新页操作已结束、需切回原页才能操作其元素——
                            // 此时先内联 closeCurrentPage 切回，再生成该元素操作（而非把 closeCurrentPage 推到 step
                            // 末尾，否则原页元素会夹在新页上下文里导致顺序错乱/悬空）。
                            // 此逻辑原先只在 generateMulti 中有，而 buildStepCode 实际调用的是 generatePerPage，
                            // 导致该修复未生效——现补齐到生成主路径。
                            if (activeVar == null) {
                                activeVar = var;
                            } else if (var != null && !activeVar.equals(var)) {
                                if (popupTargetVar != null && activeVar.equals(popupTargetVar) && !var.equals(popupTargetVar)) {
                                    methods.append("        ").append(popupTargetVar)
                                            .append(".closeCurrentPage();\n");
                                    renderedCloseOp = true;
                                    activeVar = null;
                                    popupTargetVar = null;
                                }
                                activeVar = var;
                            }
                            if (e.isCloseOp()) {
                                // 关闭当前页（弹窗/新页）：在 step 序列中该关闭操作发生的位置内联渲染
                                // closeCurrentPage，使“打开新页 -> 在新页操作 -> 关闭 -> 自动切回 active 父页”
                                // 的顺序与用户实际操作一致（不再 defer 到 step 末尾，否则会把旧页元素夹在
                                // 新页切换与关闭之间造成错位）。closeCurrentPage 的 onClose 会自动把 Page
                                // 上下文切回打开它的父页（当前 active 页），故后续旧页元素无需再手写 switchToPage。
                                String cv = (popupTargetVar != null) ? popupTargetVar : var;
                                methods.append("        ").append(cv)
                                        .append(".closeCurrentPage();\n");
                                renderedCloseOp = true;
                                lastFp = null; // 关闭弹窗回到父页，frame 栈与弹窗页不同，重置避免误切
                                continue;
                            }
                            Map<String, String> kf = pageFields.get(epc);
                            String field = (kf == null) ? null : kf.get(RoleElementPageGenerator.locatorKey(e));
                            if (field == null) continue;
                            // iframe 上下文切换：若当前元素所在 iframe 链与上一个不同，先回主框架再逐层切入，
                            // 生成独立的切换 step，保证元素操作落在正确的 frame 上下文。
                            List<String> fp = e.getFramePath();
                            if (!sameFramePath(fp, lastFp)) {
                                // 最小化切换：仅当「目标在主框架」(fp 为空) 时才先退出到主框架；
                                // 分叉到另一 iframe 时，PageObject.switchToFrame(name) 内部用 page.frame(name)
                                // 全局精确查找，从任意当前上下文都能直接切到目标 frame，无需先回主框架，
                                // 故省略多余的 switchToDefaultContent（避免来回穿插 iframe 时产生冗余切换）。
                                boolean backToMain = (fp == null || fp.isEmpty());
                                if (lastFp != null && backToMain) {
                                    methods.append("        ").append(var)
                                            .append(".switchToDefaultContent(); // 退出当前 iframe，回到主框架\n");
                                }
                                if (fp != null) {
                                    for (String fsel : fp) {
                                        String name = frameNameOf(fsel);
                                        if (name != null) {
                                            methods.append("        ").append(var).append(".switchToFrame(\"")
                                                    .append(escapeJavaString(name)).append("\"); // 切入 iframe: ").append(fsel).append("\n");
                                        } else {
                                            methods.append("        ").append(var).append(".switchToFrame(\"")
                                                    .append(escapeJavaString(fsel)).append("\"); // 切入 iframe (CSS)\n");
                                        }
                                    }
                                }
                                lastFp = (fp == null) ? null : new ArrayList<>(fp);
                            }
                            String target = var + "." + field;
                            if (e.getCount() > 1 && e.getIndex() >= 0) target += ".nth(" + e.getIndex() + ")";
                            // 原生对话框（alert/confirm/prompt）：对齐 page.pause() 的 onDialog 录制——监听
                            // 必须在"触发它的动作闭包内"同步注册，否则 confirm 在注册前已被浏览器默认 dismiss，
                            // 且多元素连续点击时前置的 onceDialog 会误作用到后续元素。框架封装的
                            // acceptAlert(trigger)/dismissAlert(trigger) 正是"在触发动作内注册一次性监听"的语义，
                            // 因此以独立 step 形式包裹触发动作（先注册监听再触发），而非裸点击。alert 默认 accept，
                            // confirm/prompt 默认 dismiss。
                            String op = target + "." + operationFor(e);
                            // 拖拽源：operationFor 返回 __DRAGTO__ 占位，展开为 dragTo(目标字段)（对齐 page.pause 的 source.dragTo）。
                            // 目标元素定位签名在所有页字段中反查（支持跨页拖拽），拼成 <页变量>.<字段名>。
                            if (op.endsWith(".__DRAGTO__")) {
                                String dstRef = findFieldRefAcrossPages(e.getDragDstKey(), pageFields, pageVar);
                                op = target + ".dragTo(" + dstRef + ")";
                            } else if (e.getPressKey() != null) {
                                // 键盘序列（对齐 page.pause 的 press("Enter")）：先 fill/click 基础动作，再 press 实质按键。
                                op += ";\n        " + target + ".press(\"" + escapeJava(e.getPressKey()) + "\")";
                            }
                            if (e.isDialog()) {
                                String dlgType = (e.getDialogType() == null) ? "alert" : e.getDialogType();
                                String dlgMethod = "accept".equals(e.getDialogAction()) ? "acceptAlert" : "dismissAlert";
                                // 独立 step：先注册对话框处理器，再触发点击（时序正确，不再被误读为“裸点击”）
                                op = var + "." + dlgMethod + "(() -> " + op + ") // 处理 " + dlgType + " 弹窗\n        ";
                            }
                            if (e.isDownload()) {
                                if (e.isPopup()) {
                                    sawPopup = true; if (popupTargetVar == null) popupTargetVar = var;
                                    methods.append("        ").append(var).append(".waitForDownload(() -> {\n")
                                            .append("            ").append(var).append(".waitForNewPage(() ->\n")
                                            .append("                    ").append(op).append(", 15);\n")
                                            .append("        });\n");
                                } else {
                                    methods.append("        ").append(var).append(".waitForDownload(() ->\n")
                                            .append("                ").append(op).append(");\n");
                                }
                            } else if (e.isPopup()) {
                                sawPopup = true;
                                lastFp = null; // 弹窗/新页在另一个 Page（frame 栈与当前页无关），重置避免误切 frame
                                String npVar = nextNewPageVar(npIdx);
                                // 弹窗目标页（popupTarget）已推断出来时，由它来触发并接管新页，
                                // 避免"openVar 先接管新页、再 switchToPage 交给 popupTarget"造成的语义歧义与
                                // openVar 引用短暂错位（生成：privacyAndSecurityPage.waitForNewPage(() ->
                                // logonPage.privacyAndSecurityFooterLink.click(), 15)）。触发点击的元素仍属 openVar，
                                // 故闭包内用 var + "." + op；waitForNewPage 的宿主用 popupTarget。
                                String popupTarget = inferPopupTargetVar(step, var, pageVar, opsByPage);
                                if (popupTarget != null) {
                                    methods.append("        Page ").append(npVar).append(" = ").append(popupTarget)
                                            .append(".waitForNewPage(() ->\n")
                                            .append("                ").append(op).append(", 15);\n");
                                    methods.append("        ").append(popupTarget).append(".switchToPage(")
                                            .append(npVar).append(");\n");
                                    if (popupTargetVar == null) popupTargetVar = popupTarget;
                                } else {
                                    methods.append("        Page ").append(npVar).append(" = ").append(var)
                                            .append(".waitForNewPage(() ->\n")
                                            .append("                ").append(op).append(", 15);\n");
                                    if (popupTargetVar == null) popupTargetVar = var;
                                }
                            } else {
                                methods.append("        ").append(op).append(";\n");
                            }
                        }
                    }
                    // 弹窗打开（sawPopup）：仅当内联 _closeOp 未渲染时才基于“打开页”统一补 closeCurrentPage，
                    // 保证“打开弹窗→关闭弹窗”闭环生成（修复：onClose 未捕获关闭时关闭步骤丢失）；
                    // 若已内联渲染则跳过，避免重复生成 closeCurrentPage。
                    if (sawPopup && popupTargetVar != null && !renderedCloseOp) {
                        methods.append("        ").append(popupTargetVar)
                                .append(".closeCurrentPage();\n");
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
                                .append(".closeCurrentPage();\n");
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
        boolean sawPopup = false;
        if (step != null) {
            boolean pastPopup = false;
            for (RoleEntry e : step) {
                if (e.isPopup()) { pastPopup = true; sawPopup = true; continue; }
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
        // 【同页面类弹窗修复】window.open(同URL+'#popup') 弹出的仍是同一页面类（如 PickerComprehensivePage），
        // 弹窗打开页与目标页是同一个 pageClass 变量，上方"归属页不同"分支无法识别。
        // 但既然 step 内确实存在 popup 打开行为（sawPopup），其关闭已由 waitForNewPage 的打开页变量
        // 在 step 末尾内联 closeCurrentPage()（生成器 sawPopup 分支），故把打开页变量本身作为目标，
        // 使其被 inlinedTargetVars 收录，跳过独立的 closeXXX() 方法，避免重复关闭。
        if (sawPopup && openVar != null && !openVar.isEmpty()) return openVar;
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
     * 供弹窗触发 {@code Page x = page.waitForNewPage(...)} 使用，使新页面可被引用、生成代码可读。
     *
     * @param idx 计数器（用 int[] 以便在循环内自增），idx[0] 为已生成个数
     */
    private static String nextNewPageVar(int[] idx) {
        idx[0]++;
        return idx[0] == 1 ? "newPage" : "newPage" + idx[0];
    }
}
