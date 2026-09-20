# 05 api 模块：REST 测试能力

> 评审范围：`api/src/main/java/.../framework/api/**`（30 java）
> 评审基线：`e11a847`

---

## 一、模块职责

提供 UI 自动化之外的 API 侧测试能力，用于前置造数、后置校验与纯接口用例。分层：

| 包 | 职责 |
|---|---|
| `client` | `ApiJob` 外观 + `AbstractApiJobHelper`，委托 Reader/Writer/PayloadLoader |
| `client/rest` | `AbstractRestJob` 模板方法 + 5 个 HTTP verb 实现 |
| `core/entity` | `Entity` 请求数据载体 + `EntityBuilder`（Typesafe Config 构建） |
| `core/endpoint` / `core/enums` / `core/services` | 端点、枚举、服务编排 |
| `core/step` | `BaseStep`，断言与 JSONPath 提取，供 Cucumber 步骤复用 |
| `assembler` | 请求头/请求体装配（`HeadersAssemblers`） |
| `domain` / `domain/enums` | 领域模型 |
| `config` | `ApiFrameworkConfig` 配置门面 |
| `utility` | `ApiLogSanitizer` 脱敏 |

---

## 二、现状评估

### 2.1 请求组装

`EntityBuilder`（`core/entity/EntityBuilder.java:22-87`）用 Builder 模式从 Typesafe Config 构建请求，`Entity.getRequestHeaders()` 返回防御性副本（`:166-168`）——**防御性拷贝这个细节说明作者有并发与副作用意识**。

`AbstractRestJob.execute()`（`:115-125`）用模板方法，5 个实现只提供 HTTP verb（`RestPostJob:21`），消除了重复。**模板方法在这里用得恰当**。

### 2.2 HTTP 客户端

使用 `net.serenitybdd.rest.SerenityRest`（`AbstractRestJob:8,129`）——选它是因为 `SerenityRest.given()` 会自动产生 Serenity step，API 调用能进报告。这个取舍正确。

配置仅覆盖超时（`:184-188`，`http.connection.timeout` / `http.socket.timeout`）：

- **无连接池配置**（RestAssured 默认无池，每次新建连接）**已修复**：`AbstractRestJob` 经 `httpClientFactory` 注入 `PoolingHttpClientConnectionManager`，max-total/per-route 由 `ApiFrameworkConfig`（`http.connection.pool.max-total`/`max-per-route`，默认 200/20）配置
- **无重试机制** **已修复**：`AbstractRestJob.execute` 新增幂等+5xx+网络错误重试（绝不重试 4xx），重试次数/退避取自 `ApiFrameworkConfig.getRetryCount()/getRetryDelay()`（默认 3 次 / 1000ms）
- **无统一拦截器**（鉴权、日志、脱敏都需要每个 job 自己处理）

SSL 校验默认严格、可放宽（`:50-62`）——**"可放宽"在金融测试框架里是危险开关**，需要有审计日志。

### 2.3 与 Serenity 集成（正面）

`SerenityRest.given()`（`:129`）让每次 API 调用成为报告里的一个 step；`BaseStep` 的断言（`:76-85`、`:91-120`）用 Hamcrest `assertThat` 并在 catch 后 `throw`，**失败能正确标记用例失败**。这一链路是通的。

### 2.4 日志脱敏（高危）

```java
// AbstractRestJob.java:120-122
response.log().all();
// :148
requestSpecification.log().all();
```

`ApiFrameworkConfig.java:107-108` 中该开关**默认为 `true`**。

**这是全框架最直接的敏感信息泄露点**：`log().all()` 会把完整的 request/response 打进日志，包括 `Authorization` 头、`Cookie`、请求体里的 `password`、响应里的 token。

模块里其实**已经有** `ApiLogSanitizer`（`utility/ApiLogSanitizer.java:43,60,70`，委托 `SensitiveDataSanitizer`），但 `log().all()` **完全绕过了它**——RestAssured 直接把原始内容写给自己的 `PrintStream`，不经过 SLF4J，因此 core 层的 `SanitizingMessageConverter` 也拦不住。

**结论：脱敏能力齐全，但没接在这条最需要它的路径上。**

### 2.5 响应处理

`BaseStep` 提供状态码 / JSONPath / header 校验（`:76,91,126,146,159`）。

**JSONPath 引擎两套并存**：`:183-221` 自写 `resolveJsonPath`（Jackson 实现），`:131` 又用 `com.jayway.jsonpath.JsonPath.parse`。两套实现的行为在边界情况下不一致（null 处理、数组通配、函数支持），会给用例编写者带来困惑。

### 2.6 测试覆盖

**`api/src/test` 目录不存在。** 30 个生产类、零单元测试。对比 `core` / `web` / `route` / `reporting` 都有 `src/test`，api 是唯一的空白。

---

## 三、优势

1. **`SerenityRest` 选型正确**，API 调用天然进 Serenity 报告，与 UI 用例报告统一。
2. **模板方法消除 verb 重复**，5 个实现类各只有几十行。
3. **`Entity.getRequestHeaders()` 返回防御性副本**，副作用意识好。
4. **已有 `ApiLogSanitizer` 且委托统一的 `SensitiveDataSanitizer`**，脱敏能力本可直接复用。
5. **断言失败正确抛出异常**，不会静默通过。

---

## 四、风险与问题

| 编号 | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| P-1 | **P0** | `log().all()` 默认开启，明文输出 Authorization/Cookie/请求体/token，**绕过全部脱敏链路** | `AbstractRestJob.java:120-122,148`、`ApiFrameworkConfig.java:107-108` | 凭据随日志与 CI 产物外泄 |
| P-2 | **P0** | `api` 模块**零测试覆盖**（无 `src/test`） | 目录不存在 | 重构无安全网，回归靠人肉 |
| P-3 | **P1** | 无连接池、无重试 **已修复**：`AbstractRestJob` 经 `httpClientFactory` 注入 `PoolingHttpClientConnectionManager`（max-total/per-route 由 `ApiFrameworkConfig` 配置）；`execute` 新增幂等+5xx+网络错误重试（绝不重试 4xx），次数/退避取自 `ApiFrameworkConfig.getRetryCount()/getRetryDelay()` | `AbstractRestJob:184-188` 仅超时 | 串行大量接口调用时握手开销大；网络抖动直接失败 |

> **测试约束（P-3 验证方式）**：`SerenityRest` 真实 HTTP 链路因 Serenity 4.2.0 在纯 JUnit 基座下用隔离 classloader 包装 rest-assured 响应（`InternalHttpClient→AbstractHttpClient` 的 Groovy 转型 + `RestAssuredResponseOptionsImpl→ResponseOptions` 转型），**只能在 Serenity 的 Cucumber/Story runner 下运行**（生产即此场景，正常）；api 模块纯 JUnit 不发起真实响应提取。故 P-3 重试逻辑由 `shouldRetry`（纯决策）+ `executeWithRetry`（Mockito 桩驱动重试循环：重试次数/退避/4xx 与非幂等不重试/网络异常重试后抛出）覆盖，绕开该约束。
| P-4 | **P1** | SSL 校验"可放宽"无审计 **已修复**：新增 `SecurityAudit`（`api/.../security`）记录放宽事件 + prod 环境 `relax=true` 直接抛 `IllegalStateException` fail-fast | `AbstractRestJob:50-62` | 生产环境误开无法追溯 |
| P-5 | **P1** | JSONPath 两套引擎并存（自写 Jackson + jayway） **已修复**：`BaseStep.verifyResponseJsonPath` 统一改走 Jayway `JsonPath`（`JAYWAY_CONFIG` = JacksonJsonProvider/JacksonMappingProvider），删除自写 `resolveJsonPath`；`verifyJsonArrayLength` 复用同一配置；`BaseStepJsonPathTest` 5/5 | ~~`BaseStep:131` vs `:183-221`~~ | 行为不一致，用例编写困惑 |
| P-6 | **P2** | 无统一拦截器机制，日志/鉴权/脱敏需各 job 自行处理 | — | 横切关注点重复实现 |
| P-7 | **P2** | 无响应 schema 校验能力 **已修复**：新增 `api/.../core/schema/JsonSchemaValidator`（`com.networknt:json-schema-validator:1.5.1`，支持 draft-07/2019-09/2020-12），`BaseStep` 暴露 `verifyResponseMatchesSchema(整份响应体)` 与 `verifyResponseJsonPathMatchesSchema(JSONPath 子文档)`；schema 按资源路径缓存编译结果；**schema 缺失/不可解析失败快**（`IllegalStateException`，绝不静默通过）；失败信息只给「违规位置 + 语言无关 `messageKey`」且**不回显响应体**（防绕过出口脱敏）；步骤层绑定 2 条；测试 20 例（`JsonSchemaValidatorTest` 12 + `BaseStepSchemaTest` 6 + 用例模块 resources 约定守卫 2）| — | 契约变更无法自动发现 |

---

> **落地记录（P-7，2026-09-17）**：响应契约校验能力与用法。
> - **依赖收敛**：根 DM 钉 `com.networknt:json-schema-validator:1.5.1`；api 声明时**排除**其 `jackson-dataformat-yaml` 传递依赖（本框架只用 JSON 校验，避免 dataformat 2.17.1 与项目钉定的 jackson-core/databind 2.18.3 版本偏斜），故净新增仅 `json-schema-validator` + `com.ethlo.time:itu`（RFC 3339 校验）。
> - **用法**：schema 放在**用例模块** `test-automation/src/test/resources/schemas/` 下，以 classpath 相对路径引用 ——
>   `Then the response body should match JSON schema "schemas/demo-user.json"`、
>   `Then the response body at JSON path "$.items[0]" should match JSON schema "schemas/order-item.json"`。
> - **三条硬语义**：① schema 缺失/不可解析 → `IllegalStateException` **失败快**（若静默通过，「忘放 schema 文件」会退化成永真断言，比没有校验更危险）；② 违约 → `AssertionError`，只给「违规位置 + 语言无关 `messageKey`」（如 `[required]`/`[enum]`，不受 JVM locale 影响）；③ **不回显响应体**（避免绕过出口脱敏，同 P-1 教训）。
> - **性能**：schema 按资源路径缓存已编译实例；`preload()` 可把配置错误提前到用例准备阶段。
> - **验证**：api 43 例、全护盾 1025 例全绿；`mvn verify` 全链路 GREEN（124 个 scenario 的 Gherkin 解析 + 汇总报告 exec 均正常）。

## 五、优化方案

### 5.1 立刻关闭明文日志并接入脱敏（P0）

两步：**先改默认值止血，再接脱敏**。

```java
// ApiFrameworkConfig.java —— 默认值由 true 改为 false
API_LOG_ALL("api.log.all", "false"),
```

```java
// AbstractRestJob.java —— 用 RestAssured 的 LogConfig 挂脱敏打印流
private static final LogConfig SANITIZED_LOG_CONFIG =
    LogConfig.logConfig()
        .defaultStream(new SanitizingPrintStream(System.out))
        .and().enableLoggingOfRequestAndResponseIfValidationFails();

// 构造时注入，替换 log().all()
requestSpecification.config(RestAssuredConfig.config().logConfig(SANITIZED_LOG_CONFIG));
```

```java
/** 包装 PrintStream，写出前过一遍脱敏。让 RestAssured 的原始输出也受管控。 */
public final class SanitizingPrintStream extends PrintStream {

    public SanitizingPrintStream(OutputStream out) { super(out, true, StandardCharsets.UTF_8); }

    @Override public void println(String s) { super.println(safe(s)); }
    @Override public void print(String s)   { super.print(safe(s)); }
    @Override public void write(byte[] buf, int off, int len) {
        super.write(safe(new String(buf, off, len, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
    }
    private static String safe(String s) {
        if (s == null) return null;
        try { return SensitiveDataSanitizer.sanitize(s); }
        catch (RuntimeException e) { return "[log suppressed: sanitizer error]"; }
    }
}
```

**注意兜底**：脱敏器自身抛异常时宁可抑制日志也不能崩用例（上例已处理）。

同时建议：**失败时才全量打印，成功时只打摘要**（URL / method / status / 耗时），这既降低泄露面也减少日志量。

### 5.2 补齐 api 模块单元测试（P0）

优先覆盖三条最脆弱的链路，不需要追求覆盖率数字：

```java
class SanitizingPrintStreamTest {
    @Test void 应遮蔽Authorization头() {
        var out = new ByteArrayOutputStream();
        var ps  = new SanitizingPrintStream(out);
        ps.println("Authorization: Bearer eyJhbGciOi...");
        assertThat(out.toString(UTF_8)).doesNotContain("eyJhbGciOi");
    }
    @Test void 应遮蔽请求体中的密码() { /* password / passwd / pwd */ }
    @Test void 脱敏器异常时不应抛出() { /* 注入异常，断言不抛且输出被抑制 */ }
}

class EntityBuilderTest {
    @Test void 从TypesafeConfig构建的Entity应包含全部头() { }
    @Test void getRequestHeaders应返回防御性副本() {
        var e = EntityBuilder.from(config).build();
        e.getRequestHeaders().clear();
        assertThat(e.getRequestHeaders()).isNotEmpty();   // 原对象不受影响
    }
}

class AbstractRestJobTest {
    @Test void 各verb实现应返回正确的HTTP方法() { }
    @Test void 超时配置应被正确应用() { }
}
```

配套：在父 POM 给 api 模块加 JaCoCo `check`，先设一个低门槛（如 line 40%）并只允许上升。

> **2026-09-17 落地（PAR-7）**：`AbstractRestJobTest`（6 例）已补齐，覆盖 `stripHtmlWrapper`（JSON/数组直通、HTML 包裹提取、空白回退、null/empty）、`applyResponse`（状态/头/体/cookie 回写 Entity）、`get/setValidatableResponse` 回环、`getRestAssuredConfig` 静态装配；重试内核仍由 `AbstractRestJobRetryTest`/`AbstractRestJobRetryLoopTest` 覆盖。api 模块 `JaCoCo check` 已落地：BUNDLE LINE `COVEREDRATIO` 下限 `${api.line.coverage.floor}`（首次 0.23 / 实测 23.5% = 427/1818），门禁「只升不降」。**2026-09-20 达标**：新增 6 个测试类共 46 例（`HttpStatusTest` 全量覆盖 80/80、`JsonUtilsTest` 113/123、`ApiLogSanitizerTest`、`FileReaderTest`、`EnvironmentUtilsTest`、`ConfigProviderTest`）+ 2 个测试资材，实测 **43.1%（792/1836）**，下限上调至 **0.40**（本建议原提的目标位）。任意提交把 api 行覆盖率拉到 0.40 以下即 `verify` 失败。

### 5.3 增加连接池与重试（P1）

```java
RestAssuredConfig config = RestAssuredConfig.config()
    .httpClient(HttpClientConfig.httpClientConfig()
        .setParam(CoreConnectionPNames.CONNECTION_TIMEOUT, connectTimeoutMs)
        .setParam(CoreConnectionPNames.SO_TIMEOUT, socketTimeoutMs)
        .setParam(ClientPNames.MAX_TOTAL_CONNECTIONS, 50)
        .setParam(ClientPNames.MAX_ROUTE_CONNECTIONS, 20))
    .logConfig(SANITIZED_LOG_CONFIG);
```

重试只针对幂等且网络类错误（连接超时 / 5xx），**绝不重试 4xx**：

```java
public Response executeWithRetry(int maxAttempts) {
    Response last = null;
    for (int i = 1; i <= maxAttempts; i++) {
        last = doExecute();
        if (last.statusCode() < 500 || !isIdempotent()) return last;
        sleepBackoff(i);
    }
    return last;
}
```

### 5.4 SSL 放宽加审计（P1）— 已落地

```java
// AbstractRestJob.applySslPolicy()
if (relax) {
    String environment = EnvironmentUtils.currentEnvironment().getEnvironmentName();
    SecurityAudit.recordRelaxedTls(environment, ApiFrameworkConfig.HTTP_SSL_RELAX_VALIDATION.key());
    if (SecurityAudit.isProductionEnvironment(environment)) {
        throw new IllegalStateException(
                "Refusing to start: TLS validation RELAXED in PRODUCTION-like env=" + environment);
    }
}
```

并在 ArchUnit 或启动检查里加一条：**当检测到环境 URL 包含 `prod`/`prd` 且开启 relax 时直接 fail fast**。

### 5.5 统一 JSONPath 引擎（P1）

保留 jayway（功能完整、社区标准），删除自写实现：

```java
// BaseStep.java —— 删除 resolveJsonPath(:183-221)，统一为
private Object extract(String json, String path) {
    try {
        return com.jayway.jsonpath.JsonPath.parse(json).read(path);
    } catch (PathNotFoundException e) {
        throw new FrameworkException("JSONPath 未匹配: " + path + "\n响应: "
            + SensitiveDataSanitizer.sanitize(json), e);
    }
}
```

注意异常消息里对响应体脱敏——**这正是 C-2（脱敏不覆盖异常栈）的具体落点**。

---

## 六、结论

api 模块的**分层与抽象是对的**：Builder 组装、模板方法消除重复、`SerenityRest` 保证报告打通、`Entity` 防御性拷贝。它的问题不在设计，而在**两处执行缺口**：默认开启的 `log().all()` 让最敏感的数据绕过了框架已有的全部脱敏能力（P-1），以及整个模块零测试（P-2）。

P-1 的修复成本极低（改一个默认值 + 加一个 `PrintStream` 包装），收益却是堵住最大的数据泄露面，**应作为本次评审的第一批落地项**。
