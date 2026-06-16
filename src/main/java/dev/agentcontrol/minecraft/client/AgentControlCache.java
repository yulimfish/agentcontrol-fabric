package dev.agentcontrol.minecraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 AgentControl 的世界方块缓存。
 * 将已探索区域的方块数据持久化到磁盘，支持跨会话恢复。
 * 按维度（overworld/nether/end）隔离缓存。
 */
public class AgentControlCache {
    private static final String CACHE_DIR_NAME = "agentcontrol-cache";
    private static final int SAVE_THRESHOLD_BLOCKS = 500;
    private static final long SAVE_INTERVAL_MS = 30_000; // 30 seconds

    private final Map<String, String> blocks = new ConcurrentHashMap<>();
    private final Path cacheFile;
    private volatile boolean dirty = false;
    private volatile int newBlocksSinceSave = 0;
    private long lastSaveTime = 0;

    public AgentControlCache(Identifier dimension) {
        MinecraftClient client = MinecraftClient.getInstance();
        Path gameDir = client.runDirectory.toPath();
        Path cacheDir = gameDir.resolve(CACHE_DIR_NAME);
        // 使用维度路径作为文件名（如 overworld.json）
        String fileName = dimension.getPath().replace('/', '_') + ".json";
        this.cacheFile = cacheDir.resolve(fileName);
        load();
    }

    /**
     * 记录一个方块到缓存。
     */
    public void recordBlock(int x, int y, int z, String blockId) {
        String key = x + "," + y + "," + z;
        String prev = blocks.put(key, blockId);
        if (prev == null || !prev.equals(blockId)) {
            dirty = true;
            newBlocksSinceSave++;
        }
    }

    /**
     * 从缓存读取方块 ID，未缓存则返回 null。
     */
    public String getBlock(int x, int y, int z) {
        return blocks.get(x + "," + y + "," + z);
    }

    /**
     * 获取缓存中的方块总数。
     */
    public int getBlockCount() {
        return blocks.size();
    }

    /**
     * 检查是否应保存（达到阈值或时间间隔）。
     */
    public boolean shouldSave() {
        if (!dirty) return false;
        if (newBlocksSinceSave >= SAVE_THRESHOLD_BLOCKS) return true;
        return (System.currentTimeMillis() - lastSaveTime) > SAVE_INTERVAL_MS;
    }

    /**
     * 如果需要，在后台线程中保存缓存。
     */
    public void saveIfNeededAsync() {
        if (shouldSave()) {
            Thread thread = new Thread(this::save, "agentcontrol-cache-save");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * 立即保存缓存到磁盘。
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());
            try (PrintWriter writer = new PrintWriter(new FileWriter(cacheFile.toFile()))) {
                writer.println("{");
                writer.println("  \"version\": 1,");
                writer.println("  \"blockCount\": " + blocks.size() + ",");
                writer.println("  \"blocks\": {");
                boolean first = true;
                for (Map.Entry<String, String> entry : blocks.entrySet()) {
                    if (!first) writer.println(",");
                    writer.print("    \"" + escapeJson(entry.getKey()) + "\":\"" + escapeJson(entry.getValue()) + "\"");
                    first = false;
                }
                writer.println();
                writer.println("  }");
                writer.println("}");
            }
            dirty = false;
            newBlocksSinceSave = 0;
            lastSaveTime = System.currentTimeMillis();
        } catch (IOException e) {
            System.err.println("[AgentControl] Failed to save cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从磁盘加载缓存。
     */
    private void load() {
        File file = cacheFile.toFile();
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean inBlocks = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("\"blocks\":")) {
                    inBlocks = true;
                    continue;
                }
                if (inBlocks) {
                    if (line.equals("}")) break; // end of blocks object
                    // Parse line like "    "x,y,z":"block_id"" or "    "x,y,z":"block_id","
                    if (line.endsWith(",")) line = line.substring(0, line.length() - 1);
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = unescapeJson(line.substring(0, colonIdx).trim());
                        String value = unescapeJson(line.substring(colonIdx + 1).trim());
                        // Remove surrounding quotes
                        key = key.replace("\"", "");
                        value = value.replace("\"", "");
                        if (!key.isEmpty()) {
                            blocks.put(key, value);
                        }
                    }
                }
            }
            lastSaveTime = System.currentTimeMillis();
            System.out.println("[AgentControl] Loaded " + blocks.size() + " blocks from cache: " + cacheFile.getFileName());
        } catch (IOException e) {
            System.err.println("[AgentControl] Failed to load cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
