# rcon_bridge

MCDR 内置 RCON 服务端插件。MCDR 自带的 `rcon` 配置是 RCON **客户端**
（MCDR 去连 MC 原版 RCON），本插件补上服务端一环：监听一个本地 TCP 端口，
接受标准 SOURCE RCON 协议连接，远程执行指令并回传输出。

主要用途：Velocity 插件 `mclink-velocity` 通过 RCON 执行 MCDR 指令
（如 `!!pb list`）并把输出回传 QQ（AstrBot `/mcm c`）。也可用任意标准
RCON 客户端（mcrcon 等）连接调试。

## 安装

把 `rcon_bridge.py` 放入 MCDR 的 `plugins` 目录，然后在 MCDR 控制台执行
`!!MCDR plugin load rcon_bridge`（或重启 MCDR）。

## 配置

`config/rcon_bridge/config.json`（首次加载自动生成，需要手动改）：

| 键 | 默认 | 说明 |
|---|---|---|
| `enable` | `true` | 是否启动 RCON 服务端 |
| `host` | `127.0.0.1` | 监听地址，保持本机即可 |
| `port` | `30166` | 监听端口（镜像服用 `30167`），需与 Velocity 侧 `config.yml` 一致 |
| `password` | `sk-` 前缀占位符 | RCON 密码，需与 Velocity 侧 `config.yml` 的 `rcon-password` 一致 |
| `native_output_wait` | `2.0` | 原版指令执行后收集输出的最长等待（秒） |
| `native_output_quiet` | `0.4` | 连续该时长无新输出则提前结束收集（秒） |
| `socket_timeout` | `60` | RCON 连接空闲超时（秒） |

## 指令分发规则

- `!!` 开头 → MCDR 指令系统执行（`!!pb list`、`!!MCDR status`、`!!msr start` 等），
  输出来自插件 reply，权限为最高级（4）
- 其它（含 `/` 开头）→ 原版指令，发送到服务端 stdin，收集执行期间的服务端日志回显
- 输出统一去掉 `§` 颜色码；超过 3800 字节按 UTF-8 字符边界自动分包

## 自带指令

- `!!rconbridge` — 查看监听状态

## 测试

`test_env/` 下是独立测试环境（假 MC 服务端 + 独立 MCDR 实例，端口 30999）：

```bash
cd test_env
Z:/Server/.venv/Scripts/python.exe -m mcdreforged   # 启动测试实例
Z:/Server/.venv/Scripts/python.exe test_client.py   # 跑 RCON 协议用例
```
