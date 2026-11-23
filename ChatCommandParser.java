package com.soulfamiliar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
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
        long currentTick = FamiliarDialogueEngine.getInstance().getTickCounter();

        // 0. Silent AI & Groq configuration commands (via `.f`)
        if (isSilentCommand) {
            if (lower.startsWith("ключ ") || lower.startsWith("api ") || lower.startsWith("groq ")) {
                String key = msg.substring(msg.indexOf(' ') + 1).trim();
                if (FamiliarConfig.getInstance().getApiUrl().isBlank()) {
                    FamiliarConfig.getInstance().setApiUrl(FamiliarConfig.DEFAULT_GROQ_URL);
                }
                FamiliarConfig.getInstance().setApiKey(key);
                FamiliarConfig.getInstance().setAiEnabled(true);
                GroqBrain.getInstance().resetFailureWarning();
                FamiliarManager.notifyPlayer(client, "Ключ сохранён (" + FamiliarConfig.getInstance().getMaskedKey() + "). Проверяю связь...", ChatFormatting.GREEN);
                GroqBrain.getInstance().testConnectionAsync(client);
                return;
            }
            if (lower.equals("тест ии") || lower.equals("ии тест") || lower.equals("проверка ии")) {
                GroqBrain.getInstance().resetFailureWarning();
                GroqBrain.getInstance().testConnectionAsync(client);
                return;
            }
            if (lower.startsWith("прокси ") || lower.startsWith("proxy ")) {
                String proxy = msg.substring(msg.indexOf(' ') + 1).trim();
                if (proxy.equalsIgnoreCase("выкл") || proxy.equalsIgnoreCase("off") || proxy.equalsIgnoreCase("нет")) {
                    FamiliarConfig.getInstance().setProxyAddress("");
                    GroqBrain.getInstance().resetFailureWarning();
                    FamiliarManager.notifyPlayer(client, "Локальный прокси отключён.", ChatFormatting.YELLOW);
                } else {
                    FamiliarConfig.getInstance().setProxyAddress(proxy);
                    GroqBrain.getInstance().resetFailureWarning();
                    FamiliarManager.notifyPlayer(client, "Локальный HTTP-прокси установлен: " + proxy + ". Проверяю связь...", ChatFormatting.AQUA);
                    GroqBrain.getInstance().testConnectionAsync(client);
                }
                return;
            }
            if (lower.startsWith("провайдер ")) {
                String p = lower.substring("провайдер ".length()).trim();
                GroqBrain.getInstance().resetFailureWarning();
                if (p.contains("groq")) {
                    FamiliarConfig.getInstance().setApiUrl(FamiliarConfig.DEFAULT_GROQ_URL);
                    FamiliarConfig.getInstance().setPrimaryModel(FamiliarConfig.DEFAULT_PRIMARY_MODEL);
                    FamiliarManager.notifyPlayer(client, "Выбран провайдер Groq.", ChatFormatting.GREEN);
                } else if (p.contains("github")) {
                    FamiliarConfig.getInstance().setApiUrl("https://models.inference.ai.azure.com/chat/completions");
                    FamiliarConfig.getInstance().setPrimaryModel("gpt-4o-mini");
                    FamiliarManager.notifyPlayer(client, "Выбран GitHub Models. Укажи токен командой: .f ключ ghp_...", ChatFormatting.GREEN);
                } else if (p.contains("openrouter")) {
                    FamiliarConfig.getInstance().setApiUrl("https://openrouter.ai/api/v1/chat/completions");
                    FamiliarConfig.getInstance().setPrimaryModel("google/gemma-2-9b-it:free");
                    FamiliarManager.notifyPlayer(client, "Выбран OpenRouter. Укажи ключ командой: .f ключ sk-or-...", ChatFormatting.GREEN);
                } else if (p.contains("ollama")) {
                    FamiliarConfig.getInstance().setApiUrl("http://localhost:11434/v1/chat/completions");
                    FamiliarConfig.getInstance().setPrimaryModel("llama3.2");
                    FamiliarConfig.getInstance().setAiEnabled(true);
                    FamiliarManager.notifyPlayer(client, "Выбрана локальная Ollama (localhost:11434).", ChatFormatting.GREEN);
                }
                return;
            }
            if (lower.startsWith("модель ")) {
                String model = msg.substring("модель ".length()).trim();
                FamiliarConfig.getInstance().setPrimaryModel(model);
                GroqBrain.getInstance().resetFailureWarning();
                FamiliarManager.notifyPlayer(client, "Основная модель ИИ изменена на: " + model, ChatFormatting.AQUA);
                return;
            }
            if (lower.startsWith("адрес ") || lower.startsWith("url ")) {
                String url = msg.substring(msg.indexOf(' ') + 1).trim();
                FamiliarConfig.getInstance().setApiUrl(url);
                GroqBrain.getInstance().resetFailureWarning();
                FamiliarManager.notifyPlayer(client, "Адрес провайдера обновлён: " + url, ChatFormatting.AQUA);
                return;
            }
            if (lower.startsWith("резерв ")) {
                String[] parts = msg.substring("резерв ".length()).trim().split("\\s+");
                if (parts.length >= 1) {
                    String bUrl = parts[0];
                    String bKey = parts.length >= 2 ? parts[1] : "none";
                    String bModel = parts.length >= 3 ? parts[2] : "llama3.2";
                    FamiliarConfig.getInstance().setBackupProvider(bUrl, bKey, bModel);
                    GroqBrain.getInstance().resetFailureWarning();
                    FamiliarManager.notifyPlayer(client, "Резервный провайдер сохранён: " + bUrl + " (модель: " + bModel + ")", ChatFormatting.GREEN);
                }
                return;
            }
            if (lower.equals("ии вкл") || lower.equals("нейросеть вкл")) {
                FamiliarConfig.getInstance().setAiEnabled(true);
                GroqBrain.getInstance().resetFailureWarning();
                FamiliarManager.notifyPlayer(client, "Режим нейросети включён.", ChatFormatting.GREEN);
                return;
            }
            if (lower.equals("ии выкл") || lower.equals("нейросеть выкл")) {
                FamiliarConfig.getInstance().setAiEnabled(false);
                FamiliarManager.notifyPlayer(client, "Режим нейросети отключён (используются встроенные фразы).", ChatFormatting.YELLOW);
                return;
            }
            if (lower.equals("статус ии") || lower.equals("ии статус") || lower.equals("groq статус")) {
                FamiliarConfig cfg = FamiliarConfig.getInstance();
                String proxyInfo = cfg.getProxyAddress().isBlank() ? "нет" : cfg.getProxyAddress();
                FamiliarManager.notifyPlayer(client,
                        "ИИ: " + (cfg.hasActiveAi() ? "АКТИВЕН" : "ВЫКЛЮЧЕН")
                                + " | Ключ: " + cfg.getMaskedKey()
                                + " | Модель: " + GroqBrain.getInstance().getLastWorkingModel()
                                + " | Прокси: " + proxyInfo
                                + " | Статус: " + GroqBrain.getInstance().getLastStatusInfo(),
                        ChatFormatting.AQUA);
                return;
            }
        }

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

        List<Familiar> targets = resolveAddressedFamiliars(lower);
        if (targets.isEmpty()) {
            if (isSilentCommand && !FamiliarManager.getInstance().getAll().isEmpty()) {
                List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
                Familiar speaker = all.get(ThreadLocalRandom.current().nextInt(all.size()));
                FamiliarDialogueEngine.getInstance().handleDirectConversationWithMaster(client, speaker, msg, true);
            }
            return;
        }

        if (lower.contains("молчи") || lower.contains("тихо") || lower.contains("заткнись")) {
            FamiliarDialogueEngine.getInstance().setChatterEnabled(false);
            targets.get(0).speakToOwner(client, "Повинуюсь, " + FamiliarDialogueEngine.randomHonorific() + ". Храню безмолвие, но в случае опасности немедленно предупрежу вас.");
            return;
        }
        if (lower.contains("болтай") || lower.contains("говори") || lower.contains("разговаривай")) {
            FamiliarDialogueEngine.getInstance().setChatterEnabled(true);
            targets.get(0).speakToOwner(client, "Благодарю за право голоса, " + FamiliarDialogueEngine.randomHonorific() + "! Я снова делюсь важными вестями.");
            return;
        }
        if (lower.contains("шутк") || lower.contains("пошути") || lower.contains("скажи что-нибудь")) {
            FamiliarDialogueEngine.getInstance().speakMoodJoke(client, targets.get(0));
            return;
        }
        if (lower.contains("настроен")) {
            if (lower.contains("смени") || lower.contains("поменяй") || lower.contains("другое")) {
                for (Familiar f : targets) f.cycleMood();
                targets.get(0).speakToOwner(client, "Моё состояние переменилось, " + FamiliarDialogueEngine.randomHonorific() + ". Теперь моё настроение — " + targets.get(0).getMood().getRussianLabel() + ".");
            } else {
                targets.get(0).speakToOwner(client, "Сейчас моё настроение — " + targets.get(0).getMood().getRussianLabel() + ", " + FamiliarDialogueEngine.randomHonorific() + ".");
            }
            return;
        }
        if (lower.contains("общая память") || lower.contains("коллективный разум") || lower.contains("что вы знаете") || lower.contains("что помните") || lower.contains("находк")) {
            List<HiveMemory.MemoryEntry> entries = HiveMemory.getInstance().getRecentEntries(currentTick, 5);
            String h = FamiliarDialogueEngine.randomHonorific();
            if (entries.isEmpty()) {
                targets.get(0).speakToOwner(client, "В памяти наших душ пока не запечатлено ни одной тайны, " + h + ". Мы продолжаем неспешный дозор.");
            } else {
                List<String> notes = new ArrayList<>();
                for (HiveMemory.MemoryEntry e : entries) {
                    int dist = (int) Math.round(client.player.getEyePosition().distanceTo(e.pos()));
                    String dir = WorldScout.relativeDirection(client.player, e.pos());
                    notes.add(e.label() + " (" + dist + " бл. " + dir + ", приметил " + e.discoveredBy() + ")");
                }
                targets.get(0).speakToOwner(client, "Вот что сохранили наши тени в едином эфире, " + h + ": " + String.join("; ", notes) + ".");
            }
            return;
        }

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

        if (lower.contains("что я вижу") || lower.contains("что передо мной") || lower.contains("опиши мой взгляд") || lower.contains("куда я смотрю")) {
            targets.get(0).speakPlayerGaze(client);
            return;
        }

        if (lower.contains("что ты видишь") || lower.contains("что видишь") || lower.contains("осмотрись") || lower.contains("что вокруг") || lower.contains("доложи")) {
            targets.get(0).speakSurroundings(client, currentTick);
            return;
        }

        Matcher locateMatcher = LOCATE_PATTERN.matcher(msg);
        if (locateMatcher.find()) {
            String targetQuery = locateMatcher.group(1).trim();
            targets.get(0).locateAndReport(client, targetQuery, currentTick);
            return;
        }

        if (lower.contains("на плечо") || lower.contains("к плечу")) {
            for (Familiar f : targets) {
                f.setRoamEnabled(false);
                f.setMode(FamiliarMode.IDLE);
            }
            targets.get(0).speakToOwner(client, "Возвращаюсь на пост у вашего плеча, " + FamiliarDialogueEngine.randomHonorific() + ".");
            return;
        }

        if (lower.contains("погуляй") || lower.contains("изучай") || lower.contains("исследуй") || lower.contains("свободно")) {
            for (Familiar f : targets) {
                f.setRoamEnabled(true);
                f.setMode(FamiliarMode.IDLE);
            }
            targets.get(0).speakToOwner(client, "Вылетаю на неспешную разведку окрестностей, " + FamiliarDialogueEngine.randomHonorific() + ".");
            return;
        }

        if (lower.contains("глаза") || lower.contains("связь")) {
            FamiliarManager.getInstance().startCameraPossession(client, targets.get(0).getName());
            return;
        }

        Matcher stalkMatcher = STALK_PATTERN.matcher(msg);
        if (stalkMatcher.find()) {
            String tail = stalkMatcher.group(1).trim();
            Entity targetEntity = null;
            if (!tail.isEmpty()) {
                targetEntity = WorldScout.findEntityForStalk(client.level, client.player, tail);
            }
            if (targetEntity == null) {
                targetEntity = FamiliarManager.raycastAnyEntity(client, 22.0);
            }
            if (targetEntity == null) {
                FamiliarManager.notifyPlayer(client, "Укажи имя/тип цели или посмотри на существо перед командой «преследуй».", ChatFormatting.YELLOW);
                return;
            }
            for (Familiar f : targets) {
                f.triggerStalk(targetEntity);
            }
            targets.get(0).speakToOwner(client, "Как прикажете, " + FamiliarDialogueEngine.randomHonorific() + ". «" + targetEntity.getName().getString() + "» теперь под моим незримым надзором.");
            return;
        }

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

        if (lower.contains("танц")) {
            for (Familiar f : targets) f.setMode(FamiliarMode.DANCE);
            targets.get(0).speakToOwner(client, "С радостью исполню танец в вашу честь, " + FamiliarDialogueEngine.randomHonorific() + "!");
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

        if (lower.contains("напуг")) {
            Player victim = findMentionedPlayer(client, msg);
            if (victim == null) {
                victim = FamiliarManager.raycastPlayer(client, 14.0);
            }
            if (victim == null) {
                FamiliarManager.notifyPlayer(client, "Укажи ник игрока или посмотри на цель перед командой «напугай».", ChatFormatting.YELLOW);
                return;
            }
            for (Familiar f : targets) {
                f.triggerScare(victim, f.resolveBoundStand(client.level));
            }
            targets.get(0).speakToOwner(client, "С величайшим удовольствием, " + FamiliarDialogueEngine.randomHonorific() + "! Сейчас «" + victim.getName().getString() + "» испытает леденящий ужас.");
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
            Vec3 targetPos = FamiliarManager.raycastLookPoint(client, 20.0);
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
            return;
        }

        // 12. Natural free-form conversation!
        // Triggers if typed via `.f`, OR if the familiar's name is mentioned, OR if you have 1 active familiar and chatter is enabled.
        boolean directlyNamed = isDirectlyNamedIn(lower, targets);
        boolean singleFamiliarConversation = (FamiliarManager.getInstance().getAll().size() == 1) && FamiliarDialogueEngine.getInstance().isChatterEnabled();
        if (isSilentCommand || directlyNamed || singleFamiliarConversation) {
            FamiliarDialogueEngine.getInstance().handleDirectConversationWithMaster(client, targets.get(0), msg, isSilentCommand);
        }
    }

    private static boolean isDirectlyNamedIn(String lower, List<Familiar> targets) {
        for (Familiar f : targets) {
            if (lower.contains(f.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
