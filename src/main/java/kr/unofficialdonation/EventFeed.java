package kr.unofficialdonation;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Persistent, bounded event feed rendered by the built-in dashboard. */
final class EventFeed {
    private final JavaPlugin plugin;
    private final int capacity;
    private final Path historyFile;
    private final List<Entry> entries = new ArrayList<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingWrite;
    private boolean dirty;

    EventFeed(JavaPlugin plugin, int capacity) {
        this.plugin = plugin;
        this.capacity = Math.max(200, capacity);
        this.historyFile = plugin.getDataFolder().toPath().resolve("event-history.json");
        load();
    }

    synchronized void publish(String type, String channelId, Map<String, Object> data) {
        entries.add(0, new Entry(UUID.randomUUID().toString(), Instant.now().toString(), type, channelId, new LinkedHashMap<>(data)));
        while (entries.size() > capacity) entries.remove(entries.size() - 1);
        dirty = true;
        if (pendingWrite == null || pendingWrite.isDone()) pendingWrite = writer.schedule(this::saveIfNeeded, 5, TimeUnit.SECONDS);
    }

    synchronized List<Map<String, Object>> snapshot() { return snapshot(capacity); }

    synchronized List<Map<String, Object>> snapshot(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, capacity));
        return entries.stream().limit(safeLimit).map(Entry::asMap).toList();
    }

    void close() {
        saveIfNeeded();
        writer.shutdownNow();
    }

    private void load() {
        if (Files.notExists(historyFile)) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(historyFile, StandardCharsets.UTF_8)));
            for (Object raw : Json.array(root.get("events"))) {
                Map<String, Object> value = Json.object(raw);
                String id = Json.string(value.get("id"));
                String time = Json.string(value.get("time"));
                String type = Json.string(value.get("type"));
                String channelId = Json.string(value.get("channelId"));
                Map<String, Object> data = Json.object(value.get("data"));
                if (id != null && time != null && type != null && channelId != null && data != null) {
                    entries.add(new Entry(id, time, type, channelId, new LinkedHashMap<>(data)));
                }
                if (entries.size() >= capacity) break;
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("저장된 대시보드 이벤트를 불러오지 못했습니다: " + exception.getMessage());
        }
    }

    private void saveIfNeeded() {
        List<Object> saved;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            saved = entries.stream().map(Entry::asMap).map(value -> (Object) value).toList();
        }
        try {
            Files.createDirectories(historyFile.getParent());
            Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
            Files.writeString(temporary, Json.write(Map.of("events", saved)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            synchronized (this) { dirty = true; }
            plugin.getLogger().warning("대시보드 이벤트를 저장하지 못했습니다: " + exception.getMessage());
        }
    }

    private record Entry(String id, String time, String type, String channelId, Map<String, Object> data) {
        Map<String, Object> asMap() { return Map.of("id", id, "time", time, "type", type, "channelId", channelId, "data", data); }
    }
}
