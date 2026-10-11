# 管理后台部署与访问

管理员账号独立存储，不复用 SeekCHA 普通用户账号、密码或登录会话。后台认证要求独立密码和 TOTP 动态口令；管理员 API、初始化及登录只接受配置的私有网络来源。普通用户站点可继续公开访问。

## 私网接入

推荐通过 Tailscale 等私有组网访问。让管理员电脑与服务器加入同一网络，并将服务器私网地址对应的 CIDR 配置到 `DORMCHECK_ADMIN_ALLOWED_CIDRS`。默认值包含 RFC1918 局域网及 Tailscale CGNAT 网段。应用仍应绑定回环地址并由受控反向代理转发；不要放行 `0.0.0.0/0`、`::/0`，不要直接暴露 Java 服务端口。

## 建立首位管理员

1. 在可信电脑生成一次性高熵密钥，例如 `openssl rand -hex 32`。
2. 将密钥暂时配置为服务器环境变量 `DORMCHECK_ADMIN_BOOTSTRAP_TOKEN`，同时正确配置 `DORMCHECK_ADMIN_ALLOWED_CIDRS`。
3. 重启服务后，从允许的私网来源调用 `POST /api/admin/auth/bootstrap`，提交 `token`、`email`、`name` 和至少 16 位的独立 `password`。邮箱应为 `njhtr1018@gmail.com`。响应会给出 TOTP 密钥和 `otpauth_url`；立即导入验证器并妥善保管。
4. 调用 `POST /api/admin/auth/bootstrap/confirm`，提交同一 token、邮箱及验证器当前的 6 位动态口令，完成绑定。此接口只允许一次性初始化记录，成功后无法重复启用。
5. 从服务器环境移除 `DORMCHECK_ADMIN_BOOTSTRAP_TOKEN` 并重启服务。管理员登录使用站点中的“管理员登录”入口，填入独立密码和 TOTP。

初始化请求示例：

```sh
curl -X POST http://127.0.0.1:8001/api/admin/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"token":"<一次性密钥>","email":"njhtr1018@gmail.com","name":"NJHTR","password":"<至少16位独立密码>"}'
```

该接口在已有任何管理员账号时拒绝创建；不存在管理员公开注册或普通用户提升权限的入口。不要把初始化密钥、密码、TOTP 密钥或 `otpauth_url` 放进代码仓库、工单或聊天记录。初始化响应中的 TOTP 密钥只显示一次。

## 角色与范围

- `analyst`：只读汇总统计，不返回用户级数据。
- `operator`：查看脱敏用户配置、当前队列、查寝执行记录和课表/公告访问事件。
- `owner`：包含运维只读权限并查看管理员访问审计。

首版不提供管理员网页端增删账号、修改角色、代用户操作、查看或修改校园凭据等能力。额外管理员需经受控运维流程创建，不能用普通 SeekCHA 账号转换。

## 数据保护

- 邮箱在后台列表中脱敏；校园认证仅暴露配置/验证状态，不返回凭据或账号提示。
- 查寝错误信息限制长度并清洗邮箱、password、cookie、token 等字段。
- 课表和公告事件不记录缓存正文或校园平台响应内容；明细保留 90 天。
- 管理员会话使用独立 HttpOnly、SameSite=Strict Cookie、CSRF token，8 小时过期；管理员登录有速率限制和失败退避。
- 管理接口访问写入审计记录；审计表没有网页删除接口。
- IP 校验依赖受控反向代理覆盖写入的来源地址；不要透传用户可控的转发头。
