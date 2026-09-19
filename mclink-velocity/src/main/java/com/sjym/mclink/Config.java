package com.sjym.mclink;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

/**
 * 插件配置：http 监听 + token + 子服 RCON 映射。
 * 从 data 目录 config.yml 读取，不存在则生成默认。
 */
public class Config {

    public static final class ServerRcon {
        public String rconHost;
        public int rconPort;
        public String rconPassword;
    }

    public int httpPort = 28080;
    public String httpHost = "127.0.0.1";
    // 默认占位密钥（sk- 前缀）：仅作缺省值，生产环境必须改成真实值并与 AstrBot 侧一致
    public String token = "sk-651c2f35f5";
    public final Map<String, ServerRcon> servers = new LinkedHashMap<>();

    public static Config load(Path dataDir, Logger logger) {
        Config cfg = new Config();
        Path f = dataDir.resolve("config.yml");
        if (!Files.exists(f)) {
            cfg.writeDefault(f);
            return cfg;
        }
        try {
            ConfigurationNode root = YAMLConfigurationLoader.builder()
                .setPath(f)
                .build()
                .load();
            cfg.parseNode(root);
            logger.info("[mclink] 已加载配置：{} 个 RCON 子服", cfg.servers.size());
        } catch (IOException e) {
            logger.error("[mclink] 读取 config.yml 失败，使用默认配置", e);
            cfg = new Config();
        }
        return cfg;
    }

    private void parseNode(ConfigurationNode root) {
        httpPort = root.getNode("http-port").getInt(httpPort);
        httpHost = root.getNode("http-host").getString(httpHost);
        token = root.getNode("token").getString(token);

        ConfigurationNode serversNode = root.getNode("servers");
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : serversNode.getChildrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            ConfigurationNode sn = entry.getValue();
            ServerRcon r = new ServerRcon();
            r.rconHost = sn.getNode("rcon-host").getString("127.0.0.1");
            r.rconPort = sn.getNode("rcon-port").getInt(25575);
            r.rconPassword = sn.getNode("rcon-password").getString("");
            servers.put(name, r);
        }
    }

    private void writeDefault(Path f) {
        try {
            Files.createDirectories(f.getParent());
            String yaml = defaultYaml();
            Files.writeString(f, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 忽略，内存默认配置仍可用
        }
    }

    private String defaultYaml() {
        return "http-host: 127.0.0.1\n"
            + "http-port: 28080\n"
            + "token: \"sk-651c2f35f5\"\n"
            + "servers:\n"
            + "  survival:\n"
            + "    rcon-host: 127.0.0.1\n"
            + "    rcon-port: 30166\n"
            + "    rcon-password: \"sk-651c2f35f5\"\n"
            + "  mirror:\n"
            + "    rcon-host: 127.0.0.1\n"
            + "    rcon-port: 30167\n"
            + "    rcon-password: \"sk-651c2f35f5\"\n";
    }
}
