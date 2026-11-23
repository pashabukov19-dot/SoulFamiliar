package com.soulfamiliar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class FamiliarDialogueEngine {
    private static final FamiliarDialogueEngine INSTANCE = new FamiliarDialogueEngine();

    private boolean chatterEnabled = true;
    private long tickCounter = 0;
    private long lastChatReactionTick = -400;
    private long lastAmbientTick = 0;
    private long lastInitiativeTick = -300;
    private final Map<String, Long> reportedDiscoveries = new HashMap<>();

    // Delayed banter state
    private String pendingSpeakerName = null;
    private String pendingSpeech = null;
    private int pendingDelayTicks = 0;

    private FamiliarDialogueEngine() {}

    public static FamiliarDialogueEngine getInstance() {
        return INSTANCE;
    }

    public void setChatterEnabled(boolean enabled) {
        this.chatterEnabled = enabled;
    }

    public boolean isChatterEnabled() {
        return chatterEnabled;
    }

    public void scheduleDelayedSpeech(String familiarName, String line, int delayTicks) {
        this.pendingSpeakerName = familiarName;
        this.pendingSpeech = line;
        this.pendingDelayTicks = delayTicks;
    }

    public void tick(Minecraft client) {
        tickCounter++;
        if (client.level == null || client.player == null) {
            reportedDiscoveries.clear();
            pendingSpeech = null;
            return;
        }

        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        if (all.isEmpty()) return;

        // 1. Handle delayed banter reply
        if (pendingSpeech != null && --pendingDelayTicks <= 0) {
            Familiar f = FamiliarManager.getInstance().getByName(pendingSpeakerName);
            if (f != null) {
                f.speakToOwner(client, pendingSpeech);
            }
            pendingSpeech = null;
            pendingSpeakerName = null;
        }

        // 2. Proactive initiative scan every 4 seconds (80 ticks)
        if (tickCounter % 80 == 0 && (tickCounter - lastInitiativeTick) >= 160) {
            runInitiativeScan(client, all);
        }

        // 3. Ambient sarcastic chatter every ~42 seconds if enabled
        if (chatterEnabled && tickCounter % 100 == 0 && (tickCounter - lastAmbientTick) >= 840) {
            lastAmbientTick = tickCounter;
            speakAmbientQuip(client, all);
        }
    }

    private void runInitiativeScan(Minecraft client, List<Familiar> all) {
        ClientLevel level = client.level;
        LocalPlayer owner = client.player;
        if (level == null || owner == null) return;

        // Clean expired discovery memories (older than 2 minutes = 2400 ticks)
        reportedDiscoveries.entrySet().removeIf(entry -> (tickCounter - entry.getValue()) > 2400);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Familiar scout = all.get(rng.nextInt(all.size()));
        if (scout.getMode() == FamiliarMode.FROZEN || scout.getMode() == FamiliarMode.POSSESSED) return;

        Vec3 origin = scout.getCurrentWorldPosition(level, owner);
        WorldScout.Discovery found = WorldScout.scanForInitiative(level, owner, origin);
        if (found == null) return;

        // Only comment if chatter is enabled OR it's a direct danger!
        if (!chatterEnabled && !found.isDanger()) return;
        if (reportedDiscoveries.containsKey(found.uniqueKey())) return;

        reportedDiscoveries.put(found.uniqueKey(), tickCounter);
        lastInitiativeTick = tickCounter;

        String dir = WorldScout.relativeDirection(owner, found.pos());
        int dist = (int) Math.round(owner.getEyePosition().distanceTo(found.pos()));

        if (found.isDanger()) {
            String line = buildDangerQuip(found.label(), dist, dir);
            scout.speakToOwner(client, line);
            level.playLocalSound(origin.x, origin.y, origin.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 0.6f, false);
            if (scout.isBound()) {
                scout.triggerPoint(found.pos());
            } else {
                scout.focusOnLocation(found.pos(), 80);
            }
        } else {
            String line = buildLootQuip(found.label(), dist, dir);
            scout.speakToOwner(client, line);
            level.playLocalSound(found.pos().x, found.pos().y, found.pos().z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.85f, 1.25f, false);
            for (int i = 0; i < 14; i++) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME,
                        found.pos().x + (rng.nextDouble() - 0.5) * 0.8,
                        found.pos().y + 0.6 + rng.nextDouble() * 0.5,
                        found.pos().z + (rng.nextDouble() - 0.5) * 0.8,
                        0, 0.02, 0);
            }
            if (scout.isBound()) {
                scout.triggerPoint(found.pos());
            } else {
                scout.focusOnLocation(found.pos(), 120);
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

        // Ignore our own mod prefix messages to avoid infinite self-loops
        if (rawText.startsWith("[Фамильяр]")) return;
        for (Familiar f : all) {
            if (rawText.startsWith("[" + f.getName() + "]")) return;
        }

        // Ignore commands the owner just typed that already get a direct command response
        boolean fromOwner = isPlayerChat && (lower.startsWith("<" + ownerNick + ">") || lower.contains(ownerNick + ":") || lower.contains(ownerNick + " »"));
        if (fromOwner && isCommandLike(lower)) return;

        // Check if a familiar's name was mentioned in chat
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

        // Cooldown: 10 seconds for general chat unless directly named or a death occurred
        boolean isDeath = !isPlayerChat && (lower.contains("slain") || lower.contains("blew up") || lower.contains("fell")
                || lower.contains("drowned") || lower.contains("burned") || lower.contains("tried to swim in lava")
                || lower.contains("умер") || lower.contains("погиб") || lower.contains("взорвал") || lower.contains("упал") || lower.contains("сгорел"));

        if (mentioned == null && !isDeath && (tickCounter - lastChatReactionTick) < 200) {
            return;
        }

        String quip = generateChatQuip(lower, isPlayerChat, fromOwner, ownerNick, mentioned != null, isDeath);
        if (quip == null) return;

        lastChatReactionTick = tickCounter;
        speaker.speakToOwner(client, quip);

        // 35% chance a second familiar chimes in with banter!
        if (all.size() >= 2 && rng.nextDouble() < 0.35) {
            Familiar other = null;
            for (Familiar candidate : all) {
                if (candidate != speaker) {
                    other = candidate;
                    break;
                }
            }
            if (other != null) {
                String[] followUps = {
                        "Опять " + speaker.getName() + " умничает. Не слушай его.",
                        "Редкий случай, но я с ним согласен.",
                        "Вы оба звучите утомительно. Может, просто пойдём копать?",
                        "А я бы на это посмотрел с попкорном. Жаль, у меня нет желудка."
                };
                scheduleDelayedSpeech(other.getName(), followUps[rng.nextInt(followUps.length)], 45);
            }
        }
    }

    public void speakImmediateJoke(Minecraft client, Familiar speaker) {
        String[] jokes = {
                "Знаешь, почему скелеты такие спокойные? Потому что их невозможно вывести из себя — у них и так одни кости.",
                "Я потратил три века в эфире, чтобы в итоге носить твои поручения. Карьера мечты.",
                "Если крипер зашипит за спиной, не переживай — я обязательно красиво поклонюсь на твоих поминках.",
                "У меня нет ни рук, ни тела, а работаю я всё равно больше всех на этом сервере.",
                "Иногда я смотрю на твой инвентарь и думаю: «Да, это определённо стратегия гения»."
        };
        speaker.speakToOwner(client, jokes[ThreadLocalRandom.current().nextInt(jokes.length)]);
        speaker.triggerShakeHead();
    }

    private void speakAmbientQuip(Minecraft client, List<Familiar> all) {
        LocalPlayer owner = client.player;
        ClientLevel level = client.level;
        if (owner == null || level == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Familiar speaker = all.get(rng.nextInt(all.size()));
        if (speaker.getMode() == FamiliarMode.FROZEN) return;

        List<String> pool = new ArrayList<>();
        if (owner.getHealth() <= owner.getMaxHealth() * 0.45f) {
            pool.add("У тебя здоровья на полтора чиха. Может, поешь, или мне уже присматривать новую стойку?");
            pool.add("Обожаю этот адреналин: ещё один удар — и я снова свободный дух!");
        }
        if (level.dimension() == Level.NETHER) {
            pool.add("Прекрасный курорт. Лава, гасты, запах серы — чувствую себя как дома.");
            pool.add("Главное здесь — не чихнуть рядом с пиглинами. Хотя мне-то что, я и так мёртв.");
        } else if (level.dimension() == Level.END) {
            pool.add("Бесконечная пустота и высокие тощие парни, не любящие зрительный контакт. Уютненько.");
        } else if (owner.getY() < 40 && !level.canSeeSky(owner.blockPosition())) {
            pool.add("Мы уже достаточно глубоко, или ты планируешь прокопать мир насквозь?");
            pool.add("В этих пещерах так темно, что даже мои глазницы начинают щуриться.");
        } else if (level.isRaining()) {
            pool.add("Отличная погода для прогулок. Хорошо, что у меня нет ни плаща, ни лёгких.");
        }

        pool.add("Стоим, дышим, философствуем. Великолепный темп приключений.");
        pool.add("Если что, я всё вижу. Даже то, что ты сейчас пытаешься сделать вид, будто всё под контролем.");
        pool.add("Скучновато. Может, напугаем кого-нибудь или устроим танцы?");
        pool.add("Напоминаю: я бессмертный дух, а не бесплатный фонарик. Хотя свечусь неплохо.");

        speaker.speakToOwner(client, pool.get(rng.nextInt(pool.size())));
    }

    private String generateChatQuip(String lower, boolean isPlayerChat, boolean fromOwner, String ownerNick, boolean directlyNamed, boolean isDeath) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (isDeath) {
            if (lower.contains(ownerNick)) {
                String[] ownerDeaths = {
                        "Блестящий манёвр! Особенно та часть, где ты умер.",
                        "Я всё записал. Назову этот приём «Тактическое испарение».",
                        "Не волнуйся, я присмотрю за твоими вещами. Наверное."
                };
                return ownerDeaths[rng.nextInt(ownerDeaths.length)];
            } else {
                String[] otherDeaths = {
                        "Минус один. Естественный отбор на сервере работает исправно.",
                        "Кто-то только что пополнил ряды моих коллег по загробному миру.",
                        "F. Я бы снял шляпу, но у меня только череп."
                };
                return otherDeaths[rng.nextInt(otherDeaths.length)];
            }
        }

        if (directlyNamed) {
            if (lower.contains("как дела") || lower.contains("жив")) {
                return "Технически я мёртв, так что стабильно. А ты всё ещё пытаешься выжить?";
            }
            if (lower.contains("молодец") || lower.contains("красав") || lower.contains("спасибо") || lower.contains("хорош")) {
                return "Запишу эту похвалу в свой несуществующий дневник. Приятно, не скрою.";
            }
            if (lower.contains("дурак") || lower.contains("туп") || lower.contains("бесишь") || lower.contains("глуп")) {
                return "Смелое заявление от существа с ограниченным сроком годности.";
            }
            if (lower.contains("привет") || lower.contains("ку") || lower.contains("здаров")) {
                return "И тебе не хворать. Надеюсь, сегодня обойдётся без купания в лаве.";
            }
            String[] namedReplies = {
                    "Я всё слышу. Можешь не повышать шрифт.",
                    "О, обо мне вспомнили! Неужели нужно кого-то напугать?",
                    "Внимательно слушаю. Только давай без очередных гениальных авантюр."
            };
            return namedReplies[rng.nextInt(namedReplies.length)];
        }

        // React to specific keywords in chat
        if (lower.contains("алмаз") || lower.contains("diamond")) {
            return "Стоило кому-то сказать «алмазы», как у меня сразу зачесались скулы.";
        }
        if (lower.contains("крипер") || lower.contains("creeper")) {
            return "Криперы — единственные существа здесь, у которых ещё меньше терпения, чем у меня.";
        }
        if (lower.contains("помоги") || lower.contains("сос") || lower.contains("убивают") || lower.contains("help")) {
            return "Звучит как чья-то личная трагедия. Надеюсь, не наша.";
        }
        if (lower.contains("скучно") || lower.contains("делать нечего")) {
            return "Если кому-то скучно, я могу устроить скример или партию в шахматы.";
        }
        if (lower.contains("привет") || lower.contains("всем ку") || lower.contains("здарова")) {
            if (!fromOwner && rng.nextDouble() < 0.65) {
                return "О, в чате появилась жизнь. Посмотрим, надолго ли.";
            }
            return null;
        }
        if (lower.contains("торг") || lower.contains("продам") || lower.contains("куплю")) {
            return "Предлагаю обмен: мои бесценные советы на пару изумрудов. Выгодная сделка.";
        }
        if (lower.contains("лаг") || lower.contains("пинг") || lower.contains("tps")) {
            return "Это не лаги, это искажение астрального плана. Ну или сервер прилёг отдохнуть.";
        }

        // Occasional dry remark on any other player chat (35% chance)
        if (isPlayerChat && !fromOwner && rng.nextDouble() < 0.35) {
            String[] generalBanter = {
                    "Захватывающая беседа. Продолжайте, я конспектирую.",
                    "У смертных такие увлекательные проблемы...",
                    "Интересно, они всегда столько болтают вместо того, чтобы делом заняться?"
            };
            return generalBanter[rng.nextInt(generalBanter.length)];
        }

        return null;
    }

    private static String buildDangerQuip(String threatLabel, int dist, String dir) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String[] templates = {
                "Обернись! В " + dist + " бл. " + dir + " крадётся " + threatLabel + ". Не хочу терять работодателя!",
                "Внимание: " + threatLabel + " (" + dist + " бл., " + dir + "). Если взорвёшься — я не виноват.",
                "У нас гости: " + threatLabel + " прямо " + dir + " (" + dist + " бл.). Рекомендую не зевать."
        };
        return templates[rng.nextInt(templates.length)];
    }

    private static String buildLootQuip(String lootLabel, int dist, String dir) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String[] templates = {
                "Эй, я тут заметил кое-что блестящее: " + lootLabel + " в " + dist + " бл. " + dir + ". Иди посмотри!",
                "Находка! " + lootLabel + " (" + dist + " бл., " + dir + "). И что бы ты без моих глазниц делал?",
                "Погляди-ка " + dir + " (" + dist + " бл.) — там " + lootLabel + ". Делим добычу по-братски: тебе блоки, мне слава."
        };
        return templates[rng.nextInt(templates.length)];
    }

    private static boolean isCommandLike(String lower) {
        return lower.contains("восстань") || lower.contains("вселись") || lower.contains("вернись")
                || lower.contains("упокойся") || lower.contains("доска ") || lower.contains("сброс")
                || lower.contains("сторож") || lower.contains("поклон") || lower.contains("укаж")
                || lower.contains("напуг") || lower.contains("танц") || lower.contains("марш")
                || lower.contains("в круг") || lower.contains("в шеренг") || lower.contains("преследуй")
                || lower.contains("что ты видишь") || lower.contains("осмотрись") || lower.contains("где ")
                || lower.contains("найди ") || lower.contains("погуляй") || lower.contains("на плечо")
                || lower.contains("молчи") || lower.contains("тихо") || lower.contains("болтай");
    }
}
