# mclink-velocity

Velocity 代理插件：QQ ↔ MC 账号绑定拦截 + 远程 MCDR 指令桥接。

- **进服拦截**：玩家进服时校验 `bindings.json`，未绑定 QQ 则踢出。
- **HTTP 桥**：内嵌 JDK `HttpServer`，监听 `127.0.0.1:28080`，Token 鉴权，接收 AstrBot 的绑定同步与远程指令。
- **RCON 执行**：通过 SOURCE RCON 协议向子服 MCDR 执行 `!!` 指令并回传输出。

## 目录结构

```
mclink-velocity/
├── src/main/java/com/sjym/mclink/
│   ├── McLinkPlugin.java   # @Plugin 主类，启动 HTTP、注册事件
│   ├── BindingStore.java   # bindings.json 读写 + 小写归一化查询
│   ├── Config.java         # 极简 YAML 配置解析
│   ├── HttpBridge.java     # HTTP 服务 + Token 鉴权 + JSON 解析
│   ├── LoginGuard (内嵌于 McLinkPlugin)  # LoginEvent 拦截
│   └── RconClient.java     # 极简 SOURCE RCON 实现
├── velocity-plugin.json    # 手工生成的插件描述
├── build.ps1               # javac + jar 打包脚本
└── README.md
```

## 构建

```powershell
cd velocity\plugins-src\mclink-velocity
powershell -ExecutionPolicy Bypass -File build.ps1
# 产物 mclink-velocity.jar 生成在本目录，需手动拷贝到 velocity\plugins\ 覆盖
# 替换前必须先停止 Velocity（jar 被 JVM 锁定），拷贝后再启动生效
```

> 编译需 JDK 25+（velocity jar 内注解处理器是 class file 69.0，旧 javac 读不了；产物 `--release 21`，运行时 Java 21 兼容）。
> `build.ps1` 按顺序自动探测：`-JdkRoot` 参数 → `JAVA_HOME` → PATH，取第一个满足 25+ 的；都找不到会报错提示。
> 想固定本机 JDK：在旁边建 `build.local.ps1`（已被 .gitignore 排除）调用 `& (Join-Path $PSScriptRoot "build.ps1") -JdkRoot "你的JDK路径"`，脚本内不含任何机器专属路径。

## 配置

首次启动会在插件数据目录（`velocity\plugins\mclink-velocity\`）自动生成 `config.yml`：

```yaml
http-host: 127.0.0.1
http-port: 28080
token: "sk-xxxxxxxxxx"
servers:
  survival:
    rcon-host: 127.0.0.1
    rcon-port: 30166
    rcon-password: "sk-xxxxxxxxxx"
  mirror:
    rcon-host: 127.0.0.1
    rcon-port: 30167
    rcon-password: "sk-xxxxxxxxxx"
```

- 默认生成的密钥仅为 `sk-` 前缀占位符（sk- + 随机数），生产环境必须替换成真实值。
- `token` 必须与 AstrBot 插件配置 `velocity_token` 一致。
- `servers.<name>.rcon-*` 必须与对应子服 MCDR 的 **rcon_bridge 插件**配置（`config/rcon_bridge/config.json`）一致；MCDR `config.yml` 的 `rcon:` 段是 MCDR 自带的 RCON 客户端，也应指向同一端口/密码（即 rcon_bridge 的监听端口，而非 MC 原版 RCON）。

## HTTP 接口

统一响应：`{"ok": true, "data": "...", "error": ""}`。除 `/health` 外均需鉴权：
POST 接口的 token 放在 JSON 体 `{"token": ...}` 中，GET 的 `/bindings/query` 用 query 参数 `token=` 传递（均为恒时比较）。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/bindings/sync` | body token | 全量替换绑定表 |
| POST | `/bindings/remove` | body token | 按 MC ID 解绑 |
| POST | `/command` | body token | RCON 向子服转发指令并回传输出 |
| GET | `/bindings/query?player=Steve&token=...` | query token | 按 MC ID 查询 QQ |
| GET | `/health` | 无 | 存活检查 |

请求体上限 1MB；`/command` 的 `server` 必须是 `config.yml` `servers:` 中已配置的名字。

## 数据落地

`velocity\plugins\mclink-velocity\bindings.json`：

```json
{"bindings":[{"qq":"10001","mc":"steve"}]}
```

写入使用临时文件 + `ATOMIC_MOVE`，防止写坏。

## 说明

- 进服拦截使用 `LoginEvent`，MC ID 小写归一化匹配；绑定表读者与全量替换互斥（`synchronized`），同步瞬间不会误踢已绑定玩家。
- RCON 采用阻塞 IO，放入线程池执行，HTTP 层用 `CompletableFuture` 超时兜底；执行失败会把最内层原因（如 `Connection refused`）原样返回给调用方。
- 指令转发对内容不敏感：`!!` 与原版指令的区分、执行者元数据（`\x01` 尾巴）的剥离，都由子服 MCDR 的 rcon_bridge 插件负责，本插件原样透传。
