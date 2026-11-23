package com.soulfamiliar;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    public record SearchResult(String label, Vec3 pos, double distance, String direction) {}

    public static String describeSurroundings(ClientLevel level, LocalPlayer owner, Vec3 eyeOrigin) {
        if (level == null || owner == null) return "Вокруг пустота.";

        List<String> parts = new ArrayList<>();

        // 1. Players
        List<Player> players = new ArrayList<>();
        for (Player p : level.players()) {
            if (p != owner && p.isAlive() && p.position().distanceToSqr(eyeOrigin) <= 196.0) {
                players.add(p);
            }
        }
        if (!players.isEmpty()) {
            players.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(eyeOrigin)));
            Player closest = players.get(0);
            int dist = (int) Math.round(Math.sqrt(closest.position().distanceToSqr(eyeOrigin)));
            String dir = relativeDirection(owner, closest.position());
            if (players.size() == 1) {
                parts.add("игрок " + closest.getName().getString() + " (" + dist + " бл., " + dir + ")");
            } else {
                parts.add(players.size() + " игрок(а), ближайший " + closest.getName().getString() + " (" + dist + " бл., " + dir + ")");
            }
        }

        // 2. Mobs / entities
        List<Entity> mobs = level.getEntities(owner, new AABB(eyeOrigin, eyeOrigin).inflate(14.0), e ->
                e.isAlive() && e != owner && !(e instanceof Player) && !(e instanceof ArmorStand)
        );
        if (!mobs.isEmpty()) {
            mobs.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(eyeOrigin)));
            Entity nearestMob = mobs.get(0);
            int dist = (int) Math.round(Math.sqrt(nearestMob.position().distanceToSqr(eyeOrigin)));
            String mobName = nearestMob.getName().getString();
            if (mobs.size() == 1) {
                parts.add(mobName + " (" + dist + " бл.)");
            } else {
                parts.add(mobs.size() + " существ (ближайшее: " + mobName + ", " + dist + " бл.)");
            }
        }

        // 3. Notable blocks within 10 blocks
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        BlockPos center = BlockPos.containing(eyeOrigin);
        int r = 9;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = center.offset(dx, dy, dz);
                    String label = classifyNotableBlock(level.getBlockState(bp));
                    if (label != null) {
                        blockCounts.merge(label, 1, Integer::sum);
                    }
                }
            }
        }

        if (!blockCounts.isEmpty()) {
            List<String> blockNotes = new ArrayList<>();
            int shown = 0;
            for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
                if (shown++ >= 4) break;
                blockNotes.add(entry.getKey() + " ×" + entry.getValue());
            }
            parts.add("из интересного: " + String.join(", ", blockNotes));
        }

        if (parts.isEmpty()) {
            return "Я осмотрелся вокруг — здесь тихо, никого и ничего примечательного поблизости нет.";
        }
        return "Я вижу: " + String.join("; ", parts) + ".";
    }

    public static SearchResult locateTarget(ClientLevel level, LocalPlayer owner, Vec3 searchOrigin, String rawQuery) {
        if (level == null || owner == null || rawQuery == null) return null;
        String q = rawQuery.toLowerCase(Locale.ROOT).replace("?", "").replace("!", "").trim();
        if (q.isEmpty()) return null;

        // 1. Search players first
        Player bestPlayer = null;
        double bestPlayerDist = 32.0 * 32.0;
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
            double dist = Math.sqrt(bestPlayerDist);
            return new SearchResult("игрок " + bestPlayer.getName().getString(), bestPlayer.getEyePosition(), dist, relativeDirection(owner, bestPlayer.position()));
        }

        // 2. Search entities/mobs
        String entityHint = mapRussianEntityHint(q);
        Entity bestEntity = null;
        double bestEntityDist = 26.0 * 26.0;
        for (Entity e : level.getEntities(owner, new AABB(searchOrigin, searchOrigin).inflate(26.0), ent ->
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
            double dist = Math.sqrt(bestEntityDist);
            return new SearchResult(bestEntity.getName().getString(), bestEntity.getEyePosition(), dist, relativeDirection(owner, bestEntity.position()));
        }

        // 3. Search notable blocks within 20 blocks
        BlockPos center = BlockPos.containing(searchOrigin);
        BlockPos bestBlockPos = null;
        String bestBlockLabel = null;
        double bestBlockDist = 21.0 * 21.0;

        int r = 20;
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
            double dist = Math.sqrt(bestBlockDist);
            return new SearchResult(bestBlockLabel, pos, dist, relativeDirection(owner, pos));
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
        double bestDist = 24.0 * 24.0;
        for (Entity e : level.getEntities(owner, new AABB(owner.position(), owner.position()).inflate(24.0), ent ->
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

        // 45% chance to fly over and inspect a nearby entity or interesting block
        if (rng.nextDouble() < 0.45) {
            List<Entity> nearby = level.getEntities(owner, owner.getBoundingBox().inflate(10.0), e ->
                    e.isAlive() && e != owner && !(e instanceof ArmorStand)
            );
            if (!nearby.isEmpty()) {
                Entity chosen = nearby.get(rng.nextInt(nearby.size()));
                return chosen.getEyePosition().add((rng.nextDouble() - 0.5) * 1.4, 0.7, (rng.nextDouble() - 0.5) * 1.4);
            }

            BlockPos base = owner.blockPosition();
            for (int attempt = 0; attempt < 24; attempt++) {
                BlockPos sample = base.offset(rng.nextInt(-9, 10), rng.nextInt(-3, 5), rng.nextInt(-9, 10));
                if (classifyNotableBlock(level.getBlockState(sample)) != null) {
                    return Vec3.atCenterOf(sample).add(0, 1.15, 0);
                }
            }
        }

        // Otherwise pick a random air point within 9 blocks of the owner
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radius = 2.2 + rng.nextDouble() * 7.2;
            double tx = owner.getX() + Math.cos(angle) * radius;
            double ty = owner.getY() + 0.8 + rng.nextDouble() * 3.2;
            double tz = owner.getZ() + Math.sin(angle) * radius;
            BlockPos bp = BlockPos.containing(tx, ty, tz);
            if (level.getBlockState(bp).isAir()) {
                return new Vec3(tx, ty, tz);
            }
        }

        return owner.position().add(1.5, 2.0, 1.5);
    }

    public static String relativeDirection(LocalPlayer observer, Vec3 target) {
        Vec3 diff = target.subtract(observer.getEyePosition());
        double horizDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        if (horizDist < 1.2) {
            if (diff.y > 1.5) return "прямо над тобой";
            if (diff.y < -1.5) return "прямо под тобой";
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
