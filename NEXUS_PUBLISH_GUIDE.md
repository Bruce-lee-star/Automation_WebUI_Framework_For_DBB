# Nexus 发布指南

## 一、概述

本文档说明如何将 `Automation_WebUI_Framework_BDD` 的 main 包打成 JAR 发布到 Nexus 仓库，供其他项目作为框架依赖使用。

---

## 二、pom.xml 修改清单

### 2.1 需要新增的内容

#### a) `<distributionManagement>`（Nexus 仓库地址）

在 `</dependencies>` 之后、`<build>` 之前加入。**URL 请替换为实际的 Nexus 地址。**

```xml
<distributionManagement>
    <repository>
        <id>nexus-releases</id>
        <url>http://your-nexus-host/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
        <id>nexus-snapshots</id>
        <url>http://your-nexus-host/repository/maven-snapshots/</url>
    </snapshotRepository>
</distributionManagement>
```

#### b) `<maven-source-plugin>`（源码 JAR）

在 `<build><plugins>` 中加入，其他项目引用时可看到源码和 Javadoc：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-source-plugin</artifactId>
    <version>3.3.0</version>
    <executions>
        <execution>
            <id>attach-sources</id>
            <phase>verify</phase>
            <goals>
                <goal>jar-no-fork</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 2.2 需要修改 scope 的依赖

当前以下依赖是 **compile** scope（会传递到下游项目），但它们仅用于测试，应改为 `test` scope：

| 依赖 | 当前 scope | 应改为 | 原因 |
|------|-----------|--------|------|
| `junit:junit` | 无（compile） | `<scope>test</scope>` | 仅测试用 |
| `org.hamcrest:hamcrest-core` | 无（compile） | `<scope>test</scope>` | 仅测试断言用 |

修改方式（以 `junit` 为例）：

```xml
<dependency>
    <groupId>junit</groupId>
    <artifactId>junit</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>   <!-- 新增这一行 -->
</dependency>
```

> **注意**：`serenity-junit` 已经是 `test` scope（第 102 行），无需修改。

### 2.3 不需要删除的内容

| 内容 | 原因 |
|------|------|
| **`src/test/java` 目录** | Maven 打包 JAR 时**只会包含 `src/main/java`**，测试代码不会被编入最终 JAR，**无需删除** |
| **`src/test/resources`** | 同上，不会被打入 JAR |
| **`maven-surefire-plugin`** | 仅在 `test` 阶段运行，不影响 `package/deploy` 产物 |
| **`maven-failsafe-plugin`** | 同上 |
| **`serenity-maven-plugin`** | 仅在 `post-integration-test` 阶段生成报告，不影响 JAR |
| **`exec-maven-plugin`** | 同上，且引用的 `SummaryReportGenerator` 在 `src/main/java` 中，保留合理 |

### 2.4 不需要删除，但发布时可跳过的插件

如果希望 `mvn deploy` 时不运行测试和报告生成，用命令参数控制即可：

```bash
mvn clean deploy -DskipTests
```

无需修改 pom.xml 中的插件配置。

---

## 三、settings.xml 配置（Nexus 认证）

编辑 `~/.m2/settings.xml`（Windows 路径：`C:\Users\<用户名>\.m2\settings.xml`）：

```xml
<settings>
    <servers>
        <server>
            <id>nexus-releases</id>
            <username>your-username</username>
            <password>your-password</password>
        </server>
        <server>
            <id>nexus-snapshots</id>
            <username>your-username</username>
            <password>your-password</password>
        </server>
    </servers>
</settings>
```

`<server><id>` 必须与 `distributionManagement` 中的 `<repository><id>` 一致。

---

## 四、发布命令

### SNAPSHOT 版本（version 以 `-SNAPSHOT` 结尾）

```bash
mvn clean deploy -DskipTests
```

### Release 版本（正式版）

首先确保 `<version>` 不含 `-SNAPSHOT`：

```xml
<version>1.0.0</version>
```

然后执行：

```bash
mvn clean deploy -DskipTests
```

---

## 五、下游项目引用方式

```xml
<dependency>
    <groupId>com.hsbc.cmb.dbb.hk.automation</groupId>
    <artifactId>Automation_WebUI_Framework_BDD</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 六、修改汇总

| 序号 | 操作 | 位置 | 说明 |
|------|------|------|------|
| 1 | **新增** | pom.xml `</dependencies>` 后 | `<distributionManagement>` 块 |
| 2 | **新增** | pom.xml `<build><plugins>` 中 | `maven-source-plugin` |
| 3 | **修改** | pom.xml `junit` 依赖 | 添加 `<scope>test</scope>` |
| 4 | **修改** | pom.xml `hamcrest-core` 依赖 | 添加 `<scope>test</scope>` |
| 5 | **新增** | `~/.m2/settings.xml` | Nexus 认证 `server` 配置 |

**无需删除任何文件或目录。**
