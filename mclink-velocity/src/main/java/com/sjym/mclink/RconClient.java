package com.sjym.mclink;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 极简 MC RCON 协议客户端（SOURCE RCON）。
 *
 * 参考 MCDReforged 的 RconConnection 实现：
 * - 用 ENDING_PROBE（非法 request_id=100 的探测包）作为响应流结束标志，解决
 *   Minecraft 把长响应拆成多个包、且不提供明确终止信号的问题。
 * - 登录判定：收到 request_id == -1 的包即认证失败。
 *
 * 每次 execute 都新建一条 TCP 连接，用完后关闭，避免连接复用带来的串扰与半开连接问题。
 */
public class RconClient {

    private final Config config;
    private final Logger logger;

    private static final int PACKET_LOGIN = 3;
    private static final int PACKET_COMMAND = 2;
    private static final int PACKET_ENDING_PROBE = 100; // 非法 id，作为响应结束标志
    private static final int REQUEST_ID_LOGIN = 0;
    private static final int REQUEST_ID_COMMAND = 1;
    private static final int REQUEST_ID_LOGIN_FAIL = -1;

    public RconClient(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** 执行命令，返回响应文本；失败抛 IOException。 */
    public String execute(String serverName, String command, int timeoutSeconds) throws IOException {
        Config.ServerRcon rcon = config.servers.get(serverName);
        if (rcon == null) {
            throw new IOException("未配置服务器 " + serverName + " 的 RCON 信息");
        }
        if (rcon.rconHost == null || rcon.rconHost.isBlank()) {
            throw new IOException("服务器 " + serverName + " 未配置 rcon-host");
        }
        if (rcon.rconPassword == null || rcon.rconPassword.isEmpty()) {
            throw new IOException("服务器 " + serverName + " 未配置 rcon-password");
        }
        return execute(rcon.rconHost, rcon.rconPort, rcon.rconPassword, command, timeoutSeconds);
    }

    private String execute(String host, int port, String password, String command, int timeoutSeconds)
            throws IOException {
        int connectTimeoutMs = Math.max(timeoutSeconds, 5) * 1000;
        int readTimeoutMs = Math.max(timeoutSeconds, 5) * 1000;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // ---- LOGIN ----
            writePacket(out, REQUEST_ID_LOGIN, PACKET_LOGIN, password);
            RconPacket loginResp = readPacket(in);
            if (loginResp.id == REQUEST_ID_LOGIN_FAIL) {
                throw new IOException("RCON 认证失败：密码错误（服务器 " + host + ":" + port + "）");
            }

            // ---- COMMAND ----
            writePacket(out, REQUEST_ID_COMMAND, PACKET_COMMAND, command);
            StringBuilder sb = new StringBuilder();
            boolean endingProbeSent = false;
            while (true) {
                RconPacket p = readPacket(in);
                if (p.id != REQUEST_ID_COMMAND) {
                    // 收到 ENDING_PROBE 的响应（id == PACKET_ENDING_PROBE）或其它 id，表示命令输出已结束
                    break;
                }
                sb.append(p.body);
                if (!endingProbeSent) {
                    // 收到首个命令响应包后，说明 MC 已处理完 COMMAND 包，此时发送探测包作为结束标志
                    writePacket(out, PACKET_ENDING_PROBE, PACKET_ENDING_PROBE, "");
                    endingProbeSent = true;
                }
            }
            return sb.toString();
        }
    }

    private void writePacket(OutputStream out, int id, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + payload.length + 2; // id + type + body + 2 个 null
        ByteBuffer buf = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payload);
        buf.put((byte) 0);
        buf.put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private static final class RconPacket {
        int id;
        String body;
    }

    private RconPacket readPacket(InputStream in) throws IOException {
        byte[] lenBytes = readFully(in, 4);
        int length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 4096 * 1024) {
            throw new IOException("非法的 RCON 包长度：" + length);
        }
        byte[] rest = readFully(in, length);
        ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        RconPacket p = new RconPacket();
        p.id = buf.getInt();
        buf.getInt(); // type，忽略
        int bodyLen = length - 10; // id(4) + type(4) + 2 个 null
        byte[] bodyBytes = new byte[Math.max(bodyLen, 0)];
        buf.get(bodyBytes);
        p.body = new String(bodyBytes, StandardCharsets.UTF_8);
        return p;
    }

    private byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new IOException("RCON 连接被关闭（数据不完整，期望 " + n + " 字节，已读 " + off + " 字节）");
            }
            off += r;
        }
        return b;
    }
}
