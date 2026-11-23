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
