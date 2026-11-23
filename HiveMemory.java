package com.soulfamiliar;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HiveMemory {
    private static final HiveMemory INSTANCE = new HiveMemory();

    public record MemoryEntry(
            String uniqueKey,
            String label,
            Vec3 pos,
            String discoveredBy,
            long tick,
            boolean isDanger
    ) {}

    private final Map<String, MemoryEntry> sharedEntries = new LinkedHashMap<>();
    private Vec3 sharedFocusPos = null;
    private long sharedFocusUntilTick = 0;
    private String lastScoutName = null;

    private HiveMemory() {}

    public static HiveMemory getInstance() {
        return INSTANCE;
    }

    public void clear() {
        sharedEntries.clear();
        sharedFocusPos = null;
        sharedFocusUntilTick = 0;
        lastScoutName = null;
    }

    public void purgeOld(long currentTick) {
        sharedEntries.entrySet().removeIf(e -> (currentTick - e.getValue().tick()) > 6000);
        if (currentTick > sharedFocusUntilTick) {
            sharedFocusPos = null;
        }
    }

    public boolean recordDiscovery(String uniqueKey, String label, Vec3 pos, String familiarName, long currentTick, boolean isDanger) {
        purgeOld(currentTick);
        MemoryEntry existing = sharedEntries.get(uniqueKey);
        if (existing != null && (currentTick - existing.tick()) < 3600) {
            sharedEntries.put(uniqueKey, new MemoryEntry(uniqueKey, label, pos, existing.discoveredBy(), existing.tick(), isDanger));
            return false;
        }
        sharedEntries.put(uniqueKey, new MemoryEntry(uniqueKey, label, pos, familiarName, currentTick, isDanger));
        this.sharedFocusPos = pos;
        this.sharedFocusUntilTick = currentTick + 120;
        this.lastScoutName = familiarName;
        return true;
    }

    public Vec3 getSharedFocusPos(long currentTick) {
        if (sharedFocusPos != null && currentTick <= sharedFocusUntilTick) {
            return sharedFocusPos;
        }
        return null;
    }

    public String getLastScoutName() {
        return lastScoutName;
    }

    public MemoryEntry searchMemory(String rawQuery, Vec3 observerPos, long currentTick) {
        purgeOld(currentTick);
        if (rawQuery == null || rawQuery.isBlank()) return null;
        String q = rawQuery.toLowerCase(Locale.ROOT).replace("?", "").replace("!", "").trim();

        MemoryEntry best = null;
        double bestDistSq = 48.0 * 48.0;
        for (MemoryEntry entry : sharedEntries.values()) {
            String lbl = entry.label().toLowerCase(Locale.ROOT);
            boolean match = lbl.contains(q) || q.contains(lbl)
                    || (q.contains("алмаз") && lbl.contains("алмаз"))
                    || (q.contains("сундук") && (lbl.contains("сундук") || lbl.contains("бочк")))
                    || (q.contains("изумруд") && lbl.contains("изумруд"))
                    || (q.contains("незерит") && lbl.contains("облом"))
                    || (q.contains("портал") && lbl.contains("портал"))
                    || (q.contains("крипер") && lbl.contains("крипер"));
            if (match) {
                double dSq = entry.pos().distanceToSqr(observerPos);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = entry;
                }
            }
        }
        return best;
    }

    public List<MemoryEntry> getRecentEntries(long currentTick, int maxCount) {
        purgeOld(currentTick);
        List<MemoryEntry> list = new ArrayList<>(sharedEntries.values());
        if (list.size() <= maxCount) return list;
        return list.subList(list.size() - maxCount, list.size());
    }
}
