package com.sjym.mclink;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 内嵌 JDK HttpServer，接收 AstrBot 的绑定同步与远程指令，Token 鉴权。
 */
public class HttpBridge {

    // 请求体上限：绑定同步与指令转发远用不到 1MB，超限直接拒绝
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final Config config;
    private final BindingStore bindingStore;
    private final RconClient rconClient;
    private final Logger logger;
    private final Gson gson = new Gson();

    private HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public HttpBridge(Config config, BindingStore bindingStore, RconClient rconClient, Logger logger) {
        this.config = config;
        this.bindingStore = bindingStore;
        this.rconClient = rconClient;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.httpHost, config.httpPort), 0);
        server.createContext("/bindings/sync", this::handleBindingsSync);
        server.createContext("/bindings/remove", this::handleBindingsRemove);
        server.createContext("/bindings/query", this::handleBindingsQuery);
        server.createContext("/command", this::handleCommand);
        server.createContext("/health", this::handleHealth);
        server.setExecutor(executor);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdown();
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        sendJson(ex, 200, ok("ok"));
    }

    private void handleBindingsSync(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null || !checkToken(body, ex)) {
            return;
        }
        JsonElement e = body.get("bindings");
        if (e == null || !e.isJsonArray()) {
            sendJson(ex, 400, fail("缺少 bindings 数组"));
            return;
        }
        List<BindingStore.Binding> list = new ArrayList<>();
        JsonArray arr = e.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            BindingStore.Binding b = new BindingStore.Binding();
            b.qq = o.has("qq") ? o.get("qq").getAsString() : "";
            b.mc = o.has("mc") ? o.get("mc").getAsString() : "";
            if (!b.qq.isEmpty() && !b.mc.isEmpty()) {
                list.add(b);
            }
        }
        bindingStore.replaceAll(list);
        sendJson(ex, 200, ok("synced " + list.size()));
    }

    private void handleBindingsRemove(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null || !checkToken(body, ex)) {
            return;
        }
        String mc = body.has("mc") ? body.get("mc").getAsString() : "";
        boolean removed = bindingStore.removeByMc(mc);
        sendJson(ex, 200, ok(removed ? "removed" : "not found"));
    }

    private void handleBindingsQuery(HttpExchange ex) throws IOException {
        // GET；会暴露全部 QQ↔MC 映射，因此同样要求 token（通过 query 参数传递）
        String query = ex.getRequestURI().getQuery();
        String player = null;
        String token = null;
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("player=")) {
                    player = java.net.URLDecoder.decode(kv.substring("player=".length()), StandardCharsets.UTF_8);
                } else if (kv.startsWith("token=")) {
                    token = java.net.URLDecoder.decode(kv.substring("token=".length()), StandardCharsets.UTF_8);
                }
            }
        }
        if (token == null || !tokenEquals(config.token, token)) {
            sendJson(ex, 401, fail("token 错误"));
            return;
        }
        String qq = bindingStore.queryByMc(player);
        JsonObject data = new JsonObject();
        data.addProperty("mc", player == null ? "" : player);
        data.addProperty("qq", qq == null ? "" : qq);
        sendJson(ex, 200, okData(data));
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        JsonObject body = readJson(ex);
        if (body == null || !checkToken(body, ex)) {
            return;
        }
        String serverName = body.has("server") ? body.get("server").getAsString() : "";
        String command = body.has("command") ? body.get("command").getAsString() : "";
        int timeout = body.has("timeout") ? body.get("timeout").getAsInt() : 10;
        if (serverName.isEmpty() || command.isEmpty()) {
            sendJson(ex, 400, fail("缺少 server 或 command"));
            return;
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return rconClient.execute(serverName, command, timeout);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, executor);

        try {
            String output = future.get(timeout + 5, TimeUnit.SECONDS);
            sendJson(ex, 200, okDataString(output));
        } catch (TimeoutException te) {
            future.cancel(true);
            sendJson(ex, 504, fail("RCON 执行超时"));
        } catch (Exception e) {
            // 解包 CompletableFuture 的包装异常（ExecutionException → RuntimeException → IOException），
            // 把最内层的原因（如 "Connection refused"）直接给调用方，避免带异常类名前缀
            Throwable err = e;
            while (err.getCause() != null && err.getCause() != err) {
                err = err.getCause();
            }
            sendJson(ex, 500, fail(err.getMessage() != null ? err.getMessage() : err.toString()));
        }
    }

    // ---------- 辅助 ----------

    private boolean checkToken(JsonObject body, HttpExchange ex) throws IOException {
        String token = body.has("token") ? body.get("token").getAsString() : "";
        if (!tokenEquals(config.token, token)) {
            sendJson(ex, 401, fail("token 错误"));
            return false;
        }
        return true;
    }

    /** 恒时比较，避免 token 逐字节比较的时序侧信道。 */
    private static boolean tokenEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > MAX_BODY_BYTES) {
                sendJson(ex, 413, fail("请求体过大"));
                return null;
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new JsonObject();
            }
            try {
                return JsonParser.parseString(raw).getAsJsonObject();
            } catch (Exception e) {
                sendJson(ex, 400, fail("请求体不是合法 JSON"));
                return null;
            }
        }
    }

    private static JsonObject ok(String message) {
        JsonObject o = base(true);
        o.addProperty("data", message);
        return o;
    }

    private static JsonObject okData(JsonElement data) {
        JsonObject o = base(true);
        o.add("data", data);
        return o;
    }

    private static JsonObject okDataString(String data) {
        JsonObject o = base(true);
        o.addProperty("data", data);
        return o;
    }

    private static JsonObject fail(String error) {
        JsonObject o = base(false);
        o.addProperty("error", error);
        return o;
    }

    private static JsonObject base(boolean ok) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("error", "");
        return o;
    }

    private void sendJson(HttpExchange ex, int status, JsonObject obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
