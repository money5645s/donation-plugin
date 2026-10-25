package kr.unofficialdonation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short-lived duplicate guard for events that may be replayed when a live socket reconnects.
 * A platform-specific event ID is preferred; a fallback fingerprint is kept only briefly so
 * two intentional donations with the same amount are still accepted after the replay window.
 */
final class RecentEventCache {
    private final int capacity;
    private final long fallbackWindowMillis;
    private final Map<String, Long> values = new LinkedHashMap<>();

    RecentEventCache(int capacity, long fallbackWindowMillis) {
        this.capacity = Math.max(100, capacity);
        this.fallbackWindowMillis = Math.max(1_000L, fallbackWindowMillis);
    }

    synchronized boolean remember(String key) {
        long now = System.currentTimeMillis();
        values.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (values.containsKey(key)) return false;
        values.put(key, now + fallbackWindowMillis);
        while (values.size() > capacity) {
            Iterator<String> iterator = values.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }
}
