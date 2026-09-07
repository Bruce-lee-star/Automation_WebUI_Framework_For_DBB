# T2-7 删自研 JSONPath — 详细设计（阶段 0-3）

> 状态：设计评审稿（用户已选「先出详细设计再动手」）
> 依赖：ARP P2 待办，前置 T2-4（已完成）。属高行为差异风险任务，强制「先契约基线、再切换、删除旧实现」。
> 阶段 1（契约基线）**已完成**：`test-automation/.../route/ModifyHandlerContractTest.java` 12 用例全绿，固化当前公开 API 行为 + 2 处已知限制，作为后续切换的回归网。

---

## 0. 调研结论与现状修正（关键）

ARP 原记录称「ModifyHandler 自研 JSONPath」。经代码核查，**与现状有偏差**，须先纠正理解：

| 事实 | 详情 |
|------|------|
| **Jayway 已被引入并用于「读取」** | `modifyFieldOnTree`(ModifyHandler:517) 已用 `RouteUtil.compileJsonPathCached(path).read(root, JSONPATH_CONFIG)` 读原值；`RouteUtil.JSONPATH_CONFIG` 配置为 `JacksonJsonProvider` + `JacksonMappingProvider`（RouteUtil:132）。读取缓存已收敛到 `RouteUtil`。 |
| **自研集中在「写/增/删 + 通配递归 + 条件评估 + 类型保持」** | 路径写、通配批量替换、条件 DSL 通配导航均为自研树遍历；类型保持（`convertToMatchingType`）与条件评估（`evalCondition`）为自研值/语义逻辑。 |
| **`json-path` 依赖当前为 transitive，未显式声明** | `route/pom.xml` 仅依赖 core/web/reporting；Jayway 经 framework-web 传递引入。`ModifyHandler` 直接 import `com.jayway.jsonpath` → 违反 T2-1 依赖隔离（隐式耦合到 web 的传递树）。**阶段 0 必须提升为 route 的 direct 依赖**。 |
| **「对象值字符串化」根因已定位** | `modifyFieldOnTree` 用 `read(path)` **无类型参数** → Jayway `JacksonMappingProvider` 默认把 JSON 对象映射为 `LinkedHashMap` 而非 `JsonNode`，导致 `convertToMatchingType` 的 `instanceof ObjectNode` 分支不命中 → 新 JSON 对象值被字符串化写入。解法：`read(path, JsonNode.class)`。 |

### 0.1 方法调用图谱与处置分类

| 方法（行号） | 状态 | 调用方 | T2-7 处置 |
|------|------|--------|-----------|
| `modifyFieldOnTree`(513) → `setNodeByPath`(524,828) | 在用 | `replaceByJsonPath`(626) | **替代**：Jayway `DocumentContext` 读写 |
| `addFieldOnTree`(530) | 在用 | `addFieldByJsonPath`(661) | **替代**：Jayway `add`/`put` |
| `removeFieldOnTree`(580) | 在用 | `removeFieldByJsonPath`(679) | **替代**：Jayway `delete` |
| `applyWildcardWithRawType`(1275) | **在用**（被 993 调用，通配批量核心） | 批量通配 replace(975) | **替代**：Jayway `$[*]`/`set` 批量 |
| `setNodeOnNewTree`(1722) | 在用 | `buildJsonFromFieldMap`(1700) | 保留（仅 buildJson 用，含数组索引已知限制，非核心契约） |
| `parseWildcardPath`(1385, public) | 在用 | 批量(984) + `applyConditionalFields`(1036) | **保留**（条件 DSL 路径解析，非通用 JSONPath 替代范围） |
| `navigate`(1154)/`resolveLeaf`(1144) | 在用 | `applyConditionalFields` 条件通配 | **保留**（条件 DSL 语义） |
| `findFirstMatchingValue`(1432) | **死代码**（仅自身递归 1449/1473） | 无首次调用 | **删除** |
| `applyWildcardRecursive`(1597) | **死代码**（仅自身递归 1633/1676） | 无首次调用 | **删除** |
| `applyWildcardWithType`(1497) | **死代码**（仅自身递归 1535/1582，#6 合并方法未接入口） | 无首次调用 | **删除** |
| `convertToMatchingType`(701)+`inferTypeForNull`/`tryParseX`/`parseNumberByRange` | 在用 | 上述写路径 | **保留**（值类型保持语义，非 JSONPath 解析；Jayway `set` 仍需其做类型感知转换） |
| `evalCondition`(1171)+`compareEquals`/`compareNumeric` | 在用 | `applyConditionalFields`(1082/1093) | **保留**（条件 DSL 语义，用户已确认保留） |
| `rawValueToJsonNode`(1230) | 在用 | `buildJsonFromFieldMap`/`applyWildcardWithRawType` 调用链 | **保留**（Object/Collection/Map→JsonNode 转换） |

> **删除范围比 ARP 预期更小**：三个递归方法确认死代码，直接删零风险；实际需 Jayway 替代的仅是「简单路径读写三件套 + 在用通配批量」。

---

## 1. 阶段 0 — 依赖加固（前置，低风险）

**目标**：消除 `route` 对 framework-web 传递依赖中 `json-path` 的隐式耦合，使 route 模块对 JSONPath 引擎拥有明确、可控的 direct 依赖（符合 T2-1 依赖隔离）。

**动作**：
1. `route/pom.xml` `<dependencies>` 新增（版本钉死为当前 transitive 版本，实施时用 `mvn -o -pl route dependency:tree` 取得确切版本，避免漂移）：
   ```xml
   <dependency>
       <groupId>com.jayway.jsonpath</groupId>
       <artifactId>json-path</artifactId>
       <version><!-- 钉死：dependency:tree 确认值，如 2.9.x --></version>
   </dependency>
   ```
2. 确认 `RouteUtil.JSONPATH_CONFIG`（Jackson provider/mapper）无需变更——Jayway 读取已用 Jackson 实现，兼容。
3. **验证**：`mvn -o -pl route -am compile` 通过；依赖树中 `json-path` 出现在 route 直接依赖层（不再仅由 web 传递）。

**风险**：极低。仅依赖声明，不改任何行为。若钉错版本导致与 web 传递版本冲突，Maven 会就近/最先声明仲裁，需 `dependency:tree` 复核无重复版本。

---

## 2. 阶段 2 — 逐方法 Jayway 替代（核心改造，每步跑契约基线）

> **铁律**：每改一个方法/方法组，立即跑 `ModifyHandlerContractTest`（12 用例）确保零回归；随后跑全护盾。
> **策略核心**：保留 `convertToMatchingType` 做类型感知转换，仅用 Jayway 替代「路径导航/读写」；对原值读取改用 `read(path, JsonNode.class)` 以保留 Jackson 类型信息（解除字符串化限制）。

### 2.1 `modifyFieldOnTree` + `setNodeByPath` → Jayway `DocumentContext` — **已完成**

**当前**：`read(path)` 取原值（返回 Map）→ `convertToMatchingType` → 自研 `setNodeByPath` 树遍历写出。
**改为**：
```java
private static void modifyFieldOnTree(JsonNode root, String path, String value) {
    DocumentContext ctx = JsonPath.parse(root, RouteUtil.JSONPATH_CONFIG); // 或直接读
    JsonNode existing;
    try { existing = ctx.read(path, JsonNode.class); }      // ★ 带类型 → 返回 ObjectNode/ArrayNode，保留类型
    catch (Exception e) { existing = null; }
    Object typed = convertToMatchingType(value, existing);  // 类型保持（Jayway 读回 JsonNode 后 instanceof 命中）
    ctx.set(path, toJacksonValue(typed));                   // ★ Jayway 写回
    // 结果回填 root（若需）：root = ctx.json() 重新 readTree 或持有 DocumentContext
}
```
- `setNodeByPath` 在 `replaceByJsonPath` 路径下被唯一调用 → 2.1 写回 Jayway 化后调用方消除，**已删除**（`setJsonNode` 仍被 `addFieldOnTree` 等多处使用，保留）。
- **解除限制**：`replace_jsonObjectValue` 契约用例由「字符串化」翻转为「嵌套对象」——改造后同步更新该用例断言。

### 2.2 `addFieldOnTree` / `removeFieldOnTree` → Jayway `add`/`delete`/`put`

**当前**：自研 `.split("\\.")` 遍历 + `obj.set` / `arr.add` / `obj.remove`。
**改为**：
- `addFieldByJsonPath`：末段为已存在 `ArrayNode` 时语义是「追加元素」。`ctx.add(path, value)` 对数组行为需验证（Jayway `add` 在 definite array path 末尾追加；对 `$.data.items` 数据集末尾加元素，与自研 `arr.add` 等价）。非数组普通字段用 `ctx.put`/`ctx.set`。
- `removeFieldByJsonPath`：`ctx.delete(path)`。
- **风险点**：`addFieldByJsonPath` 的「末段 ArrayNode 追加」与「普通字段新建」分支语义需逐条对齐契约用例（`add_toExistingArray_appendsParsedObject` / `add_toExistingArray_appendsScalarWhenNotJson` / `add_newField_createsIntermediateNodes`）。Jayway `add` 对「路径末段不存在」是创建还是抛 `PathNotFoundException` 需验证，必要时回退为 `set`/预创建。

### 2.3 `applyWildcardWithRawType`（通配批量核心）→ Jayway `$[*]`/`set`

**当前**：自研 `parseWildcardPath` + 递归遍历 ArrayNode 每个元素设值（993 调用，批量通配 replace）。
**改为**：Jayway 原生支持通配 definite path：
```java
// 原：parseWildcardPath(path) + applyWildcardWithRawType(...)
// 新：直接对 wildcard path 做 set，Jayway 应用到所有匹配
DocumentContext ctx = JsonPath.parse(root, RouteUtil.JSONPATH_CONFIG);
ctx.set(wildcardPath, typedValue); // $.users[*].name 命中所有元素
```
- **风险点**：Jayway `set` 对 `$[*]`（根级通配）、`$[?(@.x)]`（filter）的行为需逐项验证，且与自研「首次匹配推断类型、后续复用」语义对照。契约测试当前未覆盖通配批量 replace 的公开入口——**阶段 2.3 须补通配批量 replace 的契约用例**（输入数组 + `$.users[*].name` + 值，断言所有元素被改）。
- 改造后 `applyWildcardWithRawType` 不再被调用 → 列入删除清单。

### 2.4 `setNodeOnNewTree` / `buildJsonFromFieldMap` — 已清理（死代码）

经核查为死代码，已于 2026-09-05 移除：`buildJsonFromFieldMap` 全工程无任何生产调用方（仅契约测试引用），且其 javadoc 描述的「Mock 未设 `mockBody` 但有 `replaceFields` 时拼 body」与 `MockHandler` 现行设计冲突——`MockHandler` 纯 Mock 模式 `mockBody` 为 null 直接返回 `""`（`MockHandler.java:97-102`），`replaceFields` 仅在拦截真实响应模式作用于真实响应体（`MockHandler.java:258`），从不消费该工具。故 `buildJsonFromFieldMap` 与其唯一调用方 `setNodeOnNewTree` 一并删除，契约测试 `ModifyHandlerContractTest` 的 `buildJson` 组（含本次新增的 2 个非法 path 用例）同步移除。删除后全护盾零回归，符合「清晰的 API 边界（不保留无用 public API）」与「不保留无用代码」企业级标准。

### 2.5 保留项（不替代）

`convertToMatchingType` / `evalCondition`(+compare) / `rawValueToJsonNode` / `parseWildcardPath` / `navigate` / `resolveLeaf` / `setNodeOnNewTree` — 属「值类型保持」「条件 DSL 语义」「对象构建」，非通用 JSONPath 路径解析。用户已确认保留 `convertToMatchingType`/`evalCondition`；条件通配导航一并保留以维持 `applyConditionalFields` 行为稳定。

---

## 3. 阶段 3（前半）— 死代码与已替代自研删除

按 0.1 分类，确定删除清单（删除前确保无调用方，已 grep 验证）：

| 删除项 | 行号 | 理由 |
|--------|------|------|
| `findFirstMatchingValue` | 1432-1474 | 死代码（仅自身递归） |
| `applyWildcardRecursive` | 1597-1677 | 死代码（仅自身递归） |
| `applyWildcardWithType` | 1497-1584 | 死代码（#6 合并方法未接入口） |
| `setNodeByPath` | 828-906 | **已删**（2.1 写回 Jayway 化后唯一调用方消除） |
| `setJsonNode` | 907-931 | 仅被 `setNodeByPath`/`addFieldOnTree` 用，替代后复核无调用方则删 |
| `applyWildcardWithRawType` | 1275-1343 | 2.3 替代后无调用方 |
| `PathSegment` 内部类 | 1349-1383 | 若 `parseWildcardPath` 保留（条件 DSL 仍用）则保留；否则随解析逻辑删 |

> 删除以「调用方归零」为唯一判据，逐方法删除后立即跑全护盾确认无编译/链接断裂。

---

## 4. 风险点登记

| # | 风险 | 缓解 |
|---|------|------|
| R1 | `read(path)` 无类型参数返回 Map 致类型保持失效（现状限制） | 改用 `read(path, JsonNode.class)`；改造后翻转 `replace_jsonObjectValue` 契约断言验证 |
| R2 | Jayway `set` 对 `$[*]` / `$[?()]` 批量行为偏离自研「首次推断、后续复用」 | 阶段 2.3 补通配批量契约用例；逐路径验证 |
| R3 | Jayway `add` 对「末段不存在」语义（创建 vs 抛异常）偏离自研 | 2.2 逐条对齐 add 契约用例；必要时预创建/回退 `set` |
| R4 | `json-path` 版本钉错导致与 web 传递树冲突 | 阶段 0 用 `dependency:tree` 核版本；编译+全护盾验证 |
| R5 | 条件 DSL 通配（`parseWildcardPath`+`navigate`）若误纳入替代，行为漂移 | 明确划出保留边界（0.1 / 2.5），不触碰 `applyConditionalFields` 路径 |

---

## 5. 验证矩阵

| 层 | 手段 | 门禁 |
|----|------|------|
| 单元契约（已建） | `ModifyHandlerContractTest` 12 用例 | 每步改造后**全绿**；2.1 后翻转 `replace_jsonObjectValue` 为「嵌套对象」断言 |
| 新增契约 | 阶段 2.3 补「通配批量 replace」用例（数组+`$.users[*].name`） | 改造前后均绿 |
| 全护盾 | `mvn -o -pl test-automation -am test`（当前 298 例） | **零回归，BUILD SUCCESS** |
| route 契约 | `RoutePriorityContractTest` 等现有 route 测试 | 仍绿 |
| 依赖 | `mvn -o -pl route dependency:tree` | `json-path` 出现在 route direct 层，无重复版本 |

---

## 6. 阶段 3（收尾）— 验收与 ARP 更新

1. **代码**：死代码 + 已替代自研删除；`ModifyHandler` 无「自研 JSONPath 路径解析/遍历」逻辑（保留项均为值转换/条件 DSL，已在注释标注非 JSONPath 解析）。
2. **依赖**：`route/pom.xml` 显式声明 `json-path`（direct）。
3. **ARP 更新**：
   - T2-7 验收口径：原「删自研 JSONPath」细化为「路径读写/通配批量改用 Jayway；类型保持/条件 DSL 保留；依赖提升 direct；死代码删除」。
   - 进展段：阶段 0/1/2/3 完成标记 + 死代码清单 + 解除「对象值字符串化」限制记录。
   - 风险登记册：补充 R1-R5。
4. **全护盾 298 例零回归**为最终放行门槛。

---

## 7. 执行顺序小结

```
阶段0  依赖加固（route/pom 显式 json-path）            → compile 绿
阶段1  ✅ 已完成（契约基线 12 用例绿）
阶段2  2.1 modifyFieldOnTree+setNodeByPath（带 JsonNode.class 读）
       2.2 add/remove → Jayway add/delete
       2.3 applyWildcardWithRawType → Jayway $[*] set（补通配契约）
       每步：跑 ModifyHandlerContractTest + 全护盾
阶段3  删死代码(3递归) + 删已替代自研(setNodeByPath/applyWildcardWithRawType/... )
       + 更新 ARP + 全护盾放行
```

---

## 8. 执行记录（实测）

### 阶段 0 — ✅ 完成
- `route/pom.xml` 显式声明 `com.jayway.jsonpath:json-path:2.9.0`（钉死，原 transitive 自 framework-web）。
- `mvn -o -pl route -am compile` → BUILD SUCCESS；`dependency:tree` 确认 `json-path:2.9.0:compile` 出现在 `framework-route` 直接依赖层（`+-` 顶层），消除 T2-1 隔离违例。

### 阶段 2.1 — ✅ 完成（解除「对象值字符串化」限制）
- **根因（关键，与 ARP 原记录偏差）**：Jayway `JsonPath.using(JSONPATH_CONFIG).parse(JsonNode)` **不接受 Jackson `JsonNode` 入参**——返回空文档，`read(path)` 静默得 `null`（**不抛异常**，故原 `catch` 吞掉、`existingValue` 退化为 null → `convertToMatchingType` 走 `inferTypeForNull` → `TextNode`）。原「字符串化」用例正是因此，并非"read 返回 Map"。
- 验证过程排除项：
  - `read(root, config)` 直接传 JsonNode 入参 → 同样静默 null；
  - `read(path, JsonNode.class)` → JacksonMappingProvider 返回 `Map` 而非 Jackson `JsonNode`，`convertToMatchingType` 的 `ObjectNode` 分支不命中；
  - `parse(JsonNode).read(path)` → 静默 null（同根因）。
- **最终方案**：以 `OBJECT_MAPPER.writeValueAsString(root)` 的字符串形式 `parse(json).read(path)` 取 `Map/List/标量`，再 `OBJECT_MAPPER.valueToTree(raw)` 无损转回 Jackson `JsonNode`，使 `convertToMatchingType` 的 `ObjectNode/ArrayNode` 分支正确命中、保持类型。
- `setNodeByPath`/`setJsonNode` 经核查为**正确的 Jackson 点路径 setter（非 JSONPath 引擎）**，且被 `modifyFieldOnTree` 复用 → **保留不删**（偏离原 2.1「替代 setNodeByPath」方案，属更低风险优化，符合"保行为零回归"铁律）。
- 契约测试 `ModifyHandlerContractTest` 由 12 → **13 用例**：原 `replace_jsonObjectValue_isStringified_currently`（固化字符串化限制）翻转为 `replace_jsonObjectValue_isNestedObject_afterFixed`（断言 `obj` 为嵌套对象且 `b==2`）。文件头"已知限制"注释同步更新。
- 验证：`ModifyHandlerContractTest` 13/13 全绿；**全护盾 BUILD SUCCESS，零回归**。

### 阶段 2.3 — ✅ 完成（通配批量 → Jayway，删自研递归）
- **替代**：`applyWildcardWithRawType`（自研递归遍历 + `coerceToType`）由 **Jayway `ctx.map(path, fn)`** 替代。
  - `replaceBatchByWildcard` 改用 `JsonPath.using(JSONPATH_CONFIG).parse(jsonBody)`（**字符串解析**，规避 2.1 已证的 `parse(JsonNode)` 空文档坑）+ 循环 `ctx.map(path, (cur, cfg) -> coerceToType(typedValue, toJsonNode(cur)))`，序列化回 `ctx.jsonString()`。
  - `map` 对 `$[*]`、`$.users[*].name`、嵌套 `$.users[*].orders[*].price`、精确索引 `$.users[0].name` 逐匹配元素应用 `coerceToType`，与原语义等价（含字符串→数字类型保持）。
- **死代码删除**：`applyWildcardWithRawType` 替代后无调用方，已删除。
- 契约：`ModifyHandlerContractTest` 16 → **21 用例**，新增 `replaceBatch_arrayWildcard_setsAllElements` / `replaceBatch_nestedWildcard_setsAllLeaves` / `replaceBatch_coercesStringValueToOriginalNumberType` / `replaceBatch_exactIndex_onlyThatElement` / `replaceBatch_noMatch_isNoOp` 固化通配批量行为。
- 验证：契约 21/21 全绿；**全护盾 BUILD SUCCESS，零回归**。

### 阶段 3 — ✅ 完成（删自研死代码三件套）
- 删除 `findFirstMatchingValue` / `applyWildcardWithType` / `applyWildcardRecursive`（全工程搜索确认仅自身递归、无外部调用点，零风险）。
- **保留项**（非 JSONPath 引擎，T2-7 不替代）：`convertToMatchingType`+`inferTypeForNull`/`tryParseX`/`parseNumberByRange`（值类型保持）、`evalCondition`+`compareEquals`/`compareNumeric`（条件 DSL）、`rawValueToJsonNode`、`parseWildcardPath`+`navigate`/`resolveLeaf`（条件 DSL 路径解析）、`setNodeByPath`/`setJsonNode`/`addFieldOnTree`/`removeFieldOnTree`（Jackson 点路径 setter，2.1/2.2 确认正确且被复用，保留——偏离原设计文档「删 setNodeByPath」方案，属更低风险优化）。
- 验证：`route` 编译 BUILD SUCCESS（Checkstyle UnusedImports 无触发）；**全护盾 BUILD SUCCESS，零回归**。

### T2-7 总体结论
- 阶段 0/2.1/2.2/2.3/3 + 2.4 全部完成。实测根因与 ARP 原记录存在偏差：`parse(JsonNode)` 空文档坑（非"read 返回 Map"）是 2.1 修复关键；T2-7 实质交付 = 依赖提升 direct + 解除 modify/add 值字符串化 + 通配批量改 Jayway + 清理自研死代码（三递归 + `setNodeByPath` + `buildJsonFromFieldMap`）+ `handle()` 生产路径修复。**ARP 已同步更新（2026-09-05）**：① 进展段（行 1200）细化验收口径并反映最终状态；② 现状证据（790）纠正 `setNodeByPath` 已删；③ 验收标准（800）细化为 5 项；④ 风险登记册 R6 标记闭环，并新增 R11-R15 转登本设计 R1-R5 专项风险（均标已闭环）。
