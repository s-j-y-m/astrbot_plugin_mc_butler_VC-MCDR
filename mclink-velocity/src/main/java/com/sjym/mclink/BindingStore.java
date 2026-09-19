package com.sjym.mclink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 绑定表读写：bindings.json，内存用并发 map，MC ID 小写归一化查询。
 */
public class BindingStore {

    public static final class Binding {
        public String qq;
        public String mc;
    }

    private static final Type BINDINGS_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Path dataDir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // key 为小写 mc id，value 为 QQ（用于进服拦截快速判断）
    private final Map<String, String> byMc = new ConcurrentHashMap<>();
    // key 为 QQ，value 为该 QQ 绑定的 MC 列表（保持顺序）
    private final Map<String, List<String>> byQq = new ConcurrentHashMap<>();

    public BindingStore(Path dataDir, Logger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
        load();
    }

    private Path file() {
        return dataDir.resolve("bindings.json");
    }

    public synchronized void load() {
        byMc.clear();
        byQq.clear();
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            Map<String, Object> root = gson.fromJson(raw, BINDINGS_TYPE);
            if (root == null) {
                return;
            }
            Object bindingsObj = root.get("bindings");
            if (!(bindingsObj instanceof List)) {
                return;
            }
            List<?> list = (List<?>) bindingsObj;
            for (Object o : list) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                String qq = str(m.get("qq"));
                String mc = str(m.get("mc"));
                if (qq == null || mc == null || qq.isEmpty() || mc.isEmpty()) {
                    continue;
                }
                String mcLow = mc.toLowerCase(Locale.ROOT);
                byMc.put(mcLow, qq);
                byQq.computeIfAbsent(qq, k -> new ArrayList<>()).add(mc);
            }
            logger.info("[mclink] 已加载 {} 条绑定", byMc.size());
        } catch (Exception e) {
            logger.error("[mclink] 读取 bindings.json 失败", e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // 读者与写者（replaceAll/removeByMc 的 clear-then-rebuild）互斥，
    // 避免全量同步瞬间登录校验读到空表而误踢已绑定玩家
    public synchronized boolean isBound(String mcId) {
        if (mcId == null) {
            return false;
        }
        return byMc.containsKey(mcId.toLowerCase(Locale.ROOT));
    }

    public synchronized String queryByMc(String mcId) {
        if (mcId == null) {
            return null;
        }
        return byMc.get(mcId.toLowerCase(Locale.ROOT));
    }

    /** 全量替换绑定表。 */
    public synchronized void replaceAll(List<Binding> bindings) {
        byMc.clear();
        byQq.clear();
        if (bindings != null) {
            for (Binding b : bindings) {
                if (b == null || b.qq == null || b.mc == null) {
                    continue;
                }
                String mcLow = b.mc.toLowerCase(Locale.ROOT);
                byMc.put(mcLow, b.qq);
                byQq.computeIfAbsent(b.qq, k -> new ArrayList<>()).add(b.mc);
            }
        }
        persist();
        logger.info("[mclink] 绑定表已全量替换为 {} 条", byMc.size());
    }

    /** 按 MC ID 解绑，返回是否真的删除了。 */
    public synchronized boolean removeByMc(String mcId) {
        if (mcId == null) {
            return false;
        }
        String mcLow = mcId.toLowerCase(Locale.ROOT);
        String qq = byMc.remove(mcLow);
        if (qq != null) {
            List<String> mcs = byQq.get(qq);
            if (mcs != null) {
                mcs.removeIf(m -> m.toLowerCase(Locale.ROOT).equals(mcLow));
                if (mcs.isEmpty()) {
                    byQq.remove(qq);
                }
            }
            persist();
            return true;
        }
        return false;
    }

    private void persist() {
        List<Binding> list = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byQq.entrySet()) {
            for (String mc : e.getValue()) {
                Binding b = new Binding();
                b.qq = e.getKey();
                b.mc = mc;
                list.add(b);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bindings", list);
        String json = gson.toJson(root);

        Path f = file();
        try {
            Files.createDirectories(dataDir);
            Path tmp = dataDir.resolve("bindings.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("[mclink] 写入 bindings.json 失败", e);
        }
    }
}
