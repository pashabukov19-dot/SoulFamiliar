package com.soulfamiliar;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldScout {
    private WorldScout() {}

    public record SearchResult(String label, Vec3 pos, double distance, String direction, String fromMemoryScout) {}

    public record Discovery(String uniqueKey, String label, Vec3 pos, boolean isDanger) {}

    /**
     * Compact situation summary passed to Groq AI so the familiar understands the world around the Master.
     */
    public static String buildCompactAiWorldSummary(ClientLevel level, LocalPlayer owner, Familiar speaker, long currentTick) {
        if (level == null || owner == null) return "Неизвестная местность.";
        List<String> facts = new ArrayList<>();

        String dimName = "Верхний мир";
        if (level.dimension() == Level.NETHER) dimName = "Нижний мир (Незер)";
        else if (level.dimension() == Level.END) dimName = "Край (Энд)";

        BlockPos bp = owner.blockPosition();
        facts.add("Измерение: " + dimName + ", высота Y=" + bp.getY());
        facts.add("Здоровье Повелителя: " + (int) Math.ceil(owner.getHealth()) + "/" + (int) Math.ceil(owner.getMaxHealth()));
        if (level.isThundering()) facts.add("Погода: гроза");
        else if (level.isRaining()) facts.add("Погода: дождь");

        if (!owner.getMainHandItem().isEmpty()) {
            facts.add("В руке Повелителя: " + owner.getMainHandItem().getHoverName().getString());
        }

        // Nearby players
        List<String> nearPlayers = new ArrayList<>();
        for (Player p : level.players()) {
            if (p != owner && p.isAlive() && p.distanceToSqr(owner) <= 900.0) {
                int dist = (int) Math.round(p.distanceTo(owner));
                nearPlayers.add(p.getName().getString() + " (" + dist + " бл.)");
            }
        }
        if (!nearPlayers.isEmpty()) {
            facts.add("Игроки рядом: " + String.join(", ", nearPlayers));
        }

        // Nearest entity
        List<Entity> mobs = level.getEntities(owner, owner.getBoundingBox().inflate(16.0), e ->
                e.isAlive() && e != owner && !(e instanceof Player) && !(e instanceof ArmorStand)
        );
        if (!mobs.isEmpty()) {
            mobs.sort(Comparator.comparingDouble(e -> e.distanceToSqr(owner)));
            Entity nearest = mobs.get(0);
            facts.add("Ближайшее существо: " + nearest.getName().getString() + " (" + (int) Math.round(nearest.distanceTo(owner)) + " бл.)");
        }

        // Recent discoveries in soul memory
        List<HiveMemory.MemoryEntry> recent = HiveMemory.getInstance().getRecentEntries(currentTick, 3);
        if (!recent.isEmpty()) {
            List<String> memNotes = new ArrayList<>();
            for (HiveMemory.MemoryEntry e : recent) {
                int dist = (int) Math.round(owner.getEyePosition().distanceTo(e.pos()));
                memNotes.add(e.label() + " (" + dist + " бл. " + relativeDirection(owner, e.pos()) + ")");
            }
            facts.add("Недавние находки духов: " + String.join(", ", memNotes));
        }

        facts.add("Облик духа " + speaker.getName() + ": " + (speaker.isBound() ? "вселён в стойку для брони" : "парящий череп"));
        return String.join("; ", facts) + ".";
    }

    public static Discovery scanForInitiative(ClientLevel level, LocalPlayer owner, Vec3 origin) {
        if (level == null || owner == null) return null;

        // 1. Immediate threats near owner (within 10 blocks)
        List<Entity> threats = level.getEntities(owner, owner.getBoundingBox().inflate(10.0), e ->
                e.isAlive() && e != owner && !(e instanceof ArmorStand) && (e instanceof Enemy || e instanceof Player)
        );
        for (Entity e : threats) {
            if (e instanceof Creeper) {
                return new Discovery("entity:" + e.getUUID(), "крипер", e.getEyePosition(), true);
            }
            if (e.getType().toString().toLowerCase(Locale.ROOT).contains("enderman")) {
                return new Discovery("entity:" + e.getUUID(), "эндермен", e.getEyePosition(), true);
            }
            if (e instanceof Player p) {
                if (p.isShiftKeyDown() || p.distanceToSqr(owner) < 36.0) {
                    return new Discovery("player:" + p.getUUID(), "игрок " + p.getName().getString(), p.getEyePosition(), p.isShiftKeyDown());
                }
            }
            if (e instanceof Enemy && e.distanceToSqr(owner) < 49.0) {
                return new Discovery("entity:" + e.getUUID(), e.getName().getString(), e.getEyePosition(), true);
            }
        }

        // 2. Scan radius around the roaming familiar (10 blocks) for genuinely high-value loot/ores
        BlockPos center = BlockPos.containing(origin);
        int r = 10;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.offset(dx, dy, dz);
                    String highValue = classifyHighValueDiscovery(level.getBlockState(bp));
                    if (highValue != null) {
                        String sectorKey = highValue + ":" + (bp.getX() >> 2) + ":" + (bp.getY() >> 2) + ":" + (bp.getZ() >> 2);
                        return new Discovery(sectorKey, highValue, Vec3.atCenterOf(bp), false);
                    }
                }
            }
        }

        return null;
    }

    private static String classifyHighValueDiscovery(BlockState state) {
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return "алмазная руда";
        if (state.is(Blocks.ANCIENT_DEBRIS)) return "древние обломки";
        if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return "изумрудная руда";
        if (state.is(Blocks.SPAWNER)) return "спавнер";
        if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME)) return "портал";
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.ENDER_CHEST)) return "сундук";
        if (state.is(Blocks.ENCHANTING_TABLE)) return "стол зачарований";
        if (state.is(Blocks.BEACON)) return "маяк";
        return null;
    }

    public static String describeDetailedFamiliarSight(ClientLevel level, LocalPlayer owner, Familiar familiar, Vec3 eyeOrigin, float yaw, float pitch, long currentTick) {
        if (level == null || owner == null) return "Вокруг лишь пустота, Повелитель.";

        List<String> sections = new ArrayList<>();

        Vec3 lookVec = directionFromYawPitch(yaw, pitch);
        String directSight = inspectRay(level, owner, eyeOrigin, lookVec, 18.0);
        if (directSight != null) {
            sections.add("Прямо перед моим взором — " + directSight);
        }

        List<Player> players = new ArrayList<>();
        for (Player p : level.players()) {
            if (p != owner && p.isAlive() && p.position().distanceToSqr(eyeOrigin) <= 484.0) {
                players.add(p);
            }
        }
        if (!players.isEmpty()) {
            players.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(eyeOrigin)));
            List<String> pDetails = new ArrayList<>();
            for (int i = 0; i < Math.min(3, players.size()); i++) {
                Player p = players.get(i);
                int dist = (int) Math.round(Math.sqrt(p.position().distanceToSqr(owner.position())));
                String dir = relativeDirection(owner, p.position());
                String itemNote = p.getMainHandItem().isEmpty() ? "с пустыми руками" : "держит " + p.getMainHandItem().getHoverName().getString();
                String sneakNote = p.isShiftKeyDown() ? ", крадётся" : "";
                pDetails.add(p.getName().getString() + " (" + dist + " бл. " + dir + ", " + itemNote + sneakNote + ")");
            }
            sections.add("Игроки поблизости: " + String.join(", ", pDetails));
        }

        List<Entity> mobs = level.getEntities(owner, new AABB(eyeOrigin, eyeOrigin).inflate(18.0), e ->
                e.isAlive() && e != owner && !(e instanceof Player) && !(e instanceof ArmorStand)
        );
        if (!mobs.isEmpty()) {
            mobs.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(eyeOrigin)));
            Entity nearest = mobs.get(0);
            int dist = (int) Math.round(Math.sqrt(nearest.position().distanceToSqr(owner.position())));
            String dir = relativeDirection(owner, nearest.position());
            String hpInfo = "";
            if (nearest instanceof LivingEntity le) {
                hpInfo = ", ОЗ: " + (int) Math.ceil(le.getHealth()) + "/" + (int) Math.ceil(le.getMaxHealth());
            }
            if (mobs.size() == 1) {
                sections.add("Существо рядом: " + nearest.getName().getString() + " (" + dist + " бл. " + dir + hpInfo + ")");
            } else {
                sections.add("Существ вокруг: " + mobs.size() + " (ближайшее — " + nearest.getName().getString() + ", " + dist + " бл. " + dir + hpInfo + ")");
            }
        }

        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        BlockPos center = BlockPos.containing(eyeOrigin);
        int r = 13;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(bp);
                    String label = classifyNotableBlock(state);
                    if (label != null) {
                        blockCounts.merge(label, 1, Integer::sum);
                        String high = classifyHighValueDiscovery(state);
                        if (high != null) {
                            String sectorKey = high + ":" + (bp.getX() >> 2) + ":" + (bp.getY() >> 2) + ":" + (bp.getZ() >> 2);
                            HiveMemory.getInstance().recordDiscovery(sectorKey, high, Vec3.atCenterOf(bp), familiar.getName(), currentTick, false);
                        }
                    }
                }
            }
        }

        if (!blockCounts.isEmpty()) {
            List<String> blockNotes = new ArrayList<>();
            int shown = 0;
            for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
                if (shown++ >= 5) break;
                blockNotes.add(entry.getKey() + " (" + entry.getValue() + " шт.)");
            }
            sections.add("Ценные находки в радиусе: " + String.join(", ", blockNotes));
        }

        if (sections.isEmpty()) {
            return "Вокруг тихо и безлюдно — ни ценных залежей, ни живых душ поблизости не видно.";
        }

        return String.join(". ", sections) + ".";
    }

    public static String describePlayerGaze(ClientLevel level, LocalPlayer owner, double range) {
        if (level == null || owner == null) return "Я не могу заглянуть в ваш взор сейчас, Повелитель.";
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle();
        String sight = inspectRay(level, owner, eye, look, range);
        String cardinal = cardinalDirectionFromYaw(owner.getYRot());
        if (sight == null) {
            return "Вы устремили взор на " + cardinal + " в открытое пространство (в пределах " + (int) range + " бл. препятствий нет).";
        }
        return "Ваш взор направлен на " + cardinal + ": перед вами " + sight + ".";
    }

    private static String inspectRay(ClientLevel level, LocalPlayer owner, Vec3 origin, Vec3 dir, double range) {
        Vec3 normDir = dir.normalize();
        Vec3 end = origin.add(normDir.scale(range));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                owner, origin, end,
                new AABB(origin, end).inflate(1.0),
                e -> e != owner && e.isAlive(),
                range * range
        );

        BlockHitResult blockHit = level.clip(new ClipContext(
                origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, owner
        ));

        double entityDistSq = entityHit != null ? origin.distanceToSqr(entityHit.getLocation()) : Double.MAX_VALUE;
        double blockDistSq = blockHit.getType() != HitResult.Type.MISS ? origin.distanceToSqr(blockHit.getLocation()) : Double.MAX_VALUE;

        if (entityHit != null && entityDistSq <= blockDistSq) {
            Entity target = entityHit.getEntity();
            int dist = (int) Math.round(Math.sqrt(entityDistSq));
            StringBuilder sb = new StringBuilder();
            sb.append(target.getName().getString()).append(" (дистанция ").append(dist).append(" бл.");
            if (target instanceof LivingEntity le) {
                sb.append(", здоровье ").append((int) Math.ceil(le.getHealth())).append("/").append((int) Math.ceil(le.getMaxHealth()));
                ItemStack mainHand = le.getMainHandItem();
                if (!mainHand.isEmpty()) {
                    sb.append(", в руке: ").append(mainHand.getHoverName().getString());
                }
                if (le.isOnFire()) sb.append(", объят пламенем");
                if (le.isShiftKeyDown()) sb.append(", крадётся");
            }
            sb.append(")");
            return sb.toString();
        }

        if (blockHit.getType() != HitResult.Type.MISS) {
            BlockPos bp = blockHit.getBlockPos();
            BlockState state = level.getBlockState(bp);
            Block block = state.getBlock();
            int dist = (int) Math.round(Math.sqrt(blockDistSq));
            String blockName = block.getName().getString();
            String notable = classifyNotableBlock(state);
            String extra = notable != null ? " — примечательный объект!" : "";
            return "блок «" + blockName + "» на расстоянии " + dist + " бл. (координаты: " + bp.getX() + ", " + bp.getY() + ", " + bp.getZ() + ")" + extra;
        }

        return null;
    }

    public static SearchResult locateTarget(ClientLevel level, LocalPlayer owner, Vec3 searchOrigin, String rawQuery, long currentTick) {
        if (level == null || owner == null || rawQuery == null) return null;
        String q = rawQuery.toLowerCase(Locale.ROOT).replace("?", "").replace("!", "").trim();
        if (q.isEmpty()) return null;

        HiveMemory.MemoryEntry mem = HiveMemory.getInstance().searchMemory(q, owner.position(), currentTick);
        if (mem != null) {
            double dist = owner.getEyePosition().distanceTo(mem.pos());
            return new SearchResult(mem.label(), mem.pos(), dist, relativeDirection(owner, mem.pos()), mem.discoveredBy());
        }

        Player bestPlayer = null;
        double bestPlayerDist = 45.0 * 45.0;
        for (Player p : level.players()) {
            if (p == owner || !p.isAlive()) continue;
            String nick = p.getName().getString().toLowerCase(Locale.ROOT);
            if (nick.contains(q) || q.contains(nick) || q.contains("игрок")) {
                double d = p.position().distanceToSqr(searchOrigin);
                if (d < bestPlayerDist) {
                    bestPlayerDist = d;
                    bestPlayer = p;
                }
            }
        }
        if (bestPlayer != null) {
            double dist = owner.getEyePosition().distanceTo(bestPlayer.getEyePosition());
            return new SearchResult("игрок " + bestPlayer.getName().getString(), bestPlayer.getEyePosition(), dist, relativeDirection(owner, bestPlayer.position()), null);
        }

        String entityHint = mapRussianEntityHint(q);
        Entity bestEntity = null;
        double bestEntityDist = 36.0 * 36.0;
        for (Entity e : level.getEntities(owner, new AABB(searchOrigin, searchOrigin).inflate(36.0), ent ->
                ent.isAlive() && ent != owner && !(ent instanceof ArmorStand)
        )) {
            String eName = e.getName().getString().toLowerCase(Locale.ROOT);
            String eType = e.getType().toString().toLowerCase(Locale.ROOT);
            boolean matches = eName.contains(q) || eType.contains(q)
                    || (entityHint != null && (eName.contains(entityHint) || eType.contains(entityHint)));
            if (matches) {
                double d = e.position().distanceToSqr(searchOrigin);
                if (d < bestEntityDist) {
                    bestEntityDist = d;
                    bestEntity = e;
                }
            }
        }
        if (bestEntity != null) {
            double dist = owner.getEyePosition().distanceTo(bestEntity.getEyePosition());
            return new SearchResult(bestEntity.getName().getString(), bestEntity.getEyePosition(), dist, relativeDirection(owner, bestEntity.position()), null);
        }

        BlockPos center = BlockPos.containing(searchOrigin);
        BlockPos bestBlockPos = null;
        String bestBlockLabel = null;
        double bestBlockDist = 29.0 * 29.0;

        int r = 28;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir()) continue;
                    String matchedLabel = matchBlockQuery(state, q);
                    if (matchedLabel != null) {
                        double d = bp.distToCenterSqr(searchOrigin.x, searchOrigin.y, searchOrigin.z);
                        if (d < bestBlockDist) {
                            bestBlockDist = d;
                            bestBlockPos = bp;
                            bestBlockLabel = matchedLabel;
                        }
                    }
                }
            }
        }

        if (bestBlockPos != null) {
            Vec3 pos = Vec3.atCenterOf(bestBlockPos);
            double dist = owner.getEyePosition().distanceTo(pos);
            return new SearchResult(bestBlockLabel, pos, dist, relativeDirection(owner, pos), null);
        }

        return null;
    }

    public static Entity findEntityForStalk(ClientLevel level, LocalPlayer owner, String rawQuery) {
        if (level == null || owner == null) return null;
        String q = rawQuery.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return null;

        for (Player p : level.players()) {
            if (p != owner && p.isAlive() && p.getName().getString().toLowerCase(Locale.ROOT).contains(q)) {
                return p;
            }
        }

        String hint = mapRussianEntityHint(q);
        Entity best = null;
        double bestDist = 32.0 * 32.0;
        for (Entity e : level.getEntities(owner, new AABB(owner.position(), owner.position()).inflate(32.0), ent ->
                ent.isAlive() && ent != owner && !(ent instanceof ArmorStand)
        )) {
            String eName = e.getName().getString().toLowerCase(Locale.ROOT);
            String eType = e.getType().toString().toLowerCase(Locale.ROOT);
            if (eName.contains(q) || eType.contains(q) || (hint != null && (eName.contains(hint) || eType.contains(hint)))) {
                double d = e.distanceToSqr(owner);
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        }
        return best;
    }

    public static Vec3 pickInterestingRoamTarget(ClientLevel level, LocalPlayer owner, Vec3 currentPos) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (level == null || owner == null) return currentPos;

        if (rng.nextDouble() < 0.55) {
            List<Entity> nearby = level.getEntities(owner, owner.getBoundingBox().inflate(20.0), e ->
                    e.isAlive() && e != owner && !(e instanceof ArmorStand)
            );
            if (!nearby.isEmpty()) {
                Entity chosen = nearby.get(rng.nextInt(nearby.size()));
                return chosen.getEyePosition().add((rng.nextDouble() - 0.5) * 1.6, 0.85, (rng.nextDouble() - 0.5) * 1.6);
            }

            BlockPos base = owner.blockPosition();
            for (int attempt = 0; attempt < 36; attempt++) {
                BlockPos sample = base.offset(rng.nextInt(-20, 21), rng.nextInt(-6, 9), rng.nextInt(-20, 21));
                if (classifyNotableBlock(level.getBlockState(sample)) != null) {
                    return Vec3.atCenterOf(sample).add(0, 1.2, 0);
                }
            }
        }

        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radius = 3.5 + rng.nextDouble() * 18.0;
            double tx = owner.getX() + Math.cos(angle) * radius;
            double ty = owner.getY() + 0.8 + rng.nextDouble() * 5.0;
            double tz = owner.getZ() + Math.sin(angle) * radius;
            BlockPos bp = BlockPos.containing(tx, ty, tz);
            if (level.getBlockState(bp).isAir()) {
                return new Vec3(tx, ty, tz);
            }
        }

        return owner.position().add(2.0, 2.2, 2.0);
    }

    public static String relativeDirection(LocalPlayer observer, Vec3 target) {
        Vec3 diff = target.subtract(observer.getEyePosition());
        double horizDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        if (horizDist < 1.2) {
            if (diff.y > 1.5) return "прямо над вами";
            if (diff.y < -1.5) return "прямо под вами";
            return "совсем рядом";
        }

        double targetAngle = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0;
        double rel = wrapDegrees(targetAngle - observer.getYRot());

        String horizDir;
        if (rel >= -30 && rel <= 30) horizDir = "спереди";
        else if (rel > 30 && rel < 150) horizDir = "справа";
        else if (rel < -30 && rel > -150) horizDir = "слева";
        else horizDir = "сзади";

        if (diff.y > 3.0) return horizDir + " и выше";
        if (diff.y < -3.0) return horizDir + " и ниже";
        return horizDir;
    }

    private static String cardinalDirectionFromYaw(float yaw) {
        float norm = ((yaw % 360.0f) + 360.0f) % 360.0f;
        if (norm >= 337.5 || norm < 22.5) return "юг";
        if (norm < 67.5) return "юго-запад";
        if (norm < 112.5) return "запад";
        if (norm < 157.5) return "северо-запад";
        if (norm < 202.5) return "север";
        if (norm < 247.5) return "северо-восток";
        if (norm < 292.5) return "восток";
        return "юго-восток";
    }

    private static Vec3 directionFromYawPitch(float yaw, float pitch) {
        float f = -yaw * ((float) Math.PI / 180.0F) - (float) Math.PI;
        float f1 = -pitch * ((float) Math.PI / 180.0F);
        double x = Math.sin(f) * Math.cos(f1);
        double y = Math.sin(f1);
        double z = Math.cos(f) * Math.cos(f1);
        return new Vec3(x, y, z);
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static String classifyNotableBlock(BlockState state) {
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) return "сундук";
        if (state.is(Blocks.BARREL)) return "бочка";
        if (state.is(Blocks.ENDER_CHEST)) return "эндер-сундук";
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return "алмазная руда";
        if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return "изумрудная руда";
        if (state.is(Blocks.ANCIENT_DEBRIS)) return "древние обломки";
        if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE) || state.is(Blocks.NETHER_GOLD_ORE)) return "золотая руда";
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) return "железная руда";
        if (state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)) return "лазурит";
        if (state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)) return "редстоун";
        if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME)) return "портал";
        if (state.is(Blocks.SPAWNER)) return "спавнер";
        if (state.is(Blocks.ENCHANTING_TABLE)) return "стол зачарований";
        if (state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL)) return "наковальня";
        if (state.is(Blocks.BEACON)) return "маяк";
        return null;
    }

    private static String matchBlockQuery(BlockState state, String q) {
        if ((q.contains("алмаз") || q.contains("diamond")) && (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE))) return "алмазная руда";
        if ((q.contains("изумруд") || q.contains("emerald")) && (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE))) return "изумрудная руда";
        if ((q.contains("незерит") || q.contains("облом") || q.contains("debris")) && state.is(Blocks.ANCIENT_DEBRIS)) return "древние обломки";
        if ((q.contains("золот") || q.contains("gold")) && (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE) || state.is(Blocks.NETHER_GOLD_ORE))) return "золотая руда";
        if ((q.contains("желез") || q.contains("iron")) && (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE))) return "железная руда";
        if ((q.contains("мед") || q.contains("copper")) && (state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE))) return "медная руда";
        if ((q.contains("угол") || q.contains("coal")) && (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE))) return "угольная руда";
        if ((q.contains("редстоун") || q.contains("красн") || q.contains("redstone")) && (state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE))) return "редстоуновая руда";
        if ((q.contains("лазурит") || q.contains("lapis")) && (state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE))) return "лазуритовая руда";
        if ((q.contains("кварц") || q.contains("quartz")) && state.is(Blocks.NETHER_QUARTZ_ORE)) return "кварцевая руда";
        if (q.contains("руд") || q.contains("ore")) {
            String notable = classifyNotableBlock(state);
            if (notable != null && (notable.contains("руд") || notable.contains("облом") || notable.contains("лазурит") || notable.contains("редстоун"))) {
                return notable;
            }
        }
        if ((q.contains("сундук") || q.contains("бочк") || q.contains("лут") || q.contains("chest")) && (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.ENDER_CHEST))) return "сундук/бочка";
        if ((q.contains("портал") || q.contains("portal")) && (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME))) return "портал";
        if ((q.contains("спавнер") || q.contains("spawner")) && state.is(Blocks.SPAWNER)) return "спавнер";
        if ((q.contains("чар") || q.contains("enchant")) && state.is(Blocks.ENCHANTING_TABLE)) return "стол зачарований";
        if ((q.contains("наковал") || q.contains("anvil")) && (state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL))) return "наковальня";
        if ((q.contains("верстак") || q.contains("craft")) && state.is(Blocks.CRAFTING_TABLE)) return "верстак";
        if ((q.contains("печ") || q.contains("furnace")) && (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER))) return "печь";
        if ((q.contains("книг") || q.contains("полк") || q.contains("book")) && (state.is(Blocks.BOOKSHELF) || state.is(Blocks.LECTERN))) return "книжная полка";
        if ((q.contains("лав") || q.contains("lava")) && state.is(Blocks.LAVA)) return "лава";
        if ((q.contains("вод") || q.contains("water")) && state.is(Blocks.WATER)) return "вода";
        if ((q.contains("обсидиан") || q.contains("obsidian")) && (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN))) return "обсидиан";
        if ((q.contains("аметист") || q.contains("amethyst")) && (state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.BUDDING_AMETHYST))) return "аметист";
        if ((q.contains("маяк") || q.contains("beacon")) && state.is(Blocks.BEACON)) return "маяк";
        return null;
    }

    private static String mapRussianEntityHint(String q) {
        if (q.contains("зомб")) return "zombie";
        if (q.contains("скелет")) return "skeleton";
        if (q.contains("крипер")) return "creeper";
        if (q.contains("паук")) return "spider";
        if (q.contains("эндер") || q.contains("странник")) return "enderman";
        if (q.contains("ведьм")) return "witch";
        if (q.contains("слиз")) return "slime";
        if (q.contains("фантом")) return "phantom";
        if (q.contains("жител") || q.contains("крестьян")) return "villager";
        if (q.contains("голем")) return "golem";
        if (q.contains("коров")) return "cow";
        if (q.contains("овц") || q.contains("баран")) return "sheep";
        if (q.contains("свин")) return "pig";
        if (q.contains("куриц") || q.contains("кур")) return "chicken";
        if (q.contains("лошад") || q.contains("кон")) return "horse";
        if (q.contains("волк") || q.contains("собак")) return "wolf";
        if (q.contains("кот") || q.contains("кошк")) return "cat";
        if (q.contains("пчел")) return "bee";
        if (q.contains("аксолот")) return "axolotl";
        return null;
    }
}
