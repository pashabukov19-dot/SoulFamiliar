package com.soulfamiliar;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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

    public record OpenedChestRecord(
            String coordKey,
            BlockPos pos,
            ResourceKey<Level> dimension,
            String containerTitle,
            List<String> itemsSummary,
            long tick
    ) {}

    private final Map<String, MemoryEntry> sharedEntries = new LinkedHashMap<>();
    private final Map<String, OpenedChestRecord> openedChests = new LinkedHashMap<>();
    private Vec3 sharedFocusPos = null;
    private long sharedFocusUntilTick = 0;
    private String lastScoutName = null;

    private HiveMemory() {}

    public static HiveMemory getInstance() {
        return INSTANCE;
    }

    public void clear() {
        sharedEntries.clear();
        openedChests.clear();
        sharedFocusPos = null;
        sharedFocusUntilTick = 0;
        lastScoutName = null;
    }

    public void purgeOld(long currentTick) {
        sharedEntries.entrySet().removeIf(e -> (currentTick - e.getValue().tick()) > 12000);
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

    public void recordOpenedChest(BlockPos pos, ResourceKey<Level> dimension, String title, List<String> itemsSummary, long currentTick) {
        if (pos == null) return;
        String dimId = dimension != null ? dimension.identifier().toString() : "unknown";
        String key = dimId + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        List<String> copyItems = new ArrayList<>(itemsSummary);
        openedChests.put(key, new OpenedChestRecord(
                key,
                pos.immutable(),
                dimension,
                title != null && !title.isBlank() ? title : "Сундук",
                copyItems,
                currentTick
        ));
    }

    public OpenedChestRecord searchOpenedChests(String rawQuery, ResourceKey<Level> currentDim, Vec3 observerPos) {
        if (openedChests.isEmpty()) return null;
        String q = rawQuery != null ? rawQuery.toLowerCase(Locale.ROOT).trim() : "";

        OpenedChestRecord best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (OpenedChestRecord rec : openedChests.values()) {
            if (currentDim != null && rec.dimension() != null && !currentDim.equals(rec.dimension())) {
                continue;
            }
            String joinedItems = String.join(", ", rec.itemsSummary()).toLowerCase(Locale.ROOT);
            String titleLow = rec.containerTitle().toLowerCase(Locale.ROOT);

            boolean matchesQuery = q.isEmpty()
                    || joinedItems.contains(q)
                    || titleLow.contains(q)
                    || (q.contains("сундук") || q.contains("бочк") || q.contains("лут") || q.contains("вещ"));

            if (matchesQuery) {
                Vec3 center = Vec3.atCenterOf(rec.pos());
                double dSq = center.distanceToSqr(observerPos);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = rec;
                }
            }
        }
        return best;
    }

    public List<OpenedChestRecord> getRecentOpenedChests(ResourceKey<Level> currentDim, int maxCount) {
        List<OpenedChestRecord> matching = new ArrayList<>();
        for (OpenedChestRecord rec : openedChests.values()) {
            if (currentDim == null || rec.dimension() == null || currentDim.equals(rec.dimension())) {
                matching.add(rec);
            }
        }
        if (matching.size() <= maxCount) return matching;
        return matching.subList(matching.size() - maxCount, matching.size());
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
        double bestDistSq = 64.0 * 64.0;
        for (MemoryEntry entry : sharedEntries.values()) {
            String lbl = entry.label().toLowerCase(Locale.ROOT);
            boolean match = lbl.contains(q) || q.contains(lbl)
                    || (q.contains("алмаз") && lbl.contains("алмаз"))
                    || (q.contains("сундук") && (lbl.contains("сундук") || lbl.contains("бочк")))
                    || (q.contains("изумруд") && lbl.contains("изумруд"))
                    || (q.contains("незерит") && lbl.contains("облом"))
                    || (q.contains("портал") && lbl.contains("портал"))
                    || (q.contains("крипер") && lbl.contains("крипер"))
                    || (q.contains("дроп") && lbl.contains("предмет"));
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
