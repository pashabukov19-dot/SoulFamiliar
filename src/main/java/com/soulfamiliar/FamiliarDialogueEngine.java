package com.soulfamiliar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class FamiliarDialogueEngine {
    private static final FamiliarDialogueEngine INSTANCE = new FamiliarDialogueEngine();

    private static final String[] HONORIFICS = {
            "Повелитель",
            "Господин",
            "мой господин",
            "Владыка"
    };

    private boolean chatterEnabled = true;
    private long tickCounter = 0;
    private long lastChatReactionTick = -800;
    private long lastAmbientTick = -1800;
    private long lastInitiativeTick = -400;
    private String lastAmbientCondition = "";
    private final Deque<String> recentLines = new ArrayDeque<>();
    private final Deque<String> chatHistory = new ArrayDeque<>();

    // Delayed second-familiar echo state
    private String pendingSpeakerName = null;
    private String pendingSpeech = null;
    private int pendingDelayTicks = 0;

    private FamiliarDialogueEngine() {}

    public static FamiliarDialogueEngine getInstance() {
        return INSTANCE;
    }

    public long getTickCounter() {
        return tickCounter;
    }

    public void setChatterEnabled(boolean enabled) {
        this.chatterEnabled = enabled;
    }

    public boolean isChatterEnabled() {
        return chatterEnabled;
    }

    public static String randomHonorific() {
        return HONORIFICS[ThreadLocalRandom.current().nextInt(HONORIFICS.length)];
    }

    public void scheduleDelayedSpeech(String familiarName, String line, int delayTicks) {
        this.pendingSpeakerName = familiarName;
        this.pendingSpeech = line;
        this.pendingDelayTicks = delayTicks;
    }

    private void recordChatLine(String formattedLine) {
        if (formattedLine == null || formattedLine.isBlank()) return;
        String clean = formattedLine.trim();
        if (clean.length() > 180) {
            clean = clean.substring(0, 180) + "...";
        }
        chatHistory.addLast(clean);
        while (chatHistory.size() > 14) {
            chatHistory.removeFirst();
        }
    }

    public String getChatTranscript() {
        if (chatHistory.isEmpty()) return "";
        return String.join("\n", chatHistory);
    }

    private String pickFreshLine(List<String> candidates) {
        if (candidates.isEmpty()) return null;
        List<String> fresh = new ArrayList<>();
        for (String c : candidates) {
            if (!recentLines.contains(c)) {
                fresh.add(c);
            }
        }
        List<String> pool = fresh.isEmpty() ? candidates : fresh;
        String chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        rememberLine(chosen);
        return chosen;
    }

    private void rememberLine(String line) {
        if (line == null) return;
        recentLines.addLast(line);
        while (recentLines.size() > 32) {
            recentLines.removeFirst();
        }
    }

    public void tick(Minecraft client) {
        tickCounter++;
        if (client.level == null || client.player == null) {
            HiveMemory.getInstance().clear();
            pendingSpeech = null;
            lastAmbientCondition = "";
            chatHistory.clear();
            return;
        }

        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        if (all.isEmpty()) return;

        // 1. Delayed echo reply from a second familiar
        if (pendingSpeech != null && --pendingDelayTicks <= 0) {
            Familiar f = FamiliarManager.getInstance().getByName(pendingSpeakerName);
            if (f != null) {
                f.speakToOwner(client, pendingSpeech);
                recordChatLine("[Дух " + f.getName() + "]: " + pendingSpeech);
            }
            pendingSpeech = null;
            pendingSpeakerName = null;
        }

        // 2. Proactive discovery scan every 5 seconds (100 ticks), only reports genuine discoveries
        if (tickCounter % 100 == 0 && (tickCounter - lastInitiativeTick) >= 280) {
            runInitiativeScan(client, all);
        }

        // 3. Situational observations every ~90 seconds — ONLY when there is a real environmental condition!
        if (chatterEnabled && tickCounter % 120 == 0 && (tickCounter - lastAmbientTick) >= 1800) {
            speakSituationalObservation(client, all);
        }
    }

    /**
     * Handles free-form conversation from the Master (either via silent `.f` or direct mention in chat).
     */
    public void handleDirectConversationWithMaster(Minecraft client, Familiar speaker, String masterMessage, boolean isPrivateWhisper) {
        if (client.level == null || client.player == null || speaker == null) return;

        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        recordChatLine("[" + (isPrivateWhisper ? "Шёпот Повелителя" : "Повелитель") + " -> " + speaker.getName() + "]: " + masterMessage);

        String worldCtx = WorldScout.buildCompactAiWorldSummary(client.level, client.player, speaker, tickCounter);
        String transcript = getChatTranscript();
        String task = (isPrivateWhisper
                ? "Повелитель мысленно обращается к тебе (тайно от других игроков): «" + masterMessage + "». "
                : "Повелитель сказал в чате: «" + masterMessage + "». ")
                + "Ответь ему живо, осмысленно и в характере, учитывая обстановку вокруг и контекст недавнего чата.";

        GroqBrain.getInstance().askFamiliarAsync(
                client, speaker, all, worldCtx, transcript, task,
                reply -> {
                    applyGestureTag(speaker, reply.gestureTag());
                    rememberLine(reply.text());
                    speaker.speakToOwner(client, reply.text());
                    recordChatLine("[Дух " + speaker.getName() + "]: " + reply.text());

                    // 30% chance a second familiar adds a brief follow-up comment in character
                    if (all.size() >= 2 && ThreadLocalRandom.current().nextDouble() < 0.30) {
                        Familiar other = pickOtherFamiliar(all, speaker);
                        if (other != null) {
                            triggerSecondFamiliarFollowUp(client, other, speaker, reply.text());
                        }
                    }
                },
                () -> {
                    // Local fallback if offline or API key not set
                    String fallback = generateRespectfulMoodQuip(
                            speaker.getMood(), masterMessage.toLowerCase(Locale.ROOT),
                            true, true, client.player.getName().getString().toLowerCase(Locale.ROOT), true, false
                    );
                    if (fallback != null) {
                        speaker.speakToOwner(client, fallback);
                        recordChatLine("[Дух " + speaker.getName() + "]: " + fallback);
                    }
                }
        );
    }

    private void triggerSecondFamiliarFollowUp(Minecraft client, Familiar second, Familiar first, String firstReply) {
        if (client.level == null || client.player == null) return;
        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        String worldCtx = WorldScout.buildCompactAiWorldSummary(client.level, client.player, second, tickCounter);
        String task = "Твой собрат-дух " + first.getName() + " только что сказал Повелителю: «" + firstReply + "». "
                + "Добавь короткую реплику в своём настроении (" + second.getMood().getRussianLabel() + "), поддержав или мягко подколов собрата, сохраняя почтение к Повелителю.";

        GroqBrain.getInstance().askFamiliarAsync(
                client, second, all, worldCtx, getChatTranscript(), task,
                reply -> scheduleDelayedSpeech(second.getName(), reply.text(), 45),
                () -> {
                    List<String> localEcho = List.of(
                            "Простите " + first.getName() + " за излишнее красноречие, " + randomHonorific() + ", но в его словах есть истина.",
                            "Наши тени незримо согласны с этим, Повелитель."
                    );
                    scheduleDelayedSpeech(second.getName(), pickFreshLine(localEcho), 45);
                }
        );
    }

    private void runInitiativeScan(Minecraft client, List<Familiar> all) {
        ClientLevel level = client.level;
        LocalPlayer owner = client.player;
        if (level == null || owner == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Familiar scout = all.get(rng.nextInt(all.size()));
        if (scout.getMode() == FamiliarMode.FROZEN || scout.getMode() == FamiliarMode.POSSESSED) return;

        Vec3 origin = scout.getCurrentWorldPosition(level, owner);
        WorldScout.Discovery found = WorldScout.scanForInitiative(level, owner, origin);
        if (found == null) return;

        if (!chatterEnabled && !found.isDanger()) return;

        boolean isNewToSpirits = HiveMemory.getInstance().recordDiscovery(
                found.uniqueKey(), found.label(), found.pos(), scout.getName(), tickCounter, found.isDanger()
        );
        if (!isNewToSpirits) return;

        lastInitiativeTick = tickCounter;
        String dir = WorldScout.relativeDirection(owner, found.pos());
        int dist = (int) Math.round(owner.getEyePosition().distanceTo(found.pos()));
        String h = randomHonorific();

        if (found.isDanger()) {
            String line = buildDangerLine(h, found.label(), dist, dir);
            scout.speakToOwner(client, line);
            recordChatLine("[Дух " + scout.getName() + "]: " + line);
            level.playLocalSound(origin.x, origin.y, origin.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 0.65f, false);
            if (scout.isBound()) {
                scout.triggerPoint(found.pos());
            } else {
                scout.focusOnLocation(found.pos(), 140);
            }
        } else {
            String line = buildLootLine(h, found.label(), dist, dir);
            scout.speakToOwner(client, line);
            recordChatLine("[Дух " + scout.getName() + "]: " + line);
            level.playLocalSound(found.pos().x, found.pos().y, found.pos().z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.85f, 1.25f, false);
            for (int i = 0; i < 16; i++) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME,
                        found.pos().x + (rng.nextDouble() - 0.5) * 0.85,
                        found.pos().y + 0.6 + rng.nextDouble() * 0.5,
                        found.pos().z + (rng.nextDouble() - 0.5) * 0.85,
                        0, 0.02, 0);
            }
            if (scout.isBound()) {
                scout.triggerPoint(found.pos());
            } else {
                scout.focusOnLocation(found.pos(), 180);
            }

            if (all.size() >= 2 && rng.nextDouble() < 0.45) {
                Familiar second = pickOtherFamiliar(all, scout);
                if (second != null) {
                    List<String> spiritEchoReplies = List.of(
                            "Эфирный шёпот " + scout.getName() + " донёс до меня ту же весть, " + randomHonorific() + " — я тоже чувствую " + found.label() + ".",
                            "Нить, связывающая наши души, подтверждает находку, Повелитель. Я запомнил дорогу к этому месту.",
                            "Я уловил отголосок взора " + scout.getName() + ", Господин. Достойная добыча."
                    );
                    scheduleDelayedSpeech(second.getName(), pickFreshLine(spiritEchoReplies), 55);
                }
            }
        }
    }

    public void onIncomingChat(String rawText, boolean isPlayerChat) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        if (all.isEmpty()) return;

        String lower = rawText.toLowerCase(Locale.ROOT);
        String ownerNick = client.player.getName().getString().toLowerCase(Locale.ROOT);

        if (rawText.startsWith("[Фамильяр]")) return;
        for (Familiar f : all) {
            if (rawText.startsWith("[" + f.getName() + "]")) return;
        }

        // Always record incoming server chat into the context buffer so familiars understand the full thread!
        recordChatLine(rawText);

        boolean fromOwner = isPlayerChat && (lower.startsWith("<" + ownerNick + ">") || lower.contains(ownerNick + ":") || lower.contains(ownerNick + " »"));
        if (fromOwner && isCommandLike(lower)) return;

        Familiar mentioned = null;
        for (Familiar f : all) {
            if (lower.contains(f.getName().toLowerCase(Locale.ROOT))) {
                mentioned = f;
                break;
            }
        }

        if (!chatterEnabled && mentioned == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Familiar speaker = mentioned != null ? mentioned : all.get(rng.nextInt(all.size()));

        boolean isDeath = !isPlayerChat && (lower.contains("slain") || lower.contains("blew up") || lower.contains("fell")
                || lower.contains("drowned") || lower.contains("burned") || lower.contains("tried to swim in lava")
                || lower.contains("умер") || lower.contains("погиб") || lower.contains("взорвал") || lower.contains("упал") || lower.contains("сгорел"));

        // Decide whether to react to general chat
        boolean isImportantTopic = isDeath || mentioned != null
                || lower.contains("алмаз") || lower.contains("крипер") || lower.contains("помоги")
                || lower.contains("убивают") || lower.contains("торг") || lower.contains("продам")
                || lower.contains("куплю") || lower.contains(ownerNick);

        if (mentioned == null && !isDeath) {
            // Cooldown 22 seconds (440 ticks) for general chat reactions
            if ((tickCounter - lastChatReactionTick) < 440) return;
            // If it's just random casual chatter not mentioning owner or hot topics, react only 30% of the time
            if (!isImportantTopic && rng.nextDouble() > 0.30) return;
        }

        lastChatReactionTick = tickCounter;

        // If Groq AI is configured, generate a true context-aware reply reading the chat history!
        if (FamiliarConfig.getInstance().hasActiveAi()) {
            String worldCtx = WorldScout.buildCompactAiWorldSummary(client.level, client.player, speaker, tickCounter);
            String transcript = getChatTranscript();
            String task;
            if (isDeath) {
                task = "В чате появилось сообщение о смерти: «" + rawText + "». Если погиб сам Повелитель — искренне посочувствуй и пообещай сторожить место. Если погиб другой игрок — язвительно прокомментируй хрупкость смертных для Повелителя.";
            } else if (mentioned != null) {
                task = "В чате упомянули твоё имя («" + rawText + "»). Ответь с учётом контекста переписки и обстановки, обращаясь к Повелителю.";
            } else if (fromOwner) {
                task = "Повелитель написал в общий чат другим игрокам: «" + rawText + "». Дай ему краткий, остроумный или полезный комментарий шёпотом, учитывая, с кем и о чём он переписывается в чате.";
            } else {
                task = "Прочитай историю переписки игроков в чате (последнее сообщение: «" + rawText + "») и дай Повелителю живой, саркастичный или наблюдательный комментарий о том, что происходит между игроками.";
            }

            GroqBrain.getInstance().askFamiliarAsync(
                    client, speaker, all, worldCtx, transcript, task,
                    reply -> {
                        applyGestureTag(speaker, reply.gestureTag());
                        rememberLine(reply.text());
                        speaker.speakToOwner(client, reply.text());
                        recordChatLine("[Дух " + speaker.getName() + "]: " + reply.text());
                    },
                    () -> {
                        String fallback = generateRespectfulMoodQuip(speaker.getMood(), lower, isPlayerChat, fromOwner, ownerNick, mentioned != null, isDeath);
                        if (fallback != null) {
                            speaker.speakToOwner(client, fallback);
                            recordChatLine("[Дух " + speaker.getName() + "]: " + fallback);
                        }
                    }
            );
            return;
        }

        // Offline / No-key fallback
        String quip = generateRespectfulMoodQuip(speaker.getMood(), lower, isPlayerChat, fromOwner, ownerNick, mentioned != null, isDeath);
        if (quip == null) return;

        speaker.speakToOwner(client, quip);
        recordChatLine("[Дух " + speaker.getName() + "]: " + quip);

        if (all.size() >= 2 && rng.nextDouble() < 0.30) {
            Familiar other = pickOtherFamiliar(all, speaker);
            if (other != null) {
                List<String> followUps = List.of(
                        "Простите " + speaker.getName() + " за излишнее красноречие, " + randomHonorific() + ", но в его словах есть истина.",
                        "Наши тени незримо согласны с этим, Повелитель.",
                        "Как верно подметил " + speaker.getName() + ", Господин. Мы всегда начеку."
                );
                scheduleDelayedSpeech(other.getName(), pickFreshLine(followUps), 50);
            }
        }
    }

    private void applyGestureTag(Familiar speaker, String tag) {
        if (tag == null || speaker == null) return;
        switch (tag) {
            case "NOD" -> speaker.triggerNod();
            case "SHAKE" -> speaker.triggerShakeHead();
            case "BOW" -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) speaker.triggerBow(mc.player.position());
            }
            case "THINK" -> speaker.setMode(FamiliarMode.THINK);
            case "DRAMA" -> speaker.setMode(FamiliarMode.DRAMA);
            case "APPLAUD" -> speaker.setMode(FamiliarMode.APPLAUD);
            case "THREATEN" -> speaker.setMode(FamiliarMode.THREATEN);
            case "DANCE" -> speaker.setMode(FamiliarMode.DANCE);
        }
    }

    public void speakMoodJoke(Minecraft client, Familiar speaker) {
        if (FamiliarConfig.getInstance().hasActiveAi() && client.level != null && client.player != null) {
            List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
            String worldCtx = WorldScout.buildCompactAiWorldSummary(client.level, client.player, speaker, tickCounter);
            GroqBrain.getInstance().askFamiliarAsync(
                    client, speaker, all, worldCtx, getChatTranscript(),
                    "Повелитель попросил тебя пошутить или рассказать остроумную мрачную байку. Выдай одну свежую, остроумную шутку в духе тёмного фэнтези, обращаясь к Повелителю.",
                    reply -> {
                        applyGestureTag(speaker, reply.gestureTag() != null ? reply.gestureTag() : "SHAKE");
                        rememberLine(reply.text());
                        speaker.speakToOwner(client, reply.text());
                    },
                    () -> speakLocalMoodJoke(client, speaker)
            );
            return;
        }
        speakLocalMoodJoke(client, speaker);
    }

    private void speakLocalMoodJoke(Minecraft client, Familiar speaker) {
        String h = randomHonorific();
        List<String> pool = switch (speaker.getMood()) {
            case DEVOTED -> List.of(
                    "Я поклялся служить вам вечно, " + h + ", но признаюсь честно: ваш талант находить лаву вслепую поражает даже загробный мир.",
                    "Если бы у меня было сердце, оно бы замирало каждый раз, когда вы прыгаете в каньон без ведра воды, " + h + ".",
                    "Говорят, за каждым великим магом стоит мудрый дух. В нашем случае я ещё и слежу, чтобы вас не съел крипер, " + h + "."
            );
            case PHILOSOPHICAL -> List.of(
                    "Смертные строят замки из булыжника, а вечность превращает их в пыль... Но ваш замок, " + h + ", определённо простоит дольше прочих.",
                    "У меня нет ни плоти, ни костей, " + h + ", зато нет и боли в спине после добычи обсидиана.",
                    "Что такое жизнь, как не бесконечный поиск алмазов на двенадцатой высоте, " + h + "?"
            );
            case MISCHIEVOUS -> List.of(
                    "Позвольте мне напугать кого-нибудь на сервере, " + h + " — обещаю, я сделаю это с безупречными аристократическими манерами!",
                    "Обожаю смотреть, как местные жители торгуют тремя морковками за изумруд. Истинные грабители здесь они, а не нежить, " + h + ".",
                    "Если крипер зашипит у вас за спиной, " + h + ", я обязательно отвешу ему прощальный поклон перед взрывом."
            );
            case MELANCHOLIC -> List.of(
                    "Столько веков я скитался в эфире, " + h + ", чтобы наконец обрести достойного хозяина... и носить за вами дозор в сырых пещерах.",
                    "Вздохнул бы с облегчением, " + h + ", но лёгкие остались в прошлой эпохе.",
                    "Даже самый тусклый факел в ваших руках, " + h + ", теплее холодного пламени бездны."
            );
        };
        speaker.speakToOwner(client, pickFreshLine(pool));
        speaker.triggerShakeHead();
    }

    private void speakSituationalObservation(Minecraft client, List<Familiar> all) {
        LocalPlayer owner = client.player;
        ClientLevel level = client.level;
        if (owner == null || level == null) return;

        Familiar speaker = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        if (speaker.getMode() == FamiliarMode.FROZEN) return;

        String h = randomHonorific();
        String conditionKey = "";
        List<String> pool = new ArrayList<>();

        BlockPos ownerPos = owner.blockPosition();
        if (owner.getHealth() <= owner.getMaxHealth() * 0.45f) {
            conditionKey = "low_hp";
            pool.add("Ваши жизненные силы на исходе, " + h + ". Позвольте настоятельно рекомендовать вам подкрепиться!");
            pool.add("Я чувствую вашу боль через нашу связь, " + h + ". Будьте осторожны, вы нам нужны живым.");
        } else if (level.dimension() == Level.NETHER) {
            conditionKey = "nether";
            pool.add("Жар Нижнего мира обжигает даже эфир, " + h + ", но я не отступлю ни на шаг.");
            pool.add("Будьте бдительны среди этих багровых пустошей, Владыка — пиглины не знают почтения.");
        } else if (level.dimension() == Level.END) {
            conditionKey = "end";
            pool.add("Холодная бездна Края шепчет древние тайны, " + h + ". Не смотрите в глаза местным стражам.");
        } else if (owner.getY() < 32 && !level.canSeeSky(ownerPos)) {
            conditionKey = "deep_cave";
            pool.add("Глубины земли хранят несметные сокровища для вас, " + h + ". Я продолжаю вглядываться в породу.");
            pool.add("Сырость и мрак подземелий мне привычны, Господин, но ваш факел радует взор.");
        } else if (level.isThundering()) {
            conditionKey = "thunder";
            pool.add("Небеса разрывает гроза, " + h + ". Держитесь подальше от высоких деревьев и открытых вершин.");
        }

        if (pool.isEmpty() || conditionKey.equals(lastAmbientCondition)) {
            return;
        }

        lastAmbientCondition = conditionKey;
        lastAmbientTick = tickCounter;
        speaker.speakToOwner(client, pickFreshLine(pool));
    }

    private String generateRespectfulMoodQuip(FamiliarMood mood, String lower, boolean isPlayerChat, boolean fromOwner, String ownerNick, boolean directlyNamed, boolean isDeath) {
        String h = randomHonorific();

        if (isDeath) {
            if (lower.contains(ownerNick)) {
                return pickFreshLine(List.of(
                        "О нет, " + h + "! Как же так... Я буду бережно охранять это место до вашего возвращения!",
                        "Какая трагическая случайность, мой господин! Ваш верный дух ждёт вас обратно.",
                        "Даже величайшие владыки порой оступаются, Повелитель. Скорее возвращайтесь!"
                ));
            } else {
                return pickFreshLine(List.of(
                        "Одним соперником меньше, " + h + ". Смертные так хрупки...",
                        "Кто-то только что отправился в мои прежние владения, Господин.",
                        "Печальный конец для бедняги, но вас, Владыка, я подобной участи не допущу."
                ));
            }
        }

        if (directlyNamed) {
            if (lower.contains("как дела") || lower.contains("как ты") || lower.contains("жив")) {
                return pickFreshLine(List.of(
                        "Моё существование обретает смысл лишь в служении вам, " + h + ". Сейчас у меня " + mood.getRussianLabel() + " настроение.",
                        "Бессмертен, бдителен и полностью в вашем распоряжении, мой господин."
                ));
            }
            if (lower.contains("молодец") || lower.contains("красав") || lower.contains("спасибо") || lower.contains("хорош") || lower.contains("умница")) {
                return pickFreshLine(List.of(
                        "Ваша похвала — высшая награда для меня, " + h + "!",
                        "Благодарю за милость, Владыка. Я постараюсь превзойти ваши ожидания!",
                        "Служу верой и правдой, мой господин!"
                ));
            }
            if (lower.contains("дурак") || lower.contains("туп") || lower.contains("бесишь") || lower.contains("глуп")) {
                return pickFreshLine(List.of(
                        "Простите мою оплошность, " + h + ", я немедленно исправлюсь.",
                        "Смиренно принимаю ваш упрёк, Господин. Впредь буду внимательнее."
                ));
            }
            if (lower.contains("привет") || lower.contains("ку") || lower.contains("здаров")) {
                return pickFreshLine(List.of(
                        "Приветствую вас, " + h + "! Какие будут приказания?",
                        "Рад снова слышать ваш голос, мой господин."
                ));
            }
            return pickFreshLine(List.of(
                    "Я весь во внимании, " + h + ".",
                    "Слушаю вас, Владыка. Приказывайте!",
                    "Ваш верный дух на связи, Господин."
            ));
        }

        if (lower.contains("алмаз") || lower.contains("diamond")) {
            return pickFreshLine(List.of(
                    "В чате упомянули алмазы, " + h + ". Если прикажете — я обшарю каждый камень вокруг!",
                    "Алмазы... Достойный металл для короны такого правителя, как вы, Владыка."
            ));
        }
        if (lower.contains("крипер") || lower.contains("creeper")) {
            return pickFreshLine(List.of(
                    "Эти зелёные взрывные твари не заслуживают ни капли жалости, " + h + ".",
                    "Будьте спокойны, Господин: я не подпущу ни одного крипера к вашей спине незамеченным."
            ));
        }
        if (lower.contains("помоги") || lower.contains("сос") || lower.contains("убивают") || lower.contains("help")) {
            return pickFreshLine(List.of(
                    "Кто-то в чате взывает о спасении, " + h + ". Вмешаемся или оставим их на волю судьбы?",
                    "Слышу крики о помощи в эфире, Владыка. Жду ваших распоряжений."
            ));
        }
        if (lower.contains("скучно") || lower.contains("делать нечего")) {
            return pickFreshLine(List.of(
                    "Если вам наскучила тишина, " + h + ", мы можем устроить танец или выследить кого-нибудь!",
                    "Позвольте развеять вашу скуку, Господин — просто отдайте приказ."
            ));
        }

        if (isPlayerChat && !fromOwner && ThreadLocalRandom.current().nextDouble() < 0.15) {
            return pickFreshLine(List.of(
                    "Другие путники ведут беседы в чате, " + h + ", но их слова не заслуживают вашего внимания.",
                    "Любопытно наблюдать за суетой смертных со стороны, не так ли, Владыка?"
            ));
        }

        return null;
    }

    private String buildDangerLine(String h, String threatLabel, int dist, String dir) {
        return pickFreshLine(List.of(
                "Берегитесь, " + h + "! В " + dist + " бл. " + dir + " замечен " + threatLabel + "!",
                "Тревога, мой господин! Прямо " + dir + " (" + dist + " бл.) подкрадывается " + threatLabel + ".",
                "Простите за беспокойство, Владыка, но " + threatLabel + " уже в " + dist + " бл. " + dir + " — будьте начеку!"
        ));
    }

    private String buildLootLine(String h, String lootLabel, int dist, String dir) {
        return pickFreshLine(List.of(
                "Спешу доложить, " + h + "! Я уловил отблеск тайны: " + lootLabel + " в " + dist + " бл. " + dir + ". Остальные тени уже чувствуют мой зов.",
                "Благоприятная находка, мой господин! " + lootLabel + " (" + dist + " бл. " + dir + ") — я запечатлел это место в нашем общем эфире.",
                "Позвольте порадовать вас, Владыка: " + dir + " в " + dist + " бл. сокрыта находка — " + lootLabel + "."
        ));
    }

    private static Familiar pickOtherFamiliar(List<Familiar> all, Familiar exclude) {
        List<Familiar> others = new ArrayList<>();
        for (Familiar f : all) {
            if (f != exclude) others.add(f);
        }
        if (others.isEmpty()) return null;
        return others.get(ThreadLocalRandom.current().nextInt(others.size()));
    }

    private static boolean isCommandLike(String lower) {
        return lower.contains("восстань") || lower.contains("вселись") || lower.contains("вернись")
                || lower.contains("упокойся") || lower.contains("доска ") || lower.contains("сброс")
                || lower.contains("сторож") || lower.contains("поклон") || lower.contains("укаж")
                || lower.contains("напуг") || lower.contains("танц") || lower.contains("марш")
                || lower.contains("в круг") || lower.contains("в шеренг") || lower.contains("преследуй")
                || lower.contains("что ты видишь") || lower.contains("что я вижу") || lower.contains("осмотри")
                || lower.contains("где ") || lower.contains("найди ") || lower.contains("погуляй")
                || lower.contains("на плечо") || lower.contains("молчи") || lower.contains("тихо")
                || lower.contains("болтай") || lower.contains("настроен") || lower.contains("память")
                || lower.contains("разум") || lower.contains("находк");
    }
}
