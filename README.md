<p align="center">
  <img src="https://raw.githubusercontent.com/s-j-y-m/astrbot_plugin_mc_butler/master/logo.png" width="160" alt="MC 服务器大管家">
</p>

# astrbot_plugin_mc_butler_VC-MCDR

**MC 服务器大管家**（[astrbot_plugin_mc_butler](https://github.com/s-j-y-m/astrbot_plugin_mc_butler)）的配套组件仓库：Velocity 代理插件 + MCDR 子服插件。三者配合实现「QQ 绑定白名单 → 远程指令 → 子服状态查询」完整链路，整体架构见主仓库 [完整说明](https://github.com/s-j-y-m/astrbot_plugin_mc_butler/blob/master/完整说明.md)。

## 📂 组件

| 组件 | 安装位置 | 当前版本 | 说明 |
|---|---|---|---|
| [mclink-velocity](mclink-velocity/) | Velocity 代理 `plugins/`（只装一次） | 1.0.1 | 进服绑定拦截 + HTTP→RCON 指令桥（`127.0.0.1:28080`） |
| [rcon_bridge](rcon_bridge/) | 每个子服的 MCDR `plugins/` | 1.2.2 | MCDR 内置 RCON **服务端**（生存服 `30166` / 镜像服 `30167`） |

## 🚀 安装

**mclink-velocity（Velocity 侧）**：把 `mclink-velocity.jar` 放入 Velocity 的 `plugins/` 目录，首次启动自动生成 `plugins/mclink-velocity/config.yml`，填入 token 与各子服 RCON 端口/密码。源码与构建见 [mclink-velocity/src](mclink-velocity/src/main/java/com/sjym/mclink/) 与 `build.ps1`（需 JDK 25，编译 `--release 21`）。

**rcon_bridge（MCDR 侧）**：把 `rcon_bridge/rcon_bridge.py` 放入每个子服 MCDR 的 `plugins/` 目录，首次加载后修改 `config/rcon_bridge/config.json` 的端口与密码。

**AstrBot 侧**：主插件从插件市场或主仓库安装 → [astrbot_plugin_mc_butler](https://github.com/s-j-y-m/astrbot_plugin_mc_butler)。

## 🔑 三处配置对应关系

- Velocity `config.yml` 的 `token` ＝ AstrBot 插件配置的 `velocity_token`
- Velocity `config.yml` 的 `servers.<名>.rcon-port` / `rcon-password` ＝ 对应子服 `config/rcon_bridge/config.json` 的 `port` / `password`
- MCDR 自带 `config.yml` 的 `rcon:` 段（RCON **客户端**）也指向同一端口/密码；MC 原版 `server.properties` 的 `enable-rcon` 保持关闭
- 所有默认密钥均为 `sk-` 前缀占位符，生产环境必须替换

## 📄 许可

AGPL-3.0，详见 [LICENSE](LICENSE)。
