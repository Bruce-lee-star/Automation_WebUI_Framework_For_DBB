# API 自动化测试框架 - 完整使用文档

## 目录

1. [框架概述](#1-框架概述)
2. [框架架构](#2-框架架构)
3. [目录结构](#3-目录结构)
4. [配置文件详解](#4-配置文件详解)
5. [Payload 文件配置](#5-payload-文件配置)
6. [核心 API 使用方式](#6-核心-api-使用方式)
7. [Cucumber BDD 测试示例](#7-cucumber-bdd-测试示例)
8. [TDD / JUnit 编程式测试示例](#8-tdd--junit-编程式测试示例)
9. [Endpoint 端点配置](#9-endpoint-端点配置)
10. [完整配置 Demo](#10-完整配置-demo)
11. [API 参考](#11-api-参考)
12. [环境切换与多环境支持](#12-环境切换与多环境支持)

---

## 1. 框架概述

本 API 自动化测试框架基于以下技术栈构建：

| 技术 | 用途 |
|------|------|
| **Rest Assured** | HTTP 请求发送与响应处理 |
| **Serenity BDD** | 测试报告、步骤管理、Rest Assured 集成 (`SerenityRest`) |
| **Typesafe Config (HOCON)** | 配置文件解析与合并 |
| **Jayway JsonPath** | JSON 路径解析与字段修改 |
| **Jackson (ObjectMapper)** | JSON 序列化/反序列化 |
| **Hamcrest** | 断言匹配器 |
| **Cucumber** | BDD Gherkin 场景定义（可选） |
| **SLF4J + Logback** | 日志框架 |

特色功能：
- 支持 **配置文件驱动** 的 Headers、Endpoint、Query Params、Path Params、Form Params、Cookies、Payload 配置
- 支持 **多层级配置合并**：default → entity-specific → environment-specific
- 支持 **动态 payload 文件加载** 与 **JSON 路径字段修改**
- 支持 **代理 (Proxy)** 配置
- 支持 **全 URL 模式** 请求（自动解析 baseUri + endpoint + query params）
- 支持 **Cucumber BDD** 和 **TDD/JUnit 编程式** 两种测试风格
- 完善的 **HTTP 超时配置**（连接超时、Socket 超时）
- 可配置的 **SSL 宽松验证**

---

## 2. 框架架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        测试层 (Test Layer)                       │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  Cucumber Step Defs  │  │  TDD / JUnit 编程式测试           │ │
│  │  (api/steps/*.java)  │  │  (直接使用 BaseStep / TestServices)│ │
│  └──────────┬───────────┘  └──────────────┬───────────────────┘ │
│             │                             │                      │
│             └──────────┬──────────────────┘                      │
│                        ▼                                         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    TestServices (入口服务)                    │ │
│  │   .initialize().withEntity("xxx").withEnv("dev").baseStep()  │ │
│  └──────────────────────────┬──────────────────────────────────┘ │
│                             ▼                                    │
├─────────────────────────────────────────────────────────────────┤
│                        核心层 (Core Layer)                       │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  BaseStep (核心入口类)                                      │ │
│  │  - 继承 RestJobProvider → 继承 AbstractApiJobHelper         │ │
│  │  - 请求: getResource / postPayload / putPayload / ...       │ │
│  │  - 验证: verifyResponseStatusCode / verifyResponseJsonPath  │ │
│  │  - 端点: loadEndpointConfig / hasEndpointConfig             │ │
│  └──────────────────────────┬──────────────────────────────────┘ │
│                             ▼                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Entity (请求/响应数据载体)                                  │ │
│  │  - baseUri, basePath, endpoint, requestPayload, etc.        │ │
│  │  - requestHeaders, pathParams, queryParams, formParams      │ │
│  │  - cookies, proxy, responsePayload, responseCode, etc.      │ │
│  └──────────────────────────┬──────────────────────────────────┘ │
│                             ▼                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  REST Verbs: RestGet/Post/Put/Patch/DeleteJob (AbstractRestJob)│
│  │  - 执行实际的 HTTP 请求                                       │
│  │  - 解析响应存入 Entity                                       │
│  └─────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│                       配置层 (Config Layer)                      │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  ConfigProvider      │  │  FrameworkConfig                  │ │
│  │  - 配置加载与合并     │  │  - 统一配置访问入口                │ │
│  │  - Payload路径解析    │  │  - HTTP超时/SSL/编码/日志等       │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │  EndpointProvider    │  │  HeadersAssemblers                │ │
│  │  - 端点配置加载       │  │  - 请求头构建                     │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 目录结构

```
src/
├── main/java/.../framework/api/
│   ├── assembler/
│   │   └── headersImpl/
│   │       └── HeadersAssemblers.java      # Header 构建与校验工具
│   ├── client/
│   │   ├── ApiJob.java                     # API 任务基类
│   │   ├── AbstractApiJobHelper.java       # 请求参数辅助操作（增删改查 Header/Param/Cookie/Payload）
│   │   └── rest/
│   │       ├── AbstractRestJob.java        # REST 请求抽象基类（HTTP 超时/SSL 配置）
│   │       ├── RestJobProvider.java        # REST 请求入口（getResource/postPayload/...）
│   │       └── impl/
│   │           ├── RestGetJob.java         # GET 请求实现
│   │           ├── RestPostJob.java        # POST 请求实现
│   │           ├── RestPutJob.java         # PUT 请求实现
│   │           ├── RestPatchJob.java       # PATCH 请求实现
│   │           └── RestDeleteJob.java      # DELETE 请求实现
│   ├── config/
│   │   ├── ConfigProvider.java             # 配置加载与合并核心
│   │   └── FrameworkConfig.java            # 框架统一配置入口
│   ├── core/
│   │   ├── endpoint/
│   │   │   ├── EndpointConfig.java         # 端点配置模型
│   │   │   └── EndpointProvider.java       # 端点配置加载器
│   │   ├── entity/
│   │   │   ├── Entity.java                 # 请求/响应数据载体
│   │   │   └── EntityBuilder.java          # Entity 构建器
│   │   ├── enums/
│   │   │   └── HttpStatus.java             # HTTP 状态码枚举
│   │   ├── services/
│   │   │   └── TestServices.java           # 测试入口服务（链式调用）
│   │   └── step/
│   │       ├── BaseStep.java               # 核心步骤类（请求+验证）
│   │       └── BaseStepFactory.java        # BaseStep 工厂
│   ├── domain/enums/
│   │   ├── APIResources.java               # API 资源枚举（BaseUri/BasePath 获取）
│   │   └── ConfigKeys.java                 # 配置文件 Key 枚举
│   └── utility/
│       ├── Constants.java                  # 全局常量
│       ├── EnvironmentUtils.java           # 环境变量工具
│       ├── FileReader.java                 # 文件读取工具
│       └── JsonUtils.java                  # JSON 操作工具类
│
├── test/java/.../tests/api/steps/
│   ├── BaseConfigurationSteps.java         # BaseUri/Endpoint 设置步骤
│   ├── EntitySteps.java                    # Entity 初始化步骤
│   ├── HeaderSteps.java                    # Header 操作步骤
│   ├── PathParameterSteps.java             # Path 参数步骤
│   ├── PayloadSteps.java                   # Payload 步骤
│   ├── QueryParameterSteps.java            # Query 参数步骤
│   ├── RequestSteps.java                   # 发送请求步骤
│   ├── ResponseBodySteps.java              # 响应体验证步骤
│   ├── ResponseHeaderSteps.java            # 响应头验证步骤
│   └── ResponseStatusSteps.java            # 状态码验证步骤
│
├── test/resources/
│   ├── config/
│   │   └── application.conf                # 全局默认配置文件
│   ├── payload/
│   │   └── *.json                          # Payload JSON 模板文件
│   └── features/
│       └── api/
│           └── *.feature                   # Cucumber Gherkin 场景文件
```

---

## 4. 配置文件详解

### 4.1 配置文件位置与优先级

所有配置文件默认放在 **`src/test/resources/config/`** 目录下。

加载优先级（高到低）：

```
environment-specific 配置 (如 dev { ... })
    ↓ 覆盖
entity-specific 配置 (如 petstore.conf / petstore.properties)
    ↓ 覆盖
默认全局配置 (application.conf)
```

### 4.2 路径配置 (`paths`)

在 `application.conf` 中定义框架路径（可配置）：

```hocon
paths {
    # 资源根路径（默认: "./src/test/resources/"）
    base-path = "./src/test/resources/"

    # 配置文件目录（相对于 base-path）
    config-dir = "config/"

    # Payload 目录（相对于 base-path）
    payload-dir = "payload/"

    # 默认配置文件名
    default-config = "application.conf"
}
```

### 4.3 application.conf 完整配置项

```hocon
# ========================================
# 路径配置
# ========================================
paths {
    base-path = "./src/test/resources/"
    config-dir = "config/"
    payload-dir = "payload/"
}

# ========================================
# 文件编码
# ========================================
file.encoding.default = "UTF-8"
file.encoding.payload = "UTF-8"

# ========================================
# JSON 解析配置
# ========================================
json {
    # 遇到未知属性时是否抛出异常（默认: false）
    fail-on-unknown-properties = false
    # 接受单值作为数组（默认: true）
    accept-single-value-as-array = true
    # 忽略原始类型的 null 值（默认: true）
    ignore-null-for-primitives = true
}

# ========================================
# 日志配置
# ========================================
logging {
    root.level = "INFO"
    console.pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    file.enabled = false
}

# ========================================
# HTTP 超时配置
# ========================================
http {
    # 连接超时（毫秒），默认: 30000
    connection.timeout = 30000

    # Socket 超时（毫秒），默认: 30000
    socket.timeout = 30000

    # SSL 宽松验证（默认: true，不校验证书）
    ssl.relax-validation = true
}

# ========================================
# 全局 Headers 配置
# ========================================
headers {
    Accept = "application/json"
    Content-Type = "application/json"
}

# ========================================
# API Base URI 配置（多环境）
# ========================================
api.base.uri {
    default = "http://localhost:8080"
    dev = "https://dev-api.example.com"
    test = "https://test-api.example.com"
    prod = "https://api.example.com"
}

# ========================================
# API Base Path 配置（多环境）
# ========================================
api.base.path {
    default = "/api/v1"
    dev = "/api/v1"
    test = "/api/v1"
    prod = "/api/v1"
}

# ========================================
# API 请求/响应日志开关
# ========================================
api.request.response.logging.enabled = true

# ========================================
# 测试重试配置
# ========================================
test.retry {
    count = 3
    delay = 1000    # 毫秒
}

# ========================================
# 端点配置（详见第9节）
# ========================================
end-points {
    # 在此处或 entity 配置文件中定义端点
}
```

### 4.4 Entity-Specific 配置文件

每个 Entity 可以有独立的配置文件，位于 `src/test/resources/config/` 目录下：

**命名规则**: `{entityName}.conf` 或 `{entityName}.properties`（优先尝试 `.conf`，再尝试 `.properties`）

**示例 `petstore.conf`**:

```hocon
# petstore Entity 专用配置

# 覆盖默认 headers
headers {
    Accept = "application/json"
    Content-Type = "application/json"
    x-api-key = "special-key-12345"
}

# 覆盖 API Base URI
api.base.uri {
    default = "https://petstore.swagger.io"
}

# 覆盖 API Base Path
api.base.path {
    default = "/v2"
}

# ========================================
# 端点定义（详见第9节）
# ========================================
end-points {
    pet {
        # 简单端点定义 - GET /pet/{petId}
        get {
            path = "/pet/{petId}"
            method = "GET"
            path-params {
                petId = "1"
            }
            headers {
                Accept = "application/json"
            }
        }

        # POST 端点 - 带 payload 文件
        post {
            path = "/pet"
            method = "POST"
            payload = "pet-create.json"
            headers {
                Content-Type = "application/json"
            }
        }

        # 多端点子分组 - findByStatus
        findByStatus {
            get {
                path = "/pet/findByStatus"
                method = "GET"
                query-params {
                    status = "available"
                }
            }
        }

        # 多端点子分组 - getById (支持 GET 和 DELETE)
        getById {
            get {
                path = "/pet/{petId}"
                method = "GET"
                path-params {
                    petId = "2"
                }
            }
            delete {
                path = "/pet/{petId}"
                method = "DELETE"
                path-params {
                    petId = "2"
                }
            }
        }

        # PUT 端点 - 更新
        put {
            path = "/pet"
            method = "PUT"
            payload = "pet-update.json"
            headers {
                Content-Type = "application/json"
            }
        }
    }

    # 另一个端点组 - store
    store {
        inventory {
            get {
                path = "/store/inventory"
                method = "GET"
            }
        }
        order {
            post {
                path = "/store/order"
                method = "POST"
                payload = "order-create.json"
            }
            get {
                path = "/store/order/{orderId}"
                method = "GET"
                path-params {
                    orderId = "1"
                }
            }
        }
    }

    # 另一个端点组 - user
    user {
        login {
            get {
                path = "/user/login"
                method = "GET"
                query-params {
                    username = "testuser"
                    password = "testpass"
                }
            }
        }
    }
}

# ========================================
# 环境覆盖（env-specific overrides）
# ========================================
dev {
    api.base.uri {
        dev = "https://dev-petstore.example.com"
    }
    headers {
        Authorization = "Bearer dev-token-xxx"
    }
}

test {
    api.base.uri {
        test = "https://test-petstore.example.com"
    }
    headers {
        Authorization = "Bearer test-token-yyy"
    }
}
```

---

## 5. Payload 文件配置

### 5.1 Payload 文件位置

Payload JSON 文件默认存放在 **`src/test/resources/payload/`** 目录下。

可通过配置修改路径：
```hocon
paths {
    payload-dir = "payload/"
}
```

### 5.2 Payload 文件格式

**`src/test/resources/payload/pet-create.json`**:

```json
{
    "id": 0,
    "category": {
        "id": 1,
        "name": "Dogs"
    },
    "name": "Buddy",
    "photoUrls": [
        "https://example.com/photo1.jpg"
    ],
    "tags": [
        {
            "id": 1,
            "name": "friendly"
        }
    ],
    "status": "available"
}
```

### 5.3 Payload 加载方式

**方式一：代码中直接加载**
```java
baseStep.loadPayload("pet-create.json");
// payload 会被自动解析为 JSON 并设置到 Entity.requestPayload
```

**方式二：通过 Endpoint 配置自动加载**
```hocon
end-points {
    pet {
        post {
            path = "/pet"
            payload = "pet-create.json"    # 自动加载此 payload 文件
        }
    }
}
```

### 5.4 Payload 动态修改

框架支持通过 JsonPath 动态修改 Payload 中的字段：

```java
// 修改单个字段
baseStep.modifyFieldsInRequestPayload("name", "Max");
baseStep.modifyFieldsInRequestPayload("category.name", "Cats");
baseStep.modifyFieldsInRequestPayload("tags[0].name", "cute");

// 修改数组元素
baseStep.modifyFieldsInRequestPayload("photoUrls[0]", "https://new-url.com/photo.jpg");

// 修改复杂对象
Map<String, Object> newCategory = Map.of("id", 2, "name", "Birds");
baseStep.modifyFieldsInRequestPayload("$.category", newCategory);
```

---

## 6. 核心 API 使用方式

### 6.1 创建 BaseStep 实例

BaseStep 是框架的核心入口，只能通过 `TestServices` 创建：

```java
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;

// 方式1: 创建空实体（动态配置，不加载任何配置文件）
BaseStep baseStep = TestServices.initialize().baseStep();

// 方式2: 创建带配置文件的实体（加载 petstore.conf）
BaseStep baseStep = TestServices.initialize()
        .withEntity("petstore")
        .baseStep();

// 方式3: 创建带配置文件和环境的实体
BaseStep baseStep = TestServices.initialize()
        .withEntity("petstore")
        .withEnv("dev")
        .baseStep();

// 方式4: 直接通过工厂创建（不推荐，推荐用 TestServices）
BaseStep baseStep = BaseStepFactory.createWithEntity("petstore");
BaseStep baseStep = BaseStepFactory.createWithEntity("petstore", "dev");
BaseStep baseStep = BaseStepFactory.createWithNullEntity();
```

---

## 7. Cucumber BDD 测试示例

### 7.1 Feature 文件

**`src/test/resources/features/api/petstore.feature`**:

```gherkin
Feature: Petstore API 测试

  Background:
    Given an entity with "petstore"

  Scenario: 获取指定宠物信息 - 成功
    Given endpoint "pet" with "GET" method
    When I send "GET" request to "pet" endpoint
    Then I should get response status code 200
    Then response field "name" should be "doggie"
    Then response header "Content-Type" should contain "application/json"

  Scenario: 创建新宠物
    Given endpoint "pet" with "POST" method
    Given I load payload from file "pet-create.json"
    Given I modify field "name" in request body to value "Max"
    Given I modify field "category.name" in request body to value "Dogs"
    When I send "POST" request to "pet" endpoint
    Then I should get response status code 200
    Then response field "name" should be "Max"

  Scenario Outline: 按状态查询宠物
    Given endpoint "pet.findByStatus" with "GET" method
    Given I set query parameter "status" to "<status>"
    When I send "GET" request to "pet.findByStatus" endpoint
    Then I should get a successful response
    Then response array "$" length is greater than 0

    Examples:
      | status    |
      | available |
      | pending   |
      | sold      |

  Scenario: 更新宠物信息
    Given endpoint "pet" with "PUT" method
    Given I load payload from file "pet-update.json"
    Given I modify field "name" in request body to value "UpdatedBuddy"
    Given I modify field "status" in request body to value "sold"
    When I send "PUT" request to "pet" endpoint
    Then I should get response status code 200

  Scenario: 删除宠物
    Given endpoint "pet.getById" with "DELETE" method
    When I send "DELETE" request to "pet.getById" endpoint
    Then I should get response status code 200

  Scenario: 动态配置 - 无配置文件
    Given an entity
    Given I set base URI to "https://jsonplaceholder.typicode.com"
    Given I set endpoint to "/posts/1"
    Given I set request header "Accept" to "application/json"
    When I send GET request
    Then response status code should be 200
    Then response field "userId" should be 1

  Scenario: Header/Cookie/Form 参数操作
    Given an entity with "petstore"
    Given I set request header "x-custom-header" to "custom-value"
    Given I set query parameter "page" to "1"
    Given I set query parameter "limit" to "10"
    Given I set endpoint to "/pet/findByStatus"
    When I send GET request
    Then I should get a successful response
```

### 7.2 BDD Step 定义速查表

#### Entity 初始化

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given an entity` | 创建空 Entity（动态配置） |
| `Given an entity with "{entityName}"` | 创建加载 `{entityName}.conf` 的 Entity |
| `Given an entity "{entityName}" as env "{env}"` | 创建带环境的 Entity |
| `Given endpoint "{name}" with "{method}" method` | 加载端点配置 |

#### Base URI / Endpoint

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given I set base URI to "{uri}"` | 设置 Base URI |
| `Given I set endpoint to "{endpoint}"` | 设置端点路径 |

#### Header 操作

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given I set request header "{name}" to "{value}"` | 设置 Header |
| `Given I add request header "{name}" with value "{value}"` | 添加 Header |
| `Given I update request header "{name}" to value "{value}"` | 更新 Header |
| `Given I remove request header "{name}"` | 移除 Header |
| `Given I clear all request headers` | 清空所有 Headers |
| `Given I set request headers:` + DataTable | 批量设置 Headers |

#### Query 参数

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given I set query parameter "{name}" to "{value}"` | 设置 Query 参数 |
| `Given I add query parameter "{name}" with value "{value}"` | 添加 Query 参数 |
| `Given I update query parameter "{name}" to value "{value}"` | 更新 Query 参数 |
| `Given I remove query parameter "{name}"` | 移除 Query 参数 |
| `Given I clear all query parameters` | 清空所有 Query 参数 |
| `Given I set query parameters:` + DataTable | 批量设置 Query 参数 |

#### Path 参数

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given I add path parameter "{name}" with value "{value}"` | 添加 Path 参数 |
| `Given I update path parameter "{name}" to value "{value}"` | 更新 Path 参数 |
| `Given I remove path parameter "{name}"` | 移除 Path 参数 |
| `Given I clear all path parameters` | 清空所有 Path 参数 |
| `Given I set path parameters:` + DataTable | 批量设置 Path 参数 |

#### Payload / Body

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Given I load payload from file "{fileName}"` | 加载 Payload 文件 |
| `Given I set request body to "{body}"` | 直接设置请求体字符串 |
| `Given I modify field "{fieldPath}" in request body to value "{value}"` | 修改 Payload 字段 |
| `Given I clear request body` | 清空请求体 |

#### 发送请求

| Gherkin 步骤 | 说明 |
|-------------|------|
| `When I send GET request` | 发送 GET 请求 |
| `When I send POST request` | 发送 POST 请求 |
| `When I send PUT request` | 发送 PUT 请求 |
| `When I send PATCH request` | 发送 PATCH 请求 |
| `When I send DELETE request` | 发送 DELETE 请求 |
| `When I send "{METHOD}" request` | 发送指定方法请求 |
| `When I send "{method}" request to "{endpoint}" endpoint` | 发送请求并加载端点配置 |

#### 响应验证

| Gherkin 步骤 | 说明 |
|-------------|------|
| `Then I should get response status code {int}` | 验证状态码 |
| `Then I should get response status code between {int} and {int}` | 验证状态码在范围内 |
| `Then I should get a successful response` | 验证 2xx 成功 |
| `Then response field "{path}" should be {int}` | 验证整数字段 |
| `Then response field "{path}" is "{value}"` | 验证字符串字段 |
| `Then response field "{path}" should be {boolean}` | 验证布尔字段 |
| `Then response field "{path}" should be null` | 验证字段为 null |
| `Then response field "{path}" should contain "{value}"` | 验证字段包含某值 |
| `Then response field "{path}" should exist` | 验证字段存在 |
| `Then response field "{path}" should not exist` | 验证字段不存在 |
| `Then response array "{path}" length is {int}` | 验证数组长度 |
| `Then response array "{path}" length is greater than {int}` | 验证数组长度大于 |
| `Then response body should contain "{content}"` | 验证响应体包含内容 |
| `Then response body should not be empty` | 验证响应体非空 |
| `Then response fields:` + DataTable | 批量验证多个字段 |
| `Then response header "{name}" should be "{value}"` | 验证响应头值 |
| `Then response header "{name}" should contain "{value}"` | 验证响应头包含 |
| `Then response header "{name}" should exist` | 验证响应头存在 |
| `Then response header "{name}" should not exist` | 验证响应头不存在 |

---

## 8. TDD / JUnit 编程式测试示例

### 8.1 基础示例 - 使用 TestServices

```java
package com.hsbc.cmb.hk.dbb.automation.tests.api;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.services.TestServices;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.step.BaseStep;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PetstoreApiTest {

    private static BaseStep baseStep;

    @BeforeAll
    public static void setUp() {
        // 加载 petstore 配置文件
        baseStep = TestServices.initialize()
                .withEntity("petstore")
                .baseStep();
    }

    // =====================================================
    // 方式一：使用 Endpoint 配置（推荐）
    // =====================================================

    @Test
    @Order(1)
    public void testGetPetById_UsingEndpointConfig() {
        // 加载 endpoint 配置（从 petstore.conf 中的 end-points.pet.get）
        baseStep.loadEndpointConfig("pet", "GET");

        // 发送 GET 请求
        baseStep.getResource();

        // 验证
        baseStep.verifyResponseStatusCode(200);
        baseStep.verifyResponseJsonPath("status", "available");
        baseStep.verifyResponseBodyContains("name");
    }

    @Test
    @Order(2)
    public void testFindPetByStatus_UsingEndpointConfig() {
        baseStep.loadEndpointConfig("pet.findByStatus", "GET");
        baseStep.getResource();
        baseStep.verifyResponseStatusCode(200);
        baseStep.verifyJsonArrayLength("$", 1); // 至少返回1个
    }

    @Test
    @Order(3)
    public void testCreatePet_UsingEndpointConfig() {
        baseStep.loadEndpointConfig("pet", "POST");

        // 动态修改 payload 字段
        baseStep.modifyFieldsInRequestPayload("name", "MyTestPet");
        baseStep.modifyFieldsInRequestPayload("id", 99999);

        baseStep.postPayload();
        baseStep.verifyResponseStatusCode(200);
        baseStep.verifyResponseJsonPath("name", "MyTestPet");
    }

    // =====================================================
    // 方式二：动态配置（不依赖配置文件）
    // =====================================================

    @Test
    @Order(4)
    public void testDynamicConfiguration() {
        // 创建空配置的 BaseStep
        BaseStep dynamicStep = TestServices.initialize().baseStep();

        // 手动设置所有参数
        dynamicStep.setBaseUri("https://jsonplaceholder.typicode.com");
        dynamicStep.setEndpoint("/posts/1");
        dynamicStep.addRequestHeader("Accept", "application/json");

        // 发送请求
        dynamicStep.getResource();

        // 验证
        dynamicStep.verifyResponseStatusCode(200);
        dynamicStep.verifyResponseJsonPath("userId", 1);
        dynamicStep.verifyResponseJsonPath("id", 1);
        dynamicStep.verifyResponseBodyContains("title");

        // 获取响应数据
        String responseBody = dynamicStep.getResponseJson();
        int statusCode = dynamicStep.getResponseCode();
        System.out.println("Response: " + responseBody);
    }

    // =====================================================
    // 方式三：全 URL 模式（自动解析）
    // =====================================================

    @Test
    @Order(5)
    public void testFullUrlMode() {
        BaseStep urlStep = TestServices.initialize().baseStep();
        urlStep.addRequestHeader("Accept", "application/json");

        // 传入完整 URL，自动解析 baseUri、endpoint、query params
        urlStep.getFullUrl("https://jsonplaceholder.typicode.com/posts?userId=1");

        urlStep.verifyResponseStatusCode(200);
        urlStep.verifyJsonArrayLength("$", 1);
    }

    @Test
    @Order(6)
    public void testFullUrlMode_Post() {
        BaseStep urlStep = TestServices.initialize().baseStep();
        urlStep.addRequestHeader("Content-Type", "application/json; charset=UTF-8");

        // 设置请求体
        urlStep.setRequestBody("{\"title\":\"foo\",\"body\":\"bar\",\"userId\":1}");

        // 完整 URL POST
        urlStep.postFullUrl("https://jsonplaceholder.typicode.com/posts");

        urlStep.verifyResponseStatusCode(201);
        urlStep.verifyResponseJsonPath("title", "foo");
    }

    // =====================================================
    // 方式四：CRUD 完整流程
    // =====================================================

    @Test
    @Order(7)
    public void testFullCrudFlow() {
        BaseStep api = TestServices.initialize().withEntity("petstore").baseStep();

        // ---- Create ----
        api.loadEndpointConfig("pet", "POST");
        api.modifyFieldsInRequestPayload("name", "CRUD_Test_Pet");
        api.modifyFieldsInRequestPayload("id", 88888);
        api.modifyFieldsInRequestPayload("status", "available");
        api.postPayload();
        api.verifyResponseStatusCode(200);
        Integer petId = 88888;

        // ---- Read ----
        api.clearQueryParams(); // 清空之前的 query params
        api.setEndpoint("/pet/" + petId);
        api.getResource();
        api.verifyResponseStatusCode(200);
        api.verifyResponseJsonPath("name", "CRUD_Test_Pet");

        // ---- Update ----
        api.loadEndpointConfig("pet", "PUT");
        api.modifyFieldsInRequestPayload("id", petId);
        api.modifyFieldsInRequestPayload("name", "CRUD_Test_Pet_Updated");
        api.modifyFieldsInRequestPayload("status", "sold");
        api.putPayload();
        api.verifyResponseStatusCode(200);

        // ---- Delete ----
        api.setEndpoint("/pet/" + petId);
        api.addRequestHeader("api_key", "special-key");
        api.deleteResource();
        api.verifyResponseStatusCode(200);

        // ---- Verify Deleted ----
        api.setEndpoint("/pet/" + petId);
        api.getResource();
        api.verifyResponseStatusCode(404);
    }

    // =====================================================
    // 方式五：环境切换
    // =====================================================

    @Test
    @Order(8)
    public void testWithEnvironment() {
        BaseStep devStep = TestServices.initialize()
                .withEntity("petstore")
                .withEnv("dev")
                .baseStep();

        // 使用 dev 环境的配置（会覆盖 baseUri, headers.Authorization 等）
        devStep.loadEndpointConfig("pet", "GET");
        devStep.getResource();
        devStep.verifyResponseStatusCode(200);
    }

    // =====================================================
    // 方式六：代理配置
    // =====================================================

    @Test
    @Order(9)
    public void testWithProxy() {
        BaseStep proxyStep = TestServices.initialize().baseStep();
        proxyStep.setBaseUri("https://api.example.com");
        proxyStep.setEndpoint("/proxy-test");

        // 设置代理
        proxyStep.setProxy("proxy.company.com", 8080, "http");

        proxyStep.getResource();
        proxyStep.verifyResponseStatusCode(200);
    }

    // =====================================================
    // 方式七：请求/响应日志开关
    // =====================================================

    @Test
    @Order(10)
    public void testWithRequestResponseLogging() {
        BaseStep logStep = TestServices.initialize().baseStep();

        // 启用详细日志（打印完整请求/响应）
        logStep.setApiRequestResponseLogsEnabled(true);

        logStep.setBaseUri("https://jsonplaceholder.typicode.com");
        logStep.setEndpoint("/posts/1");
        logStep.getResource();
        logStep.verifyResponseStatusCode(200);
    }

    // =====================================================
    // 方式八：响应头验证
    // =====================================================

    @Test
    @Order(11)
    public void testResponseHeaders() {
        BaseStep api = TestServices.initialize().baseStep();
        api.setBaseUri("https://jsonplaceholder.typicode.com");
        api.setEndpoint("/posts/1");
        api.getResource();

        api.verifyResponseStatusCode(200);
        api.verifyResponseHeader("Content-Type", "application/json; charset=utf-8");
    }

    // =====================================================
    // 方式九：复杂的 Payload 操作
    // =====================================================

    @Test
    @Order(12)
    public void testComplexPayloadManipulation() {
        BaseStep api = TestServices.initialize().withEntity("petstore").baseStep();
        api.loadEndpointConfig("pet", "POST");

        // 加载 payload 模板
        api.loadPayload("pet-create.json");

        // 批量修改字段
        api.modifyFieldsInRequestPayload("id", 202020);
        api.modifyFieldsInRequestPayload("name", "ComplexPet");
        api.modifyFieldsInRequestPayload("category.id", 99);
        api.modifyFieldsInRequestPayload("category.name", "Fantasy");
        api.modifyFieldsInRequestPayload("tags[0].id", 100);
        api.modifyFieldsInRequestPayload("tags[0].name", "magical");
        api.modifyFieldsInRequestPayload("status", "available");
        api.modifyFieldsInRequestPayload("photoUrls[0]", "https://photos.example.com/pet1.jpg");

        api.postPayload();
        api.verifyResponseStatusCode(200);
        api.verifyResponseJsonPath("category.name", "Fantasy");
    }
}
```

### 8.2 基础示例 - 使用 EntityBuilder (底层方式)

```java
package com.hsbc.cmb.hk.dbb.automation.tests.api;

import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.Entity;
import com.hsbc.cmb.hk.dbb.automation.framework.api.core.entity.EntityBuilder;
import com.hsbc.cmb.hk.dbb.automation.framework.api.client.rest.RestJobProvider;
import org.junit.jupiter.api.Test;

public class EntityBuilderTest {

    @Test
    public void testUsingEntityBuilder_Direct() {
        // 构建 Entity（自动加载 petstore.conf）
        Entity entity = EntityBuilder.build("petstore");

        // 设置端点
        entity.setEndpoint("/pet/1");

        // 使用 RestJobProvider 发送请求
        RestJobProvider provider = new RestJobProvider();
        provider.setEntity(entity);
        provider.getResource();

        // 获取响应
        int statusCode = provider.getResponseCode();
        String responseBody = provider.getResponseJson();

        System.out.println("Status: " + statusCode);
        System.out.println("Body: " + responseBody);
    }

    @Test
    public void testDynamicEntityBuilder() {
        // 不使用配置文件，纯动态配置
        Entity entity = EntityBuilder.buildNull();
        entity.setBaseUri("https://jsonplaceholder.typicode.com");
        entity.setEndpoint("/posts/1");
        entity.addRequestHeader("Accept", "application/json");

        RestJobProvider provider = new RestJobProvider();
        provider.setEntity(entity);
        provider.getResource();

        System.out.println("Status: " + provider.getResponseCode());
        System.out.println("Body: " + provider.getResponseJson());
    }

    @Test
    public void testEntityWithEnvironment() {
        // 带环境变量
        Entity entity = EntityBuilder.build("petstore", "dev");

        RestJobProvider provider = new RestJobProvider();
        provider.setEntity(entity);
        provider.setEndpoint("/pet/1");
        provider.getResource();

        System.out.println("Status: " + provider.getResponseCode());
    }
}
```

---

## 9. Endpoint 端点配置

### 9.1 端点配置两种格式

#### 格式一：简单端点定义（一个端点直接对应一个 HTTP Method）

```hocon
end-points {
    pet {
        get {
            path = "/pet/{petId}"
            method = "GET"
            description = "根据 ID 获取宠物"
            path-params {
                petId = "1"
            }
            query-params {
                version = "v2"
            }
            headers {
                Accept = "application/json"
                x-api-key = "special-key"
            }
        }
        post {
            path = "/pet"
            method = "POST"
            description = "创建新宠物"
            payload = "pet-create.json"
            headers {
                Content-Type = "application/json"
            }
        }
    }
}
```

用法：`baseStep.loadEndpointConfig("pet", "GET")` → 加载 `end-points.pet.get`

#### 格式二：多端点分组定义（一个 Entity 下有多个子端点）

```hocon
end-points {
    pet {
        # 子端点1: 按状态查找
        findByStatus {
            get {
                path = "/pet/findByStatus"
                method = "GET"
                query-params {
                    status = "available"
                }
            }
        }
        # 子端点2: 按ID操作（支持 GET + DELETE）
        getById {
            get {
                path = "/pet/{petId}"
                method = "GET"
                path-params {
                    petId = "2"
                }
            }
            delete {
                path = "/pet/{petId}"
                method = "DELETE"
                path-params {
                    petId = "2"
                }
            }
        }
        # 子端点3: 创建
        create {
            post {
                path = "/pet"
                method = "POST"
                payload = "pet-create.json"
            }
        }
    }
}
```

用法：
- `baseStep.loadEndpointConfig("pet.findByStatus", "GET")` → `end-points.pet.findByStatus.get`
- `baseStep.loadEndpointConfig("pet.getById", "GET")` → `end-points.pet.getById.get`
- `baseStep.loadEndpointConfig("pet.getById", "DELETE")` → `end-points.pet.getById.delete`
- `baseStep.loadEndpointConfig("pet.create", "POST")` → `end-points.pet.create.post`

### 9.2 Endpoint 配置项说明

| 配置项 | 说明 | 示例 |
|--------|------|------|
| `path` | API 路径（可包含路径参数占位符 `{param}`） | `"/pet/{petId}"` |
| `method` | HTTP 方法 | `"GET"`, `"POST"`, `"PUT"`, `"PATCH"`, `"DELETE"` |
| `description` | 端点描述（可选） | `"根据ID获取宠物"` |
| `payload` | Payload 文件名（自动从 payload 目录加载） | `"pet-create.json"` |
| `path-params` | 路径参数 Key-Value | `{ petId = "1" }` |
| `query-params` | Query 参数 Key-Value | `{ status = "available" }` |
| `form-params` | Form 参数 Key-Value | `{ username = "test" }` |
| `headers` | 请求头 Key-Value | `{ Accept = "application/json" }` |
| `cookies` | Cookie Key-Value | `{ sessionId = "abc123" }` |

---

## 10. 完整配置 Demo

### 10.1 application.conf（全局配置）

**路径**: `src/test/resources/config/application.conf`

```hocon
# ========================================
# API Framework - Global Default Configuration
# ========================================

paths {
    base-path = "./src/test/resources/"
    config-dir = "config/"
    payload-dir = "payload/"
}

file.encoding.default = "UTF-8"
file.encoding.payload = "UTF-8"

json {
    fail-on-unknown-properties = false
    accept-single-value-as-array = true
    ignore-null-for-primitives = true
}

logging {
    root.level = "INFO"
    console.pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    file.enabled = false
}

http {
    connection.timeout = 30000
    socket.timeout = 30000
    ssl.relax-validation = true
}

# 全局默认 Headers
headers {
    Accept = "application/json"
    Content-Type = "application/json"
}

# 全局默认 Base URI
api.base.uri {
    default = "http://localhost:8080"
}

api.base.path {
    default = "/"
}

api.request.response.logging.enabled = true

test.retry {
    count = 3
    delay = 1000
}
```

### 10.2 Entity 配置文件 Demo

**路径**: `src/test/resources/config/petstore.conf`

```hocon
# ========================================
# Petstore API Entity Configuration
# ========================================

# 覆盖全局 Headers
headers {
    Accept = "application/json"
    Content-Type = "application/json"
    x-api-key = "special-key"
}

# 覆盖 Base URI
api.base.uri {
    default = "https://petstore.swagger.io"
}

# 覆盖 Base Path
api.base.path {
    default = "/v2"
}

# ========================================
# 端点定义
# ========================================
end-points {

    # ---- 宠物相关端点 ----
    pet {

        # GET: 根据 ID 获取宠物
        get {
            path = "/pet/{petId}"
            method = "GET"
            description = "根据宠物ID获取宠物详情"
            path-params {
                petId = "1"
            }
            headers {
                Accept = "application/json"
            }
        }

        # POST: 创建新宠物
        post {
            path = "/pet"
            method = "POST"
            description = "创建新宠物"
            payload = "pet-create.json"
            headers {
                Content-Type = "application/json"
            }
        }

        # PUT: 更新宠物信息
        put {
            path = "/pet"
            method = "PUT"
            description = "更新宠物信息"
            payload = "pet-update.json"
            headers {
                Content-Type = "application/json"
            }
        }

        # 子端点: 按状态查找
        findByStatus {
            get {
                path = "/pet/findByStatus"
                method = "GET"
                description = "按状态查找宠物"
                query-params {
                    status = "available"
                }
            }
        }

        # 子端点: 按ID操作
        getById {
            get {
                path = "/pet/{petId}"
                method = "GET"
                path-params {
                    petId = "2"
                }
            }
            delete {
                path = "/pet/{petId}"
                method = "DELETE"
                path-params {
                    petId = "2"
                }
            }
        }

        # 上传图片
        uploadImage {
            post {
                path = "/pet/{petId}/uploadImage"
                method = "POST"
                path-params {
                    petId = "1"
                }
                form-params {
                    additionalMetadata = "test metadata"
                }
            }
        }
    }

    # ---- 商店相关端点 ----
    store {
        inventory {
            get {
                path = "/store/inventory"
                method = "GET"
                description = "获取库存信息"
            }
        }
        order {
            post {
                path = "/store/order"
                method = "POST"
                description = "创建订单"
                payload = "order-create.json"
            }
            get {
                path = "/store/order/{orderId}"
                method = "GET"
                path-params {
                    orderId = "1"
                }
            }
            delete {
                path = "/store/order/{orderId}"
                method = "DELETE"
                path-params {
                    orderId = "1"
                }
            }
        }
    }

    # ---- 用户相关端点 ----
    user {
        login {
            get {
                path = "/user/login"
                method = "GET"
                query-params {
                    username = "testuser"
                    password = "testpass"
                }
            }
        }
        logout {
            get {
                path = "/user/logout"
                method = "GET"
            }
        }
        create {
            post {
                path = "/user"
                method = "POST"
                payload = "user-create.json"
            }
        }
    }
}

# ========================================
# 环境覆盖配置
# ========================================
dev {
    api.base.uri {
        default = "https://dev-petstore.example.com"
    }
    headers {
        Authorization = "Bearer dev-token-abc123"
    }
    http {
        connection.timeout = 5000
        socket.timeout = 5000
    }
}

test {
    api.base.uri {
        default = "https://test-petstore.example.com"
    }
    headers {
        Authorization = "Bearer test-token-xyz789"
    }
}

prod {
    api.base.uri {
        default = "https://petstore.example.com"
    }
    http.ssl.relax-validation = false
}
```

### 10.3 Payload 文件 Demo

**路径**: `src/test/resources/payload/pet-create.json`

```json
{
    "id": 0,
    "category": {
        "id": 1,
        "name": "Dogs"
    },
    "name": "DefaultPetName",
    "photoUrls": [
        "https://example.com/photo1.jpg"
    ],
    "tags": [
        {
            "id": 1,
            "name": "friendly"
        }
    ],
    "status": "available"
}
```

**路径**: `src/test/resources/payload/pet-update.json`

```json
{
    "id": 1,
    "category": {
        "id": 1,
        "name": "Dogs"
    },
    "name": "UpdatedPetName",
    "photoUrls": [
        "https://example.com/photo1.jpg"
    ],
    "tags": [
        {
            "id": 1,
            "name": "friendly"
        }
    ],
    "status": "sold"
}
```

**路径**: `src/test/resources/payload/order-create.json`

```json
{
    "id": 0,
    "petId": 1,
    "quantity": 1,
    "shipDate": "2025-01-01T00:00:00.000Z",
    "status": "placed",
    "complete": false
}
```

**路径**: `src/test/resources/payload/user-create.json`

```json
{
    "id": 0,
    "username": "testuser",
    "firstName": "Test",
    "lastName": "User",
    "email": "test@example.com",
    "password": "password123",
    "phone": "1234567890",
    "userStatus": 0
}
```

---

## 11. API 参考

### 11.1 BaseStep 完整 API

```java
// =====================================================
// 请求配置
// =====================================================
baseStep.setBaseUri(String baseUri);                     // 设置 Base URI
baseStep.setBasePath(String basePath);                   // 设置 Base Path
baseStep.setEndpoint(String endpoint);                   // 设置端点路径
baseStep.setRequestPayload(String payload);              // 设置请求体（字符串）
baseStep.setRequestBody(String requestBody);             // 设置请求体（别名）
baseStep.setProxy(String host, int port, String schema); // 设置代理
baseStep.setApiRequestResponseLogsEnabled(boolean);      // 开关请求/响应日志

// =====================================================
// 请求头操作
// =====================================================
baseStep.addRequestHeader(String name, Object value);    // 添加请求头
baseStep.updateHeader(String name, String value);        // 更新请求头
baseStep.removeHeader(String name);                      // 移除请求头
baseStep.removeHeaders(List<String> names);              // 批量移除请求头
baseStep.updateHeaders(Map<String, String> headers);     // 批量更新请求头
baseStep.clearHeader();                                  // 清空所有请求头

// =====================================================
// 路径参数操作
// =====================================================
baseStep.addPathParam(String name, Object value);        // 添加路径参数
baseStep.updatePathParam(String name, String value);     // 更新路径参数
baseStep.removePathParam(String name);                   // 移除路径参数
baseStep.removePathParams(List<String> names);           // 批量移除路径参数
baseStep.updatePathParams(Map<String, String> params);   // 批量更新路径参数
baseStep.clearPathParams();                              // 清空所有路径参数

// =====================================================
// Query 参数操作
// =====================================================
baseStep.addQueryParam(String name, Object value);       // 添加 Query 参数
baseStep.updateQueryParam(String name, String value);    // 更新 Query 参数
baseStep.removeQueryParam(String name);                  // 移除 Query 参数
baseStep.removeQueryParams(List<String> names);          // 批量移除 Query 参数
baseStep.updateQueryParams(Map<String, String> params);  // 批量更新 Query 参数
baseStep.clearQueryParams();                             // 清空所有 Query 参数

// =====================================================
// Form 参数操作
// =====================================================
baseStep.addFormParam(String name, Object value);        // 添加 Form 参数
baseStep.updateFormParam(String name, String value);     // 更新 Form 参数
baseStep.removeFormParam(String name);                   // 移除 Form 参数
baseStep.removeFormParams(List<String> names);           // 批量移除 Form 参数
baseStep.updateFormParams(Map<String, String> params);   // 批量更新 Form 参数
baseStep.clearFormParams();                              // 清空所有 Form 参数

// =====================================================
// Cookie 操作
// =====================================================
baseStep.addCookie(String name, Object value);           // 添加 Cookie
baseStep.updateCookieParam(String name, String value);   // 更新 Cookie
baseStep.removeCookieParam(String name);                 // 移除 Cookie
baseStep.removeCookieParams(List<String> names);         // 批量移除 Cookie
baseStep.updateCookieParams(Map<String, String> params); // 批量更新 Cookie
baseStep.clearCookies();                                 // 清空所有 Cookies

// =====================================================
// Payload 操作
// =====================================================
baseStep.loadPayload(String fileName);                   // 加载 payload 文件
baseStep.setRequestBody(String body);                    // 直接设置请求体
baseStep.modifyFieldsInRequestPayload(                   // 修改 payload 字段
    String fieldPath, Object fieldValue);

// =====================================================
// 发送请求
// =====================================================
baseStep.getResource();                                  // 发送 GET 请求
baseStep.postPayload();                                  // 发送 POST 请求
baseStep.putPayload();                                   // 发送 PUT 请求
baseStep.patchPayload();                                 // 发送 PATCH 请求
baseStep.deleteResource();                               // 发送 DELETE 请求
baseStep.getFullUrl(String fullUrl);                     // GET 全 URL（自动解析）
baseStep.postFullUrl(String fullUrl);                    // POST 全 URL（自动解析）
baseStep.putFullUrl(String fullUrl);                     // PUT 全 URL（自动解析）
baseStep.patchFullUrl(String fullUrl);                   // PATCH 全 URL（自动解析）
baseStep.deleteFullUrl(String fullUrl);                  // DELETE 全 URL（自动解析）

// =====================================================
// 端点配置操作
// =====================================================
baseStep.loadEndpointConfig(                             // 从配置文件加载端点
    String endpointName, String method);
baseStep.hasEndpointConfig(                              // 检查端点是否存在
    String endpointName, String method);

// =====================================================
// 获取响应
// =====================================================
baseStep.getResponseCode();                              // 获取 HTTP 状态码
baseStep.getResponseJson();                              // 获取响应体（JSON 字符串）
baseStep.getRequestBody();                               // 获取请求体
baseStep.getValidatableResponse();                       // 获取 Rest Assured ValidatableResponse

// =====================================================
// 响应验证
// =====================================================
baseStep.verifyResponseStatusCode(int expectedCode);     // 验证状态码
baseStep.verifyResponseJsonPath(                         // 验证 JSON 字段
    String fieldPath, Object expectedValue);
baseStep.verifyJsonArrayLength(                          // 验证 JSON 数组长度
    String arrayPath, int expectedLength);
baseStep.verifyResponseHeader(                           // 验证响应头
    String headerName, String expectedValue);
baseStep.verifyResponseBodyContains(                     // 验证响应体包含内容
    String expectedContent);
```

### 11.2 JsonUtils 工具类

```java
import com.hsbc.cmb.hk.dbb.automation.framework.api.utility.JsonUtils;
import com.jayway.jsonpath.TypeRef;

// 对象 ↔ JSON
String json = JsonUtils.toJson(myObject);
MyClass obj = JsonUtils.fromJson(json, MyClass.class);
Map<String, Object> map = JsonUtils.fromJson(json, new TypeRef<Map<String, Object>>() {});

// JSON 路径操作
Object value = JsonUtils.getValue(json, "$.data.id");
String name = JsonUtils.getValue(json, "$.name", String.class);
String modified = JsonUtils.setValue(json, "$.name", "New Name");
String deleted = JsonUtils.deleteValue(json, "$.tempField");

// JSON 验证与格式化
boolean valid = JsonUtils.isValidJson(json);
String pretty = JsonUtils.formatJson(json);
boolean equal = JsonUtils.equalsJson(json1, json2);

// JSON 合并
String merged = JsonUtils.mergeJson(json1, json2);

// 路径检查
boolean exists = JsonUtils.hasPath(json, "$.data.user.name");
List<Object> items = JsonUtils.getValues(json, "$.items[*].name");
```

### 11.3 FrameworkConfig 常用方法

```java
import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;

// HTTP 配置
int connectTimeout = FrameworkConfig.getConnectionTimeout();      // 默认: 30000
int socketTimeout = FrameworkConfig.getSocketTimeout();           // 默认: 30000
boolean sslRelax = FrameworkConfig.isSslRelaxValidation();        // 默认: true

// API 配置
String defaultBaseUri = FrameworkConfig.getDefaultBaseUri();      // 默认: http://localhost
boolean logEnabled = FrameworkConfig.isApiRequestResponseLogsEnabled(); // 默认: true

// 文件编码
String encoding = FrameworkConfig.getFileEncoding();              // 默认: UTF-8
String payloadEncoding = FrameworkConfig.getPayloadEncoding();    // 默认: UTF-8

// 测试重试
int retryCount = FrameworkConfig.getRetryCount();                 // 默认: 3
int retryDelay = FrameworkConfig.getRetryDelay();                 // 默认: 1000ms

// 通用取值
String value = FrameworkConfig.getString("some.path");
int intValue = FrameworkConfig.getInt("some.path");
boolean boolValue = FrameworkConfig.getBoolean("some.path");
boolean hasPath = FrameworkConfig.hasPath("some.path");
```

---

## 12. 环境切换与多环境支持

### 12.1 三种环境切换方式

**方式一：JVM 系统属性**（运行时指定）

```bash
# Maven 命令行
mvn test -Denv=dev
mvn test -Denv=test
mvn test -Denv=prod
```

**方式二：代码中指定**

```java
// 通过 TestServices
BaseStep baseStep = TestServices.initialize()
        .withEntity("petstore")
        .withEnv("dev")
        .baseStep();

// 通过 BaseStepFactory
BaseStep baseStep = BaseStepFactory.createWithEntity("petstore", "dev");
```

**方式三：EntityBuilder 构建时指定**

```java
Entity entity = EntityBuilder.build("petstore", "dev");
```

### 12.2 环境配置优先级（Environment Override）

```hocon
# 在 petstore.conf 中定义环境覆盖
dev {
    api.base.uri {
        default = "https://dev-api.example.com"    # 覆盖 Base URI
    }
    headers {
        Authorization = "Bearer dev-token-xxx"      # 覆盖/添加 Header
    }
    http {
        connection.timeout = 5000                    # 覆盖超时
        socket.timeout = 5000
    }
}

test {
    api.base.uri {
        default = "https://test-api.example.com"
    }
    headers {
        Authorization = "Bearer test-token-yyy"
    }
}

prod {
    api.base.uri {
        default = "https://api.example.com"
    }
    http.ssl.relax-validation = false                # 生产环境严格 SSL 验证
}
```

### 12.3 配置合并逻辑

```
最终配置 = env-specific配置 → 覆盖 → entity-specific配置 → 覆盖 → application.conf默认配置
```

合并过程中的日志示例：

```
[INFO] Active environment from EnvironmentUtils: dev
[INFO] Found environment(dev) configuration in config file
[INFO] Environment(dev) configuration keys: [api.base.uri, headers, http]
[INFO] Environment(dev) overrides configuration node: api.base.uri
[INFO] Environment(dev) overrides configuration node: headers
[INFO] Environment(dev) overrides configuration node: http
```

---

## 附录

### A. Maven 依赖

框架的核心依赖来自 `pom.xml`：

```xml
<!-- Rest Assured -->
<dependency>
    <groupId>io.rest-assured</groupId>
    <artifactId>rest-assured</artifactId>
</dependency>

<!-- Serenity BDD + Rest Assured 集成 -->
<dependency>
    <groupId>net.serenity-bdd</groupId>
    <artifactId>serenity-rest-assured</artifactId>
</dependency>

<!-- Typesafe Config -->
<dependency>
    <groupId>com.typesafe</groupId>
    <artifactId>config</artifactId>
    <version>1.4.2</version>
</dependency>

<!-- Jayway JsonPath -->
<dependency>
    <groupId>com.jayway.jsonpath</groupId>
    <artifactId>json-path</artifactId>
    <version>2.8.0</version>
</dependency>

<!-- Jackson -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>

<!-- Commons IO -->
<dependency>
    <groupId>commons-io</groupId>
    <artifactId>commons-io</artifactId>
    <version>2.11.0</version>
</dependency>

<!-- Hamcrest -->
<dependency>
    <groupId>org.hamcrest</groupId>
    <artifactId>hamcrest</artifactId>
</dependency>
```

### B. 运行命令

```bash
# 运行所有 API 测试
mvn clean verify -Dtags="api"

# 指定环境运行
mvn clean verify -Denv=dev -Dtags="api"

# 运行特定 Feature
mvn clean verify -Dcucumber.features="src/test/resources/features/api/petstore.feature"

# 运行特定 Scenario Tag
mvn clean verify -Dcucumber.filter.tags="@smoke"

# 运行 JUnit 测试类
mvn clean test -Dtest=PetstoreApiTest

# 调试模式
mvn clean verify -Denv=dev -Dmaven.surefire.debug
```

### C. 错误排查指南

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| `Configuration file does not exist` | Entity 配置文件未找到 | 检查 `config/{entityName}.conf` 是否存在 |
| `Entity name is required` | 未正确设置 entityName | 使用 `.withEntity("name")` |
| `Payload file NOT found` | Payload 文件路径错误 | 确认文件在 `src/test/resources/payload/` 下 |
| `JSON path not found` | JsonPath 表达式错误 | 使用 `$.field.subfield` 格式 |
| `Connection refused` | Base URI 或代理配置错误 | 检查 `api.base.uri` 和代理设置 |
| `SSL handshake failed` | SSL 证书问题 | 设置 `http.ssl.relax-validation = true` |
| 环境配置未生效 | 环境名不匹配或未设置 | 确认 `env` 节点名称正确，系统属性方式用 `-Denv=xxx` |

### D. 最佳实践建议

1. **配置文件分离**：全局配置放 `application.conf`，API 专用配置放 `{entityName}.conf`
2. **Payload 模板化**：将常用 JSON 模板放在 `payload/` 目录，运行时动态修改字段值
3. **端点统一管理**：使用 `end-points` 配置集中管理所有 API 端点，测试代码只引用端点名
4. **环境隔离**：使用 `dev/test/prod` 环境节点隔离不同环境的配置
5. **日志控制**：调试时开启 `api.request.response.logging.enabled = true`，CI 环境关掉以减少日志量
6. **使用 TestServices**：始终通过 `TestServices.initialize()` 创建 `BaseStep`，不直接 new
7. **超时设置**：根据网络环境合理设置 `http.connection.timeout` 和 `http.socket.timeout`
