# payload/ — 本地 Demo 请求体夹具（D-9）

本目录下的 JSON 是 **route-demo 本地演示服务** 的请求体夹具（`route-demo-service` / `route-demo-web`），
其中的 `username` / `password` 等字段值是**演示服务约定的固定测试值**，**不是任何真实凭据**。

| 文件 | 用途 |
|---|---|
| `api-demo-login-valid.json` | 演示服务登录成功用例（admin / password123） |
| `api-demo-login-invalid.json` | 演示服务登录失败用例（错误口令） |
| `api-demo-create-user.json` | 创建用户的模板（供 `PayloadFactory.createUser` / `uniqueUser` 变体覆盖） |
| `api-demo-update-user.json` | 更新用户的模板 |

## 约定（D-9）

- **禁止**把真实环境的账号 / 口令 / 令牌写入本目录。真实凭据一律经配置注入（`userinfo_*` + `ENC(...)` 密文
  或 CI Secrets），见 `serenity.conf` / `SecretValue`。
- 需要变体数据时，优先用 `tests.data.PayloadFactory`（模板 + `with` 覆盖 + `uniqueUser()` 随机化），
  而非复制新的 JSON 文件。

> 说明：这些演示口令与 `route-demo-*` 演示服务源码中的校验值一一对应，故保持字面值不变（改动会使
> 演示登录用例失配）；本 README 的目的即在于**显式标记其非机密性**，避免"明文凭据可入仓"的坏示范被沿用。
