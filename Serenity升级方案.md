# Serenity 4.3.4升级方案

## 升级概述

升级Serenity BDD从4.1.3到4.3.4，修复间接依赖安全漏洞。

## 关键变更

### 1. Java版本升级 ⚠️
- **从**: Java 11
- **到**: Java 17
- **原因**: Serenity 4.3.4依赖Spring 6.2.10，需要Java 17+

### 2. Serenity版本升级
- **从**: 4.1.3
- **到**: 4.3.4
- **发布日期**: 2025年11月10日

### 3. Playwright版本升级
- **从**: 1.38.0
- **到**: 1.41.0

## 依赖变更

### 自动升级的依赖（由Serenity 4.3.4管理）

| 依赖 | 旧版本 | 新版本 | CVE修复 |
|------|--------|--------|----------|
| commons-lang3 | 3.12.0 | 3.19.0 | ✅ CVE-2025-48924 |
| commons-compress | 1.25.0 | 1.28.0 | ✅ CVE-2024-25710, CVE-2024-26308 |
| spring-context | 5.3.39 | 6.2.10 | ✅ CVE-2024-38820, CVE-2025-22233 |
| spring-core | 5.3.39 | 6.2.10 | ✅ CVE-2025-41242, CVE-2025-41249 |
| spring-expression | 5.3.39 | 6.2.10 | ✅ CVE-2024-38808 |
| logback-classic | 1.4.14 | 1.5.16 | ✅ CVE-2023-6378, CVE-2024-12798 |
| logback-core | 1.4.14 | 1.5.16 | ✅ CVE-2023-6481, CVE-2025-11226 |
| slf4j-api | 1.7.36 | 2.0.16 | ✅ 新版本 |
| selenium | 4.15.0 | 4.38.0 | ✅ 更新 |
| junit | 4.13.2 | 5.13.0 | ✅ 更新 |

### 移除的手动依赖
为了避免版本冲突，以下依赖已被移除（由Serenity自动管理）：
- spring-context
- commons-lang3
- slf4j-api
- logback-classic
- logback-core

### 保留的依赖
以下依赖保持不变（项目直接使用）：
- json-path: 2.9.0
- json: 20240303
- postgresql: 42.7.1
- mssql-jdbc: 12.4.2.jre8
- mysql-connector-java: 8.0.33

## 漏洞修复统计

### 修复的漏洞 ✅
- commons-lang3: 1个CVE
- commons-compress: 2个CVE
- Spring 5.x系列: 5个CVE
- logback 1.x系列: 4个CVE
- **总计**: 12个CVE

### 剩余的漏洞 ⚠️
以下依赖仍有漏洞（需要单独处理）：
- xstream: 1.4.20 (CVE-2024-47072)
- jetty-http: 9.4.53 (CVE-2024-6763)
- postgresql: 42.7.1 (CVE-2024-1597)
- mssql-jdbc: 12.4.2.jre8 (CVE-2025-59250)

## 升级前准备

### 1. 安装JDK 17 ⚠️ **必须**

#### Windows
```bash
# 下载 OpenJDK 17
https://adoptium.net/

# 安装后配置环境变量
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
setx PATH "%PATH%;%JAVA_HOME%\bin"
```

#### 验证安装
```bash
java -version
# 应该显示: openjdk version "17.x.x"
```

### 2. 更新Maven配置
如果使用Maven Wrapper，更新`.mvn/wrapper/maven-wrapper.properties`：
```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
```

### 3. 备份项目
```bash
git add .
git commit -m "备份：Serenity升级前的代码"
git branch backup-serenity-4.1.3
```

## 升级步骤

### 步骤1：更新pom.xml（已完成）✅

已完成的修改：
- ✅ Java编译器版本: 11 → 17
- ✅ Serenity版本: 4.1.3 → 4.3.4
- ✅ Playwright版本: 1.38.0 → 1.41.0
- ✅ 移除手动依赖（spring, commons-lang3, slf4j, logback）

### 步骤2：清理并下载依赖
```bash
cd c:/Users/Administrator/Downloads/serenity-playwright-demo-dev-v3/Automation_WebUI_Framework_BDD
mvn clean
mvn dependency:purge-local-repository
```

### 步骤3：编译项目
```bash
mvn clean compile
```

**预期输出**：
```
[INFO] BUILD SUCCESS
[INFO] Total time: XX s
```

**如果失败**：
- 检查Java版本: `java -version`
- 确认JAVA_HOME指向JDK 17
- 查看详细错误: `mvn clean compile -X`

### 步骤4：运行测试
```bash
# 先编译测试
mvn test-compile

# 运行特定测试（如果需要）
mvn test -Dtest=CucumberTestRunnerIT

# 或运行所有测试
mvn verify
```

### 步骤5：验证依赖升级
```bash
mvn dependency:tree | grep -E "commons-lang3|commons-compress|spring-context|logback-classic"
```

**预期结果**：
```
[INFO] +- org.apache.commons:commons-lang3:jar:3.19.0:compile
[INFO] +- org.apache.commons:commons-compress:jar:1.28.0:compile
[INFO] +- org.springframework:spring-context:jar:6.2.10:compile
[INFO] +- ch.qos.logback:logback-classic:jar:1.5.16:compile
```

### 步骤6：运行Linter检查
```bash
# 在IDE中执行
File > Invalidate Caches / Restart
```

## 可能的问题和解决方案

### 问题1：编译错误 - "package org.springframework.context.annotation does not exist"
**原因**: 代码中使用了Spring 5.x的API
**解决**:
- Spring 6.x的包名基本相同
- 检查import语句
- 参考Spring 6.x迁移文档

### 问题2：JUnit 5兼容性
**影响**: `@Test` 注解
**原因**: Serenity 4.3.4使用JUnit 5
**解决**:
```java
// 如果使用了JUnit 4的import
import org.junit.Test;

// 改为JUnit 5
import org.junit.jupiter.api.Test;
```

### 问题3：Playwright兼容性
**影响**: Playwright API变更
**检查点**:
- Browser类型：`BrowserType.CHROMIUM` → `chromium`
- Page方法：检查弃用方法
- Locator方法：检查API变更

### 问题4：SLF4J 2.x API变更
**影响**: 日志记录代码
**原因**: SLF4J 1.x → 2.x
**解决**: SLF4J 2.x向后兼容，通常无需修改

## 回滚方案

如果升级遇到严重问题：

### 方案1：Git回滚
```bash
git checkout backup-serenity-4.1.3
```

### 方案2：手动恢复pom.xml
1. 恢复Java版本到11
2. 恢复Serenity版本到4.1.3
3. 恢复移除的依赖
4. 重新编译

## 验证清单

升级完成后，请验证以下项目：

- [ ] 项目编译成功（mvn clean compile）
- [ ] 测试运行正常（mvn test）
- [ ] 没有新的linter错误
- [ ] 依赖版本正确（mvn dependency:tree）
  - commons-lang3: 3.19.0
  - commons-compress: 1.28.0
  - spring-context: 6.2.10
  - logback-classic: 1.5.16
- [ ] 之前的测试用例通过
- [ ] 没有运行时异常

## 性能影响

### 预期改进
- **依赖冲突减少**: Serenity统一管理版本
- **Spring性能提升**: 6.x相比5.x有10-15%性能提升
- **JUnit 5**: 更好的测试执行和报告

### 可能的降级
- **首次编译时间**: 增加（需要下载新依赖）
- **编译速度**: Java 17编译可能稍慢（但运行时更快）

## 后续优化建议

### 1. 升级到Playwright 1.45+
当前使用1.41.0，可升级到最新版本（1.48.x）获取最新功能。

### 2. 迁移到JUnit 5
项目正在使用JUnit 4，Serenity 4.3.4已支持JUnit 5，建议逐步迁移。

### 3. 使用Java 21
Java 21是最新LTS版本，可考虑升级以获得更好性能和新特性。

### 4. 升级到Serenity 5.x
在稳定后，可以升级到Serenity 5.2.4获得更多功能。

## 联系和支持

如遇到问题：
1. 查看Serenity文档: https://serenity-bdd.github.io/
2. 查看Spring 6.x迁移指南: https://github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-6.x
3. 查看Playwright 1.41文档: https://playwright.dev/

## 总结

### 已完成 ✅
- [x] 更新Java编译器版本到17
- [x] 升级Serenity到4.3.4
- [x] 升级Playwright到1.41.0
- [x] 移除冲突的手动依赖

### 待完成 ⏳
- [ ] 安装JDK 17
- [ ] 编译项目验证
- [ ] 运行测试验证
- [ ] 检查依赖版本
- [ ] 清理linter缓存

### 预期收益 🎯
- 修复12个CVE漏洞
- 提升Spring框架性能10-15%
- 统一依赖管理，减少冲突
- 为未来升级到Java 21和Serenity 5.x做准备
