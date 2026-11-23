package com.soulfamiliar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChessBoardManager {
    private Vec3 a1Center = null;
    private Vec3 h8Center = null;
    private boolean calibrated = false;

    private String whiteCommanderName = null;
    private String blackCommanderName = null;

    private final Map<String, UUID> squareToStand = new HashMap<>();
    private final Map<String, UUID> initialSetup = new HashMap<>();
    private final Map<UUID, Vec3> initialPositions = new HashMap<>();
    private int capturedWhiteCount = 0;
    private int capturedBlackCount = 0;

    private UUID movingStandUuid = null;
    private Vec3 moveDestination = null;
    private float moveYaw = 0.0f;
    private int moveTicksLeft = 0;

    private UUID fallingStandUuid = null;
    private Vec3 graveyardDestination = null;
    private int fallTicksLeft = 0;

    private String activeCommanderName = null;

    public void setCornerA1(Minecraft client) {
        Vec3 blockTop = raycastBlockTop(client);
        if (blockTop == null) {
            FamiliarManager.notifyPlayer(client, "Посмотри на блок угла доски A1.", ChatFormatting.YELLOW);
            return;
        }
        this.a1Center = blockTop;
        FamiliarManager.notifyPlayer(client, "Угол доски A1 зафиксирован. Теперь посмотри на H8 и напиши «Доска H8».", ChatFormatting.AQUA);
        if (h8Center != null) {
            scanBoard(client);
        }
    }

    public void setCornerH8(Minecraft client) {
        Vec3 blockTop = raycastBlockTop(client);
        if (blockTop == null) {
            FamiliarManager.notifyPlayer(client, "Посмотри на блок противоположного угла H8.", ChatFormatting.YELLOW);
            return;
        }
        this.h8Center = blockTop;
        if (a1Center == null) {
            FamiliarManager.notifyPlayer(client, "Угол H8 зафиксирован. Теперь посмотри на A1 и напиши «Доска A1».", ChatFormatting.AQUA);
            return;
        }
        scanBoard(client);
    }

    private void scanBoard(Minecraft client) {
        if (client.level == null || a1Center == null || h8Center == null) return;
        squareToStand.clear();
        initialSetup.clear();
        initialPositions.clear();
        capturedWhiteCount = 0;
        capturedBlackCount = 0;

        int found = 0;
        for (int file = 0; file < 8; file++) {
            for (int rank = 0; rank < 8; rank++) {
                String sq = toSquareName(file, rank);
                Vec3 center = getSquareCenter(file, rank);
                ArmorStand nearest = findStandNear(client.level, center, 0.55);
                if (nearest != null) {
                    squareToStand.put(sq, nearest.getUUID());
                    initialSetup.put(sq, nearest.getUUID());
                    initialPositions.put(nearest.getUUID(), nearest.position());
                    found++;
                }
            }
        }
        calibrated = true;
        FamiliarManager.notifyPlayer(client, "Шахматная доска откалибрована! Обнаружено фигур-стоек: " + found + ".", ChatFormatting.GREEN);
    }

    public void assignWhiteCommander(Minecraft client, String familiarName) {
        this.whiteCommanderName = familiarName;
        FamiliarManager.notifyPlayer(client, "«" + familiarName + "» назначен полководцем белых.", ChatFormatting.WHITE);
    }

    public void assignBlackCommander(Minecraft client, String familiarName) {
        this.blackCommanderName = familiarName;
        FamiliarManager.notifyPlayer(client, "«" + familiarName + "» назначен полководцем чёрных.", ChatFormatting.DARK_GRAY);
    }

    public void orderMove(Minecraft client, Familiar commander, String fromSq, String toSq, boolean forceCapture) {
        if (!calibrated || client.level == null) {
            FamiliarManager.notifyPlayer(client, "Сначала разметь доску командами «Доска A1» и «Доска H8».", ChatFormatting.YELLOW);
            return;
        }
        UUID pieceUuid = squareToStand.get(fromSq);
        if (pieceUuid == null) {
            FamiliarManager.notifyPlayer(client, "На клетке " + fromSq + " нет стойки.", ChatFormatting.RED);
            return;
        }
        ArmorStand piece = findStandByUuid(client.level, pieceUuid);
        if (piece == null) return;

        int[] toCoords = parseSquare(toSq);
        if (toCoords == null) return;
        Vec3 dest = getSquareCenter(toCoords[0], toCoords[1]);

        UUID victimUuid = squareToStand.get(toSq);
        if (victimUuid != null && !victimUuid.equals(pieceUuid)) {
            ArmorStand victim = findStandByUuid(client.level, victimUuid);
            if (victim != null) {
                boolean victimIsWhite = isOriginallyWhite(victimUuid);
                Vec3 sideDir = h8Center.subtract(a1Center);
                Vec3 perp = new Vec3(-sideDir.z, 0, sideDir.x).normalize();
                int slot = victimIsWhite ? capturedWhiteCount++ : capturedBlackCount++;
                Vec3 baseGrave = victimIsWhite ? a1Center.add(perp.scale(-1.6)) : h8Center.add(perp.scale(1.6));
                Vec3 rankDir = new Vec3(h8Center.x - a1Center.x, 0, 0).normalize();
                this.fallingStandUuid = victimUuid;
                this.graveyardDestination = baseGrave.add(rankDir.scale(slot * 0.45));
                this.fallTicksLeft = 14;
            }
        }

        squareToStand.remove(fromSq);
        squareToStand.put(toSq, pieceUuid);

        this.movingStandUuid = pieceUuid;
        this.moveDestination = dest;
        this.moveYaw = commander != null && commander.getName().equalsIgnoreCase(blackCommanderName) ? 180.0f : 0.0f;
        this.moveTicksLeft = 14;
        this.activeCommanderName = commander != null ? commander.getName() : null;

        if (commander != null && !commander.isBound()) {
            commander.setSkullOverridePos(piece.position().add(0, 2.3, 0));
        }
    }

    public void castle(Minecraft client, Familiar commander, boolean queenSide) {
        boolean isBlack = commander != null && commander.getName().equalsIgnoreCase(blackCommanderName);
        String rank = isBlack ? "8" : "1";
        if (queenSide) {
            orderMove(client, commander, "E" + rank, "C" + rank, false);
            UUID rookUuid = squareToStand.remove("A" + rank);
            if (rookUuid != null && client.level != null) {
                squareToStand.put("D" + rank, rookUuid);
                ArmorStand rook = findStandByUuid(client.level, rookUuid);
                int[] dCoords = parseSquare("D" + rank);
                if (rook != null && dCoords != null) {
                    Vec3 dest = getSquareCenter(dCoords[0], dCoords[1]);
                    PoserBridge.sendMoveOnly(rook, dest.subtract(rook.position()), null);
                }
            }
        } else {
            orderMove(client, commander, "E" + rank, "G" + rank, false);
            UUID rookUuid = squareToStand.remove("H" + rank);
            if (rookUuid != null && client.level != null) {
                squareToStand.put("F" + rank, rookUuid);
                ArmorStand rook = findStandByUuid(client.level, rookUuid);
                int[] fCoords = parseSquare("F" + rank);
                if (rook != null && fCoords != null) {
                    Vec3 dest = getSquareCenter(fCoords[0], fCoords[1]);
                    PoserBridge.sendMoveOnly(rook, dest.subtract(rook.position()), null);
                }
            }
        }
    }

    public void resetBoard(Minecraft client) {
        if (!calibrated || client.level == null) return;
        squareToStand.clear();
        squareToStand.putAll(initialSetup);
        capturedWhiteCount = 0;
        capturedBlackCount = 0;

        for (Map.Entry<UUID, Vec3> entry : initialPositions.entrySet()) {
            ArmorStand stand = findStandByUuid(client.level, entry.getKey());
            if (stand != null) {
                Vec3 delta = entry.getValue().subtract(stand.position());
                PoserBridge.sendUpdate(stand, PoseState.idle(stand.getYRot(), 0), delta, true);
            }
        }
        FamiliarManager.notifyPlayer(client, "Фигуры возвращены на исходные позиции.", ChatFormatting.AQUA);
    }

    public boolean tick(Minecraft client, long tickCount) {
        if (client.level == null) return false;
        boolean sentPacket = false;

        if (fallingStandUuid != null && fallTicksLeft > 0 && tickCount % 2 == 0) {
            ArmorStand victim = findStandByUuid(client.level, fallingStandUuid);
            if (victim != null && graveyardDestination != null) {
                Vec3 delta = graveyardDestination.subtract(victim.position()).scale(0.35);
                PoserBridge.sendUpdate(victim, PoseState.fallen(victim.getYRot()), delta, true);
                sentPacket = true;
            }
            fallTicksLeft -= 2;
            if (fallTicksLeft <= 0) {
                fallingStandUuid = null;
            }
        }

        if (!sentPacket && movingStandUuid != null && moveTicksLeft > 0 && tickCount % 2 == 0) {
            ArmorStand piece = findStandByUuid(client.level, movingStandUuid);
            if (piece != null && moveDestination != null) {
                Vec3 diff = moveDestination.subtract(piece.position());
                Vec3 step = moveTicksLeft <= 2 ? diff : diff.scale(0.42);
                PoserBridge.sendMoveOnly(piece, step, moveYaw);
                sentPacket = true;

                if (activeCommanderName != null) {
                    Familiar cmd = FamiliarManager.getInstance().getByName(activeCommanderName);
                    if (cmd != null && !cmd.isBound()) {
                        cmd.setSkullOverridePos(piece.position().add(0, 2.3, 0));
                    }
                }
            }
            moveTicksLeft -= 2;
            if (moveTicksLeft <= 0) {
                if (activeCommanderName != null) {
                    Familiar cmd = FamiliarManager.getInstance().getByName(activeCommanderName);
                    if (cmd != null) cmd.setSkullOverridePos(null);
                }
                movingStandUuid = null;
                activeCommanderName = null;
            }
        }

        return sentPacket;
    }

    private boolean isOriginallyWhite(UUID standUuid) {
        for (Map.Entry<String, UUID> entry : initialSetup.entrySet()) {
            if (entry.getValue().equals(standUuid)) {
                char rankChar = entry.getKey().charAt(1);
                return rankChar == '1' || rankChar == '2';
            }
        }
        return true;
    }

    private Vec3 getSquareCenter(int file, int rank) {
        double fx = file / 7.0;
        double fz = rank / 7.0;
        double x = a1Center.x + (h8Center.x - a1Center.x) * fx;
        double y = Math.max(a1Center.y, h8Center.y);
        double z = a1Center.z + (h8Center.z - a1Center.z) * fz;
        return new Vec3(x, y, z);
    }

    private static String toSquareName(int file, int rank) {
        char f = (char) ('A' + file);
        char r = (char) ('1' + rank);
        return "" + f + r;
    }

    private static int[] parseSquare(String sq) {
        if (sq == null || sq.length() != 2) return null;
        int file = sq.charAt(0) - 'A';
        int rank = sq.charAt(1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return null;
        return new int[]{file, rank};
    }

    private static ArmorStand findStandNear(ClientLevel level, Vec3 pos, double radius) {
        ArmorStand best = null;
        double bestDist = radius * radius;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof ArmorStand stand && stand.isAlive()) {
                double d = stand.position().distanceToSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = stand;
                }
            }
        }
        return best;
    }

    private static ArmorStand findStandByUuid(ClientLevel level, UUID uuid) {
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof ArmorStand stand && stand.getUUID().equals(uuid)) {
                return stand;
            }
        }
        return null;
    }

    private static Vec3 raycastBlockTop(Minecraft client) {
        if (client.player == null || client.level == null) return null;
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getLookAngle().scale(12.0));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
        ));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }
}
