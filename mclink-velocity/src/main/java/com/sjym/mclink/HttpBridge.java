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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 内嵌 JDK HttpServer，接收 AstrBot 的绑定同步与远程指令，Token 鉴权。
 */
public class HttpBridge {

    // 请求体上限：绑定同步与指令转发远用不到 1MB。
    // 读取是流式受限的（见 readLimited）：最多在内存中保留 MAX_BODY_BYTES+1 字节，
    // 读到第 +1 字节即判定超限并停止读取——绝不把超大请求体整个读进内存后再拒绝
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    // HTTP 处理线程上限：固定池。/command 的 RCON 执行内联在 handler 线程完成，
    // 线程占用上界由 RconClient 的 connect/so 双超时保证
    private static final int MAX_HTTP_THREADS = 16;

    private final Config config;
    private final BindingStore bindingStore;
    private final RconClient rconClient;
    private final Logger logger;
    private final Gson gson = new Gson();

    private HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_HTTP_THREADS);

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

        // 内联执行：RconClient 内部有 connect/so 双超时，线程占用有上界；
        // 之前经共享线程池 supplyAsync 再 get，池满时 RCON 任务排队、所有 handler
        // 线程都在等待自己排队的任务，会集体超时
        try {
            String output = rconClient.execute(serverName, command, timeout);
            sendJson(ex, 200, okDataString(output));
        } catch (IOException e) {
            sendJson(ex, 500, fail(e.getMessage() != null ? e.getMessage() : e.toString()));
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
            // 流式受限读取：读到 MAX_BODY_BYTES+1 字节即判超限（413）并停止，
            // 内存占用恒不超过限制本身，与请求体实际大小无关
            byte[] bytes = readLimited(in, MAX_BODY_BYTES);
            if (bytes == null) {
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

    /** 流式读取至多 limit 字节；超过 limit（读到 limit+1 字节即停）返回 null。 */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        byte[] buf = new byte[limit + 1];
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        if (off > limit) {
            return null;
        }
        return Arrays.copyOf(buf, off);
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
