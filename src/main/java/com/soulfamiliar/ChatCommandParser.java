package com.soulfamiliar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatCommandParser {
    private static final Pattern CHESS_MOVE_PATTERN = Pattern.compile("\\b([a-hA-H][1-8])\\s*(?:на|в|->|-|бь[её]т|рубит|x|х)\\s*([a-hA-H][1-8])\\b");
    private static final Pattern LOCATE_PATTERN = Pattern.compile("(?:где|найди|поищи|отыщи)\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STALK_PATTERN = Pattern.compile("(?:преследуй|следи за|выследи)\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ChatCommandParser() {}

    public static void handleMessage(String rawMessage, boolean isSilentCommand) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        String msg = rawMessage.trim();
        if (msg.isEmpty()) return;
        String lower = msg.toLowerCase(Locale.ROOT);

        // 1. Rituals
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

        // 3. Resolve addressed familiars
        List<Familiar> targets = resolveAddressedFamiliars(lower);
        if (targets.isEmpty()) return;

        // 4. Chatter toggle & jokes
        if (lower.contains("молчи") || lower.contains("тихо") || lower.contains("заткнись")) {
            FamiliarDialogueEngine.getInstance().setChatterEnabled(false);
            targets.get(0).speakToOwner(client, "Молчу-молчу. Буду страдать в тишине.");
            return;
        }
        if (lower.contains("болтай") || lower.contains("говори") || lower.contains("разговаривай")) {
            FamiliarDialogueEngine.getInstance().setChatterEnabled(true);
            targets.get(0).speakToOwner(client, "О, ты разрешил мне комментировать происходящее? Сам напросился.");
            return;
        }
        if (lower.contains("шутк") || lower.contains("пошути") || lower.contains("скажи что-нибудь")) {
            FamiliarDialogueEngine.getInstance().speakImmediateJoke(client, targets.get(0));
            return;
        }

        // 5. Chess commands
        if (lower.contains("твои белые") || lower.contains("за белых")) {
            FamiliarManager.getInstance().getChessBoard().assignWhiteCommander(client, targets.get(0).getName());
            return;
        }
        if (lower.contains("твои чёрные") || lower.contains("твои черные") || lower.contains("за чёрных") || lower.contains("за черных")) {
            FamiliarManager.getInstance().getChessBoard().assignBlackCommander(client, targets.get(0).getName());
            return;
        }
        if (lower.contains("рокировк")) {
            boolean queenSide = lower.contains("длинн");
            FamiliarManager.getInstance().getChessBoard().castle(client, targets.get(0), queenSide);
            return;
        }

        Matcher chessMatcher = CHESS_MOVE_PATTERN.matcher(msg);
        if (chessMatcher.find()) {
            String fromSq = chessMatcher.group(1).toUpperCase(Locale.ROOT);
            String toSq = chessMatcher.group(2).toUpperCase(Locale.ROOT);
            boolean isCapture = lower.contains("бьёт") || lower.contains("бьет") || lower.contains("рубит");
            FamiliarManager.getInstance().getChessBoard().orderMove(client, targets.get(0), fromSq, toSq, isCapture);
            return;
        }

        // 6. Dialogue & Scouting ("что ты видишь", "где ...", "погуляй", "на плечо")
        if (lower.contains("что ты видишь") || lower.contains("что видишь") || lower.contains("осмотрись") || lower.contains("что вокруг") || lower.contains("доложи")) {
            targets.get(0).speakSurroundings(client);
            return;
        }

        Matcher locateMatcher = LOCATE_PATTERN.matcher(msg);
        if (locateMatcher.find()) {
            String targetQuery = locateMatcher.group(1).trim();
            targets.get(0).locateAndReport(client, targetQuery);
            return;
        }

        if (lower.contains("на плечо") || lower.contains("к плечу")) {
            for (Familiar f : targets) {
                f.setRoamEnabled(false);
                f.setMode(FamiliarMode.IDLE);
            }
            targets.get(0).speakToOwner(client, "Возвращаюсь на пост у твоего плеча.");
            return;
        }

        if (lower.contains("погуляй") || lower.contains("изучай") || lower.contains("исследуй") || lower.contains("свободно")) {
            for (Familiar f : targets) {
                f.setRoamEnabled(true);
                f.setMode(FamiliarMode.IDLE);
            }
            targets.get(0).speakToOwner(client, "Полечу осмотрюсь. Постарайся не вляпаться ни во что, пока меня нет.");
            return;
        }

        // 7. Camera possession
        if (lower.contains("глаза") || lower.contains("связь")) {
            FamiliarManager.getInstance().startCameraPossession(client, targets.get(0).getName());
            return;
        }

        // 8. Stalking a player or entity
        Matcher stalkMatcher = STALK_PATTERN.matcher(msg);
        if (stalkMatcher.find()) {
            String tail = stalkMatcher.group(1).trim();
            Entity targetEntity = null;
            if (!tail.isEmpty()) {
                targetEntity = WorldScout.findEntityForStalk(client.level, client.player, tail);
            }
            if (targetEntity == null) {
                targetEntity = FamiliarManager.raycastAnyEntity(client, 18.0);
            }
            if (targetEntity == null) {
                FamiliarManager.notifyPlayer(client, "Укажи имя/тип цели или посмотри на существо перед командой «преследуй».", ChatFormatting.YELLOW);
                return;
            }
            for (Familiar f : targets) {
                f.triggerStalk(targetEntity);
            }
            targets.get(0).speakToOwner(client, "Перехожу в режим тени. «" + targetEntity.getName().getString() + "» даже не поймёт, что за ним следят.");
            return;
        }

        // 9. Group formations & march
        if (lower.contains("в круг") || lower.contains("окружи") || lower.contains("кругом")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.FORMATION_CIRCLE);
            return;
        }
        if (lower.contains("в шеренгу") || lower.contains("в строй") || lower.contains("линия")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.FORMATION_LINE);
            return;
        }
        if (lower.contains("марш")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.MARCH);
            return;
        }

        // 10. Dance & theatrical poses
        if (lower.contains("танц")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.DANCE);
            targets.get(0).speakToOwner(client, "И ради этого я пересекал границу между мирами? Ладно, смотри!");
            return;
        }
        if (lower.contains("колен")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.KNEEL);
            return;
        }
        if (lower.contains("медитац") || lower.contains("лотос")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.MEDITATE);
            return;
        }
        if (lower.contains("аплодис") || lower.contains("хлопай") || lower.contains("браво")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.APPLAUD);
            return;
        }
        if (lower.contains("угроз") || lower.contains("в атаку") || lower.contains("замах")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.THREATEN);
            return;
        }
        if (lower.contains("думай") || lower.contains("раздум") || lower.contains("задумай")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.THINK);
            return;
        }
        if (lower.contains("драм") || lower.contains("отчаян") || lower.contains("ужас")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.DRAMA);
            return;
        }
        if (lower.contains("кивн") || lower.equals("да") || lower.endsWith(", да") || lower.endsWith(" да")) {
            for (Familiar f : targets) f.triggerNod();
            return;
        }
        if (lower.contains("покачай головой") || lower.equals("нет") || lower.endsWith(", нет") || lower.endsWith(" нет")) {
            for (Familiar f : targets) f.triggerShakeHead();
            return;
        }

        // 11. Classic behavior commands
        if (lower.contains("напуг")) {
            Player victim = findMentionedPlayer(client, msg);
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
            targets.get(0).speakToOwner(client, "С удовольствием. Сейчас у «" + victim.getName().getString() + "» прибавится седых волос.");
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
            for (Familiar f : targets) f.setMode(FamiliarMode.GUARD);
            return;
        }

        if (lower.contains("ко мне") || lower.contains("за мной")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.FOLLOW);
            return;
        }

        if (lower.contains("замр") || lower.contains("стату")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.FROZEN);
            return;
        }

        if (lower.contains("вольн") || lower.contains("отдых")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.IDLE);
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

        if (FamiliarManager.getInstance().getAll().size() == 1) {
            matched.addAll(FamiliarManager.getInstance().getAll());
        }
        return matched;
    }

    private static Player findMentionedPlayer(Minecraft client, String msg) {
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
        String cleaned = tail.replaceAll("[^\\p{L}\\p{N}_-]", " ").trim();
        if (cleaned.isEmpty()) return "";
        return cleaned.split("\\s+")[0];
    }
}
