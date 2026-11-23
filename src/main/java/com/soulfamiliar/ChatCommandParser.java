package com.soulfamiliar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatCommandParser {
    private static final Pattern CHESS_MOVE_PATTERN = Pattern.compile("\b([a-hA-H][1-8])\s*(?:на|в|->|-|бь[её]т|рубит|x|х)\s*([a-hA-H][1-8])\b");

    private ChatCommandParser() {}

    public static void handleMessage(String rawMessage, boolean isSilentCommand) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        String msg = rawMessage.trim();
        if (msg.isEmpty()) return;
        String lower = msg.toLowerCase(Locale.ROOT);

        // 1. Rituals: "Восстань [Имя]", "Вселись [Имя]", "Вернись [Имя]", "Упокойся [Имя]"
        if (lower.startsWith("восстань ")) {
            String name = extractName(msg.substring("восстань ".length()));
            FamiliarManager.getInstance().summonFamiliar(client, name, !isSilentCommand);
            return;
        }
        if (lower.startsWith("вселись ")) {
            String name = extractName(msg.substring("вселись ".length()));
            FamiliarManager.getInstance().possessTargetStand(client, name);
            return;
        }
        if (lower.equals("вернись") || lower.startsWith("вернись ")) {
            if (lower.equals("вернись")) {
                FamiliarManager.getInstance().stopCameraPossession(client);
                return;
            }
            String name = extractName(msg.substring("вернись ".length()));
            FamiliarManager.getInstance().releaseFamiliar(client, name);
            return;
        }
        if (lower.startsWith("упокойся ")) {
            String name = extractName(msg.substring("упокойся ".length()));
            FamiliarManager.getInstance().dismissFamiliar(client, name);
            return;
        }

        // 2. Chess board calibration & reset
        if (lower.equals("доска a1") || lower.equals("доска а1")) {
            FamiliarManager.getInstance().getChessBoard().setCornerA1(client);
            return;
        }
        if (lower.equals("доска h8") || lower.equals("доска н8")) {
            FamiliarManager.getInstance().getChessBoard().setCornerH8(client);
            return;
        }
        if (lower.contains("сброс партии") || lower.contains("сбрось партию")) {
            FamiliarManager.getInstance().getChessBoard().resetBoard(client);
            return;
        }

        // 3. Determine addressed familiars (specific name or "все" / single familiar)
        List<Familiar> targets = resolveAddressedFamiliars(lower);
        if (targets.isEmpty()) return;

        // 4. Chess side assignment & moves
        if (lower.contains("твои белые") || lower.contains("за белых")) {
            Familiar commander = targets.get(0);
            FamiliarManager.getInstance().getChessBoard().assignWhiteCommander(client, commander.getName());
            return;
        }
        if (lower.contains("твои чёрные") || lower.contains("твои черные") || lower.contains("за чёрных") || lower.contains("за черных")) {
            Familiar commander = targets.get(0);
            FamiliarManager.getInstance().getChessBoard().assignBlackCommander(client, commander.getName());
            return;
        }
        if (lower.contains("рокировк")) {
            Familiar commander = targets.get(0);
            boolean queenSide = lower.contains("длинн");
            FamiliarManager.getInstance().getChessBoard().castle(client, commander, queenSide);
            return;
        }

        Matcher chessMatcher = CHESS_MOVE_PATTERN.matcher(msg);
        if (chessMatcher.find()) {
            Familiar commander = targets.get(0);
            String fromSq = chessMatcher.group(1).toUpperCase(Locale.ROOT);
            String toSq = chessMatcher.group(2).toUpperCase(Locale.ROOT);
            boolean isCapture = lower.contains("бьёт") || lower.contains("бьет") || lower.contains("рубит");
            FamiliarManager.getInstance().getChessBoard().orderMove(client, commander, fromSq, toSq, isCapture);
            return;
        }

        // 5. Familiar behavior commands
        if (lower.contains("глаза") || lower.contains("связь")) {
            FamiliarManager.getInstance().startCameraPossession(client, targets.get(0).getName());
            return;
        }

        if (lower.contains("напуг")) {
            Player victim = findMentionedPlayer(client, msg, targets);
            if (victim == null) {
                victim = FamiliarManager.raycastPlayer(client, 12.0);
            }
            if (victim == null) {
                FamiliarManager.notifyPlayer(client, "Укажи ник игрока или посмотри на цель перед командой «напугай».", ChatFormatting.YELLOW);
                return;
            }
            for (Familiar f : targets) {
                f.triggerScare(victim, f.resolveBoundStand(client.level));
            }
            return;
        }

        if (lower.contains("поклон") || lower.contains("приветств")) {
            Player targetPlayer = FamiliarManager.raycastPlayer(client, 10.0);
            Vec3 lookPos = targetPlayer != null ? targetPlayer.position() : client.player.position();
            for (Familiar f : targets) {
                f.triggerBow(lookPos);
            }
            return;
        }

        if (lower.contains("укаж") || lower.contains("покаж")) {
            Vec3 targetPos = FamiliarManager.raycastLookPoint(client, 16.0);
            for (Familiar f : targets) {
                f.triggerPoint(targetPos);
            }
            return;
        }

        if (lower.contains("сторож") || lower.contains("охран")) {
            for (Familiar f : targets) {
                f.setMode(FamiliarMode.GUARD);
            }
            return;
        }

        if (lower.contains("ко мне") || lower.contains("за мной")) {
            for (Familiar f : targets) {
                f.setMode(FamiliarMode.FOLLOW);
            }
            return;
        }

        if (lower.contains("замр") || lower.contains("стату")) {
            for (Familiar f : targets) {
                f.setMode(FamiliarMode.FROZEN);
            }
            return;
        }

        if (lower.contains("вольн") || lower.contains("отдых")) {
            for (Familiar f : targets) {
                f.setMode(FamiliarMode.IDLE);
            }
        }
    }

    private static List<Familiar> resolveAddressedFamiliars(String lower) {
        List<Familiar> matched = new ArrayList<>();
        for (Familiar f : FamiliarManager.getInstance().getAll()) {
            String fn = f.getName().toLowerCase(Locale.ROOT);
            if (lower.contains(fn)) {
                matched.add(f);
            }
        }
        if (!matched.isEmpty()) return matched;

        if (lower.startsWith("все") || lower.contains(" все,") || lower.contains(" все ")) {
            matched.addAll(FamiliarManager.getInstance().getAll());
            return matched;
        }

        // If user has only 1 familiar, allow omitting its name
        if (FamiliarManager.getInstance().getAll().size() == 1) {
            matched.addAll(FamiliarManager.getInstance().getAll());
        }
        return matched;
    }

    private static Player findMentionedPlayer(Minecraft client, String msg, List<Familiar> excludedFamiliars) {
        if (client.level == null) return null;
        String lower = msg.toLowerCase(Locale.ROOT);
        for (Player p : client.level.players()) {
            if (p == client.player) continue;
            String nick = p.getName().getString();
            if (lower.contains(nick.toLowerCase(Locale.ROOT))) {
                return p;
            }
        }
        return null;
    }

    private static String extractName(String tail) {
        return tail.replaceAll("[^\p{L}\p{N}_-]", " ").trim().split("\s+")[0];
    }
}
