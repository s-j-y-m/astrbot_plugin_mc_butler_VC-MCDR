package com.sjym.mclink;

import com.google.gson.Gson;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import com.google.inject.Inject;

/**
 * McLink Velocity 插件：
 * - 内嵌 HTTP 服务，接收 AstrBot 的绑定同步与远程指令。
 * - 玩家进服时校验是否已绑定 QQ，未绑定则踢出。
 */
@Plugin(
    id = "mclink-velocity",
    name = "McLinkVelocity",
    version = "1.0.2",
    description = "QQ bind guard and MCDR command bridge",
    authors = {"Sjym"}
)
public class McLinkPlugin {

    @Inject
    private ProxyServer server;

    @Inject
    private Logger logger;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private BindingStore bindingStore;
    private HttpBridge httpBridge;
    private RconClient rconClient;

    @Subscribe
    public void onProxyInitialization(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        Config config = Config.load(dataDirectory, logger);
        this.bindingStore = new BindingStore(dataDirectory, logger);
        this.rconClient = new RconClient(config, logger);
        this.httpBridge = new HttpBridge(config, bindingStore, rconClient, logger);

        try {
            this.httpBridge.start();
            logger.info("[mclink] HTTP 桥已启动，监听 {}:{}", config.httpHost, config.httpPort);
        } catch (Exception e) {
            logger.error("[mclink] HTTP 桥启动失败", e);
        }

        server.getEventManager().register(this, LoginEvent.class, this::onLogin);
        logger.info("[mclink] 插件已初始化，绑定数据目录：{}", dataDirectory.toAbsolutePath());
    }

    @Subscribe
    public void onProxyShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (httpBridge != null) {
            httpBridge.stop();
        }
        logger.info("[mclink] 插件已关闭");
    }

    private void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        if (username == null || username.isBlank()) {
            return;
        }
        if (bindingStore.isBound(username)) {
            logger.info("[mclink] 玩家 {} 已绑定，放行", username);
            return;
        }
        event.setResult(LoginEvent.ComponentResult.denied(
            Component.text("请先在 QQ 群发送 /bind <游戏ID> 完成绑定后再进服", NamedTextColor.RED)
        ));
        logger.info("[mclink] 未绑定玩家 {} 被踢出", username);
    }
}
