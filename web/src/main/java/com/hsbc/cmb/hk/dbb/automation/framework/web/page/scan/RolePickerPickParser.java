package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.RoleElement;

/**
 * Pick / state parsing &amp; merge helpers (T5-1 step 4, cluster 3): relocates
 * RoleElementPicker's pure data-transformation helpers for browser pick maps into
 * this class. Every method here takes plain data (Map / RoleEntry / List) and
 * returns data; it performs no Playwright/browser calls and no CTX_* runtime state
 * access. It references only RoleEntry / RoleElement domain types plus the sibling
 * pure helper RoleElementPageGenerator.locatorKey and the shared constant
 * RoleElementPicker.LOCATOR_IDENTITY_STRATEGIES. Behavior is identical to the
 * original inline logic.
 */
final class RolePickerPickParser {

    static final Logger log = LoggerFactory.getLogger(RolePickerPickParser.class);
    static final Gson GSON = new Gson();
    static final Type MAP_STRING_OBJECT_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /** null-safe Object→String（与 RoleElementPicker#asString 同语义；此处自包含副本，避免反向依赖）。 */
    static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * 计算拾取回传的权威去重键：
     * - 定位器唯一型策略（i18n/id/css/text/title/...）按「去索引的稳定 locatorKey」去重，
     *   与页面字段 RoleElementPageGenerator.locatorKey 语义一致（均不含 #index）。
     *   ⚠️ 旧实现对这类策略直接用含 #index 的 _sig（如 i18n:forgot_username_title#0）作 key——
     *   同一 data-i18n 值若页面上有多个匹配（count>1），或区域重扫/勾选顺序变化导致 __attachIndex
     *   重新分配 #index 时，删除回传的 _sig 与入库时的 _sig 不一致，javaPickBySig.remove 命中失败，
     *   表现为"data-i18n 元素删了又复活"。改用去索引 locatorKey 后，删除 key 稳定，必能命中。
     * - 其余（role/closeOp）仍按页面作用域键（_sigKey，含 pageClass、不含 index）区分，保留原行为。
     */
    static String pickDedupKey(Map<Object, Object> m, RoleEntry e) {
        if (m == null) return "";
        Object sig = m.get("_sig");
        Object sigKeyRaw = m.get("_sigKey");
        // 归一化去重键：浏览器侧 __sigKey 对 i18n/role 等元素生成的是 JSON 数组形式
        // ["sig","pageClass"]（如 ["i18n:user_name#0","LogonPage"]），而首次拾取的回传 _sigKey
        // 是 JS 侧 __recordPick 写入的字符串形式 "pageClass|sig"（如 "LogonPage|i18n:user_name"）。
        // 同一元素两次回传的 key 形式不一致会导致 mergePickIntoMap 命中不同条目，重复点击的序号
        // 数组（_pickNos）被存到数组键下、而展示/生成沿用首次字符串键的残缺值 → 序号混乱。
        // 此处把数组形式统一归一为 "pageClass|sig" 字符串，与首次拾取键对齐，确保累积命中同一 entry。
        String sigKey = asString(sigKeyRaw);
        if (sigKey != null && sigKey.startsWith("[")) {
            try {
                List<?> arr = GSON.fromJson(sigKey, List.class);
                if (arr != null && arr.size() == 2) {
                    String s0 = asString(arr.get(0));
                    String s1 = asString(arr.get(1));
                    if (s1 != null && s0 != null) {
                        // 去掉 sig 末尾的 #index 后缀（如 "i18n:user_name#0" → "i18n:user_name"），
                        // 对齐首次拾取字符串键（LogonPage|i18n:user_name 不含 #0），保证同元素不同形式键命中同一 entry。
                        s0 = s0.replaceAll("#\\d+$", "");
                        sigKey = s1 + "|" + s0;
                    }
                }
            } catch (Exception ignore) { /* 保留原值 */ }
        }
        String strategy = (e != null) ? e.getStrategy() : null;
        boolean locatorIdentity = strategy != null && RoleElementPicker.LOCATOR_IDENTITY_STRATEGIES.contains(strategy);
        // 【方案 B：页面级隔离】内存态去重键一律绑定所属 pageClass，使不同页面上 role/name 完全相同
        // 的「共用元素」（如各页页脚链接、Close/Next、HSBC App tab）彻底按页分桶，互不干扰：
        // 删除某页元素时不再波及其它页同名元素（此前 i18n/定位器型策略的 locatorKey 不含 pageClass，
        // 两个页面的同名页脚共享同一 key，删 SetupSecondPwdPage 的页脚会误删 LoginPage 的同名页脚）。
        // pageClass 优先取 RoleEntry（已固化），避免浏览器侧重算键时因 _pageClass 缺失退化到 location 兜底。
        // pageClass 优先取 RoleEntry（已固化），其次回退到原始回传里的 _pageClass，
        // 避免浏览器侧重算键时因 pageClass 缺失退化到裸键（方案 B 隔离会失效）。
        String pc = (e != null && e.getPageClass() != null) ? e.getPageClass()
                : (m != null ? asString(m.get("_pageClass")) : null);
        if (pc == null) pc = "";
        String dedupKey;
        if (locatorIdentity) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) { dedupKey = pc + "|" + lk; log.info("[picker][diag-dedup] strategy={} branch=locatorIdentity key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw); return dedupKey; }
        }
        // role/closeOp 分支：_sigKey 已内嵌 pageClass（JSON.stringify([_sig, pageClass])），
        // 与浏览器 __rolePicks 的 _sigKey 同构，删除/本地过滤均可精确命中，保持原行为。
        if (sigKey != null) { dedupKey = String.valueOf(sigKey); log.info("[picker][diag-dedup] strategy={} branch=sigKey key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw); return dedupKey; }
        // 【方案 B 兜底】_sigKey 缺失时绝不能退化成裸 _sig——否则 LoginPage / SetupSecondPwdPage
        // 上同名共用元素（Language、HSBC App tab、各页脚链接，_sig 完全相同）会共享同一裸键，
        // 删一页即误删另一页。此处一律前缀 pageClass，确保即使缺 _sigKey 也维持按页隔离。
        if (sig != null) {
            String s = String.valueOf(sig);
            dedupKey = pc.isEmpty() ? s : pc + "|" + s;
            log.info("[picker][diag-dedup] strategy={} branch=fallback(_sig) key={} _sigKey(raw)={}", strategy, dedupKey, sigKeyRaw);
            return dedupKey;
        }
        log.info("[picker][diag-dedup] strategy={} branch=EMPTY key=\"\" _sigKey(raw)={}", strategy, sigKeyRaw);
        return "";
    }

    /** 回传去重辅助：null-safe 等值比较（用于 sigKey 比较）。 */
    static boolean roleEq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    /** 回传去重辅助：null-safe 的 framePath 列表等值比较（用于判断是否"增强了框架路径"）。 */
    static boolean framePathEq(List<String> a, List<String> b) {
        if (a == null) return b == null;
        if (b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i), y = b.get(i);
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    /**
     * 把一次拾取回传（incoming）写入权威内存态 map，并作「增强字段合并」而非直接覆盖。
     *
     * <p>一条 pick 可能经两条通道先后到达：① 绑定桥 __roleOnPick（exposeBinding，可拿到
     * source.frame() 做 framePath 回补，且能拿到 pageClass）；② 控制台兜底 __roleOnPick::...
     * （onConsoleMessage）。两条通道的 RoleEntry 是各自独立 parsePick 出来的对象，若直接 put 覆盖，
     * 后到者会把先到者的已补全字段（framePath / dialog / popup …）抹掉，最终生成代码缺 switchToFrame、
     * 缺 acceptAlert。
     *
     * <p>故此处保留「先到者为基线、后到者补齐空缺」：incoming 优先作为写入对象，但它缺失而已有条目
     * 已具备的增强字段（框架路径、对话框标记、弹窗标记）会被继承回来，使两次回传互为补充而非互相抵消。
     *
     * @return 最终写入 map 的 RoleEntry（供调用方日志使用）
     */
    static RoleEntry mergePickIntoMap(LinkedHashMap<String, RoleEntry> map, String key, RoleEntry incoming) {
        if (map == null || key == null || incoming == null) return incoming;
        RoleEntry existing = map.get(key);
        if (existing != null && existing != incoming) {
            // 框架路径：取「非空且更长（嵌套更深、更具体）」的那条，避免任一通道（绑定/控制台）
            // 因各自 parsePick 出的 RoleEntry 独立、互相覆盖时把已补全的 framePath 抹掉。
            // 例：控制台通道先到（__enrichState 已含 framePath）而绑定通道后到（computeFramePath 回补），
            // 或反之——二者皆非空时以更深的链为准；任一为空则继承另一方，杜绝「iframe 元素生成缺 switchToFrame」。
            List<String> inFp = incoming.getFramePath();
            List<String> exFp = existing.getFramePath();
            boolean inEmpty = (inFp == null || inFp.isEmpty());
            boolean exEmpty = (exFp == null || exFp.isEmpty());
            // existing 是最终写回 map 的权威对象（见下方 return），补全须同时落到 existing 与 incoming，
            // 避免返回 existing 时丢失 incoming 补全出的 framePath（否则 iframe 元素生成缺 switchToFrame）。
            if (inEmpty && !exEmpty) {
                incoming.setFramePath(exFp);
                existing.setFramePath(exFp);
            } else if (!inEmpty && !exEmpty && exFp.size() > inFp.size()) {
                // 旧条目链更深（更具体）→ 用旧链；同深时保留新条目（已是 incoming）
                incoming.setFramePath(exFp);
                existing.setFramePath(exFp);
            } else if (!inEmpty && !exEmpty && inFp.size() > exFp.size()) {
                // 新条目链更深 → 用新链补全 existing
                existing.setFramePath(inFp);
            }
            // 对话框标记：任一为 true 即视为触发（onDialog 回写 / 浏览器侧 hook 检测）
            if (!incoming.isDialog() && existing.isDialog()) {
                incoming.setDialog(true);
                existing.setDialog(true);
                if (existing.getDialogType() != null) { incoming.setDialogType(existing.getDialogType()); existing.setDialogType(existing.getDialogType()); }
                if (existing.getDialogAction() != null) { incoming.setDialogAction(existing.getDialogAction()); existing.setDialogAction(existing.getDialogAction()); }
            }
            // 弹窗标记：任一为 true 即视为触发（onPopup 回写）
            if (!incoming.isPopup() && existing.isPopup()) { incoming.setPopup(true); existing.setPopup(true); }
        }
        // 【修复"同一元素重复点击，序号数组被回传 null/旧值覆盖"——覆盖 existing==incoming 同引用场景】
        // 现象（22:36 日志实证）：i18n 元素 label 第三次/四次点击时，dup-send 正确发出 pickNos=[2,5,6,7]，
        // 但 Java 端收到一条「不含 _pickNos 字段」的二次回传（与 existing 同引用），parsePick 以
        // setPickNos(parsePickNos(null)) 把它抹成残缺值，且因 incoming==existing 跳过了原并集保护，
        // 最终内存态只残留 [2]。
        // 故将 pickNos 并集保护【移出 existing!=incoming 判定】：无论引用是否相同，只要 existing 已持有
        // 更大的序号集合，就用「existing ∪ incoming」去重后的更大集覆盖，任一通道传来的 null/残缺值都不会
        // 抹掉已累积的完整序号。并集为空才保留 incoming 原值（兼容首条无序号的候选）。
        if (existing != null) {
            java.util.List<Integer> inNos = incoming.getPickNos();
            java.util.List<Integer> exNos = existing.getPickNos();
            // 【修复"普通拾取回传把累积序号[4,5]冲成[1,2,3]"】
            // 旧实现用 exNos ∪ inNos 并集：当浏览器侧经 repickNos 已把序号重排成 [4,5]（Java 权威态已正确），
            // 又来一条 dup 二次投递 / iframe 自扫回传携带的"部分快照"（如 [1,2] 或 [2]）时，
            // 并集会变成 [1,2,4,5]→重排成 [1,2,3,4]，或 incoming 直接覆盖成短值 [1,2,3]，
            // 把面板真实累积的 [4,5]/[6,7] 抹掉；后续常规 syncPanelToBrowser(overwrite=false)
            // 再以这个被污染的 Java 态为准重建浏览器面板 → 面板序号退回 [1,2,3]。
            // 修复：普通拾取回传【不拥有重置/覆盖全局序号的权限】——只在 incoming 携带的 _pickNos
            // 比 existing 更"完整/更新"（去重集合更大，或含更大的号）时才用 incoming 替换；
            // 否则保留 existing 的累积序号。唯一能整体覆盖序号的是 repickNos（overwriteNos=true 路径）。
            // 这样既保留"首次无序号候选"的正确初始化，又杜绝短值覆盖长值。
            java.util.List<Integer> chosen = pickMoreComplete(exNos, inNos);
            log.info("[picker][diag-merge] key={} incomingNos={} existingNos={} -> chosenNos={}", key, inNos, exNos, (chosen == null ? "null" : chosen));
            if (chosen != null) {
                // 【修复"dup 回传的完整 [2,5,6,7,9] 被 CONSOLE 空回传覆盖回 [2]"】
                // 旧实现用 `if (existing != incoming) existing.setPickNos(...)` 早退：当 BIND 通道与 CONSOLE 通道
                // 对同一元素的两条回传并发到达时，若 existing 与 incoming 是不同对象，existing（map 内持有引用）
                // 的 pickNos 不会被更新，导致 map 中残留旧短值 [2]；随后 CONSOLE 空回传再以 existing=[2] 兜底，
                // 把刚累积的 [2,5,6,7,9] 彻底抹掉（本日志实证：user_name 最终只剩 [2]）。
                // 修复：始终把 chosen 写回【map 内持有的 existing 对象】（若 existing 存在），并同时更新 incoming；
                // 最后 map.put(key, existing != null ? existing : incoming) 保证 map 引用的是被更新的对象。
                // 这样任一通道携带的完整 _pickNos 都不会被另一通道的 null/残缺值覆盖。
                java.util.List<Integer> __chosenCopy = new java.util.ArrayList<>(chosen);
                if (existing != null) existing.setPickNos(__chosenCopy);
                incoming.setPickNos(__chosenCopy);
            }
        }
        map.put(key, existing != null ? existing : incoming);
        return existing != null ? existing : incoming;
    }

    /**
     * 把「删除回传」原始数组统一折算为要移除的内存态 map key 集合。
     * 兼容两种格式：
     * ① 完整 pick 对象数组（新格式）：对每个 pick 用与入库时完全一致的 {@link #pickDedupKey} 重算 key，
     *    从而精确命中「定位器唯一型策略」（key=_sig）与「role 策略」（key=_sigKey）——这是修复"删除无效"的关键；
     * ② 纯 key 字符串数组（旧格式兼容）：直接作为待删键。
     */
    @SuppressWarnings("unchecked")
    static java.util.Set<String> collectDeleteKeys(List<?> raw) {
        java.util.Set<String> dead = new java.util.HashSet<>();
        if (raw == null) return dead;
        for (Object o : raw) {
            if (o == null) continue;
            if (o instanceof Map) {
                Map<Object, Object> m = (Map<Object, Object>) o;
                RoleEntry e = parsePick(m);
                if (e != null) {
                    // 多通道兜底：javaPickBySig 的真实 key 由 pickDedupKey 决定（方案 B 下已绑定 pageClass）。
                    // 主删除键 k1 即 pickDedupKey，与入库 key 完全同构，确保精确命中；其余 _sig/_sigKey/
                    // sigKey 形态仅作冗余兜底（互不重复加入）。
                    // 【方案 B】删除原「去索引兜底」分支：它把 _sig 去 #index 后（如 "role:link:Privacy...footer"）
                    // 作为跨页共享键加入 dead，会导致删某页页脚时其它页同名页脚被一并清除（误删）。
                    // 方案 B 下 locator 入库键本身就是「pageClass|去索引locatorKey」，k1 已能稳定命中，
                    // 不再需要也不允许去索引跨页兜底。
                    String pc = (e.getPageClass() != null) ? e.getPageClass() : "";
                    String k1 = pickDedupKey(m, e);
                    if (k1 != null && !k1.isEmpty()) dead.add(k1);
                    String k2 = asString(m.get("_sig"));
                    // 定位器型策略的 _sig 可能带 #index，补一个「带 pageClass 前缀」形态以兼容旧数据，
                    // 但务必绑定 pageClass，绝不退化为跨页共享键（否则 SetupSecondPwdPage 删页脚会把
                    // LoginPage 同名页脚的裸 _sig 一并加入 dead，后续 isDeletedKeyInState 又按裸 _sig
                    // 把另一页同名元素永久屏蔽）。故 k2 也一律带 pc 前缀。
                    if (k2 != null && !k2.isEmpty()) {
                        String k2pc = pc + "|" + k2;
                        if (!k2pc.equals(k1)) dead.add(k2pc);
                        if (k2.length() > 2 && Character.isDigit(k2.charAt(k2.length() - 1))) {
                            String k2base = pc + "|" + k2.replaceAll("#\\d+$", "");
                            if (!k2base.isEmpty() && !dead.contains(k2base)) dead.add(k2base);
                        }
                    }
                    String k3 = asString(m.get("_sigKey"));
                    if (k3 != null && !k3.isEmpty() && !k3.equals(k1) && !k3.equals(k2)) dead.add(k3);
                    String k4 = e.getSigKey();
                    if (k4 != null && !k4.isEmpty() && !dead.contains(k4)) dead.add(k4);
                }
            } else {
                String s = String.valueOf(o);
                if (!s.isEmpty()) dead.add(s);
            }
        }
        return dead;
    }

    /** 把一次拾取返回的 map 解析为 {@link RoleEntry}（getEntries 与 getSteps 共用，保证解析一致）。 */
    @SuppressWarnings("unchecked")
    static RoleEntry parsePick(Map<Object, Object> m) {
        String strategy = asString(m.get("strategy"));
        if (strategy == null || strategy.isBlank()) {
            strategy = "role";
        }
        String role = asString(m.get("role"));
        String name = asString(m.get("name"));
        String tag = asString(m.get("tag"));
        String text = asString(m.get("text"));
        boolean popup = Boolean.parseBoolean(asString(m.get("popup")));
        boolean download = Boolean.parseBoolean(asString(m.get("download")));
        boolean hover = Boolean.parseBoolean(asString(m.get("hover")));
        boolean dblClick = Boolean.parseBoolean(asString(m.get("dblclick")));
        boolean closeOp = Boolean.parseBoolean(asString(m.get("_closeOp")));
        // 原生对话框（alert/confirm/prompt）：前端拦截后打标记，Java 侧解析并映射到 RoleEntry
        boolean dialog = Boolean.parseBoolean(asString(m.get("dialog")));
        String dialogType = asString(m.get("dialogType"));
        String dialogAction = asString(m.get("dialogAction"));
        if (dialogType != null && dialogType.isBlank()) dialogType = null;
        if (dialogAction != null && dialogAction.isBlank()) dialogAction = null;
        // 下拉选择（combobox/listbox）：选中项可见文本 + 选项值（对齐 codegen selectOption 信号）
        boolean select = Boolean.parseBoolean(asString(m.get("select")));
        String optionText = asString(m.get("optionText"));
        if (optionText != null && optionText.isBlank()) optionText = null;
        String optionValue = asString(m.get("optionValue"));
        if (optionValue != null && optionValue.isBlank()) optionValue = null;
        // 复选框勾选状态：true=已勾选（check()）/ false=未勾选（uncheck()）/ null=非复选框
        Boolean checked = null;
        String checkedRaw = asString(m.get("checked"));
        if (checkedRaw != null && !checkedRaw.isBlank()) checked = Boolean.parseBoolean(checkedRaw);
        // 复选框「目标」勾选状态（对齐 page.pause 的 setChecked）：JS 侧在 checkbox 点击后写入的
        // checked 即「操作后」状态，作为 setChecked 的目标值（setChecked 在已满足时幂等跳过，避免误 toggle）。
        Boolean setCheckedTarget = checked;
        // 键盘序列（对齐 page.pause 的 press("Enter")）：用户在输入框聚焦态按的实质按键（非字符输入）。
        String pressKey = asString(m.get("pressKey"));
        if (pressKey != null && pressKey.isBlank()) pressKey = null;
        // 拖拽目标元素定位签名（对齐 page.pause 的 dragTo）：仅拖拽源 pick 非 null。
        String dragDstKey = asString(m.get("dragDstKey"));
        if (dragDstKey != null && dragDstKey.isBlank()) dragDstKey = null;
        // 可访问状态过滤属性（对齐 page.pause() 的 getByRole setDisabled/setPressed/setExpanded）。
        // JS 侧 done() 仅当元素确有该状态时写入 "YES"/"NO"（见 RoleElement.State 三态语义）。
        RoleElement.State disabled = toState(asString(m.get("disabled")));
        RoleElement.State pressed = toState(asString(m.get("pressed")));
        RoleElement.State expanded = toState(asString(m.get("expanded")));
        int index = parseIndex(m.get("index"));
        int level = parseLevel(m.get("level"));
        // 浏览器端固化的元素永久身份键：所有入站链路（exposeBinding 回传 / console 兜底 / 快照读取）
        // 都经本方法解析，故在此统一透传，保证 Java 内存态实体始终携带 _sigKey，
        // 供 syncPanelToBrowser 原样回灌浏览器（详见 RoleEntry#getSigKey 的根因说明）。
        String pickSigKey = asString(m.get("_sigKey"));
        if (pickSigKey != null && pickSigKey.isBlank()) pickSigKey = null;
        if ("role".equals(strategy)) {
            if (role == null && !closeOp) return null;   // 角色策略但无角色：跳过（关闭操作标记除外）
            String resolvedKey = asString(m.get("key"));
            if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
            boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
            String value = asString(m.get("value"));
            if (value != null && value.isBlank()) value = null;
            RoleEntry roleEntry = new RoleEntry(role, name, tag, text, "role", null, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick, dialog, dialogType, dialogAction, select, optionText, optionValue, checked, setCheckedTarget, pressKey, dragDstKey, disabled, pressed, expanded);
            roleEntry.setSigKey(pickSigKey);
            int roleCount = parseCount(m.get("count"));
            roleEntry.setCount(roleCount);
            roleEntry.setFramePath(parseFramePath(m.get("framePath")));
            // 透传「归属空间」与「连续编号」：space 标注元素位于哪个 iframe/shadow，seq 为用户勾选连续序号。
            String rSpace = asString(m.get("space"));
            if (rSpace != null && !rSpace.isBlank()) roleEntry.setSpace(rSpace);
            roleEntry.setShadowPath(parseFramePath(m.get("shadowPath")));
            // 【修复"step 序号错乱（后拾取元素首号偏小）"】seq 是排序基准，必须反映"用户首次点击该元素的真实动作号"，
            // 只增不回退，不能被 _pickNos 污染。
            // 旧逻辑 seq = _pickNos[0]：但 _pickNos 是会被 Java 每轮 syncPanelToBrowser 整体重建的数组，
            // 并发回传竞态下 Java 权威态可能只持有短值（如 user_name 仅 [2]），重建后浏览器侧 _pickNos 退化成短值，
            // 于是 seq 跟着变成 2，导致"后点的元素排到前面 / 序号错乱"。
            // 修复：优先取 _pickSeq（浏览器侧由只增不回退的 __pickOrder 固化，= 首次真实动作号），
            // 其次才回退 _pickNos 首号 / 原 seq，使顺序严格等于用户点击先后。
            roleEntry.setSeq(parseSeq(m.get("_pickSeq")));
            if (roleEntry.getSeq() == 0) {
                List<Integer> __pn = parsePickNos(m.get("_pickNos"));
                if (__pn != null && !__pn.isEmpty()) roleEntry.setSeq(__pn.get(0));
                else roleEntry.setSeq(parseSeq(m.get("seq")));
            }
            roleEntry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
            roleEntry.setUrl(asString(m.get("_url")));
            roleEntry.setPickNos(parsePickNos(m.get("_pickNos")));
            return roleEntry;
        }
        String selector = buildSelector(strategy, m);
        if (selector == null || selector.isBlank()) return null;
        String resolvedKey = asString(m.get("key"));
        if (resolvedKey != null && resolvedKey.isBlank()) resolvedKey = null;
        boolean cleaned = Boolean.parseBoolean(asString(m.get("cleaned")));
        String value = asString(m.get("value"));
        if (value != null && value.isBlank()) value = null;
        RoleEntry entry = new RoleEntry(role, name, tag, text, strategy, selector, resolvedKey, cleaned, value, popup, index, download, asString(m.get("_pageClass")), hover, closeOp, level, dblClick, dialog, dialogType, dialogAction, select, optionText, optionValue, checked, setCheckedTarget, pressKey, dragDstKey, disabled, pressed, expanded);
        entry.setSigKey(pickSigKey);
        int nonRoleCount = parseCount(m.get("count"));
        entry.setCount(nonRoleCount);
        entry.setFramePath(parseFramePath(m.get("framePath")));
        // 透传「归属空间」与「连续编号」：space 标注元素位于哪个 iframe/shadow，seq 为用户勾选连续序号。
        String nrSpace = asString(m.get("space"));
        if (nrSpace != null && !nrSpace.isBlank()) entry.setSpace(nrSpace);
        entry.setShadowPath(parseFramePath(m.get("shadowPath")));
        // 【修复"step 序号错乱（后拾取元素首号偏小）"】同角色策略分支：优先 _pickSeq（只增不回退的真实首次动作号），
        // 其次才回退 _pickNos 首号 / 原 seq，使排序严格等于用户点击先后，不被短值污染的 _pickNos 干扰。
        entry.setSeq(parseSeq(m.get("_pickSeq")));
        if (entry.getSeq() == 0) {
            List<Integer> __pn = parsePickNos(m.get("_pickNos"));
            if (__pn != null && !__pn.isEmpty()) entry.setSeq(__pn.get(0));
            else entry.setSeq(parseSeq(m.get("seq")));
        }
        entry.setPageInstanceId(parseInstanceId(m.get("_pageInstanceId")));
        entry.setPickNos(parsePickNos(m.get("_pickNos")));
        return entry;
    }

    /** 从 readPickStateJson 的快照 JSON 解析出某页的拾取列表（页面已关闭时回退用）。 */
    @SuppressWarnings("unchecked")
    static List<RoleEntry> entriesFromState(String json) {
        List<RoleEntry> r = new ArrayList<>();
        Map<String, Object> m = parseState(json);
        Object picks = m.get("picks");
        if (picks instanceof List) {
            for (Object o : (List<?>) picks) {
                if (o instanceof Map) {
                    RoleEntry e = parsePick((Map<Object, Object>) o);
                    if (e != null) r.add(e);
                }
            }
        }
        return r;
    }

    /** 从 readPickStateJson 的快照 JSON 解析出某页的 step 序列（页面已关闭时回退用）。 */
    @SuppressWarnings("unchecked")
    static List<List<RoleEntry>> stepsFromState(String json) {
        List<List<RoleEntry>> r = new ArrayList<>();
        Map<String, Object> m = parseState(json);
        Object steps = m.get("steps");
        if (steps instanceof List) {
            for (Object o : (List<?>) steps) {
                List<RoleEntry> step = new ArrayList<>();
                if (o instanceof List) {
                    for (Object it : (List<?>) o) {
                        if (it instanceof Map) {
                            RoleEntry e = parsePick((Map<Object, Object>) it);
                            if (e != null) step.add(e);
                        }
                    }
                }
                r.add(step);
            }
        }
        return r;
    }

    /** 把 readPickStateJson 产出的 JSON 解析为 Map（容错：异常/空返回空 Map）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseState(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = GSON.fromJson(json, MAP_STRING_OBJECT_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 解析一组元素序号；缺省 / 非数值 / 负值均归一为 -1（唯一匹配，无需 nth）。 */
    static int parseIndex(Object v) {
        if (v == null) return -1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i >= 0 ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析定位器匹配总数；缺省 / 非数值 / 非正均归一为 1（唯一匹配）。 */
    static int parseCount(Object v) {
        if (v == null) return 1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i > 0 ? i : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 解析用户勾选连续序号（seq）：缺省 / 非数值 / 非正数均归一为 0（未编号）。 */
    static int parseSeq(Object v) {
        if (v == null) return 0;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i > 0 ? i : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析页面实例序号；缺省 / 非法 / <1 均归一为 1（首个实例）。 */
    static int parseInstanceId(Object v) {
        if (v == null) return 1;
        try {
            int i = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return i >= 1 ? i : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 解析全局拾取顺序号数组（_pickNos）：浏览器侧为 [1,4,7] 形式的数字数组。
     *  缺省 / 非数组 / 元素非数值均归一为 null（面板将走 __rolePicks 位次兜底）。去重保序、剔除<=0。
     *  【修复"回传数组乱序（如 [12,13,14,15,3] 未升序）"】：解析完成后强制升序排序，
     *  无论浏览器侧传入时顺序如何，进入 Java 权威内存态的 pickNos 数组始终升序，杜绝面板显示乱序。 */
    @SuppressWarnings("unchecked")
    static java.util.List<Integer> parsePickNos(Object v) {
        if (!(v instanceof java.util.List)) return null;
        java.util.List<?> raw = (java.util.List<?>) v;
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Object o : raw) {
            if (o == null) continue;
            int n;
            try { n = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString().trim()); }
            catch (NumberFormatException e) { continue; }
            if (n > 0 && !out.contains(n)) out.add(n);
        }
        if (out.isEmpty()) return null;
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 在「普通拾取回传合并」时，合并 existing 与 incoming 两侧 _pickNos。
     * 规则：返回两个集合的并集（去重、保序、升序排列）。
     * 目的：同一元素多次点击时，序号应该累积（如 [1] + [2] = [1,2]），而非选择其中一个。
     * 日志实证：第二轮拾取时，incoming=[2] existing=[1]，旧实现选择 [2] 导致序号被覆盖而非累积。
     */
    static java.util.List<Integer> pickMoreComplete(java.util.List<Integer> exNos, java.util.List<Integer> inNos) {
        java.util.Set<Integer> exSet = (exNos == null) ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(exNos);
        java.util.Set<Integer> inSet = (inNos == null) ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(inNos);
        if (exSet.isEmpty() && inSet.isEmpty()) return null;
        if (exSet.isEmpty()) return new java.util.ArrayList<>(inSet);
        if (inSet.isEmpty()) return new java.util.ArrayList<>(exSet);
        // 【关键修复】始终返回并集，使序号累积（如 [1] + [2] = [1,2]）
        java.util.LinkedHashSet<Integer> uni = new java.util.LinkedHashSet<>(exSet);
        uni.addAll(inSet);
        // 按升序排列，确保序号连续
        java.util.List<Integer> result = new java.util.ArrayList<>(uni);
        java.util.Collections.sort(result);
        return result;
    }

    /** 解析标题层级；缺省 / 非数值 / 非 1–6 均归一为 0（不限层级）。 */
    static int parseLevel(Object v) {
        if (v == null) return 0;
        try {
            int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return (n >= 1 && n <= 6) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析 iframe 嵌套路径：浏览器侧以数组回传（如 ["iframe[name=\"a\"]","#b"]），缺省/非数组返回 null。 */
    @SuppressWarnings("unchecked")
    static List<String> parseFramePath(Object v) {
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) out.add(o.toString());
            }
        } else if (v instanceof String) {
            String s = v.toString().trim();
            if (s.startsWith("[") && s.endsWith("]")) {
                // JSON 数组字符串（console 兜底回传场景）：简单切分
                String inner = s.substring(1, s.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String p = part.trim();
                        if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
                        if (!p.isEmpty()) out.add(p);
                    }
                }
            } else if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 把 JS 侧 done() 写入的可访问状态字符串（"YES"/"NO"）解析为 {@link RoleElement.State}；其余一律 null（不限）。 */
    static RoleElement.State toState(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.equalsIgnoreCase("YES")) return RoleElement.State.YES;
        if (s.equalsIgnoreCase("NO")) return RoleElement.State.NO;
        return null;
    }

    /**
     * 从拾取脚本返回的原始片段构建 Playwright 字符串选择器（在 Java 侧统一转义，
     * 避免在 JS 文本块里处理引号转义）。
     */
    static String buildSelector(String strategy, Map<Object, Object> m) {
        switch (strategy) {
            case "testid":
            case "placeholder":
            case "altText":
            case "title": {
                String attr = asString(m.get("attr"));
                String value = asString(m.get("value"));
                if (attr == null || value == null) return null;
                return "[" + attr + "=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "i18n": {
                String value = asString(m.get("value"));
                if (value == null || value.isBlank()) return null;
                return "[data-i18n=\"" + escapeSelectorValue(value) + "\"]";
            }
            case "text": {
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "text=\"" + escapeSelectorValue(name) + "\"";
            }
            case "label": {
                // 对齐 page.pause 的 getByLabel：selector 仅作占位/人工核对，
                // 生成注解与运行期定位均走 @RoleElement(label=...) → byLabel。
                String name = asString(m.get("name"));
                if (name == null || name.isBlank()) return null;
                return "label=\"" + escapeSelectorValue(name) + "\"";
            }
            case "id": {
                String id = asString(m.get("id"));
                return (id == null || id.isBlank()) ? null : "#" + id;
            }
            case "css": {
                String css = asString(m.get("css"));
                return (css == null || css.isBlank()) ? null : css;
            }
            default:
                return null;
        }
    }

    /** 转义选择器值中的反斜杠与双引号，供 CSS 属性选择器 / text= 引擎安全使用。 */
    static String escapeSelectorValue(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
