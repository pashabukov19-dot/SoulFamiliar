package com.soulfamiliar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
    private long lastChatReactionTick = -400;
    private long lastAmbientTick = 0;
    private long lastInitiativeTick = -300;
    private final Deque<String> recentLines = new ArrayDeque<>();

    // Delayed hive-mind reply state
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
        while (recentLines.size() > 28) {
            recentLines.removeFirst();
        }
    }

    public void tick(Minecraft client) {
        tickCounter++;
        if (client.level == null || client.player == null) {
            HiveMemory.getInstance().clear();
            pendingSpeech = null;
            return;
        }

        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        if (all.isEmpty()) return;

        // 1. Delayed hive-mind corroboration / banter
        if (pendingSpeech != null && --pendingDelayTicks <= 0) {
            Familiar f = FamiliarManager.getInstance().getByName(pendingSpeakerName);
            if (f != null) {
                f.speakToOwner(client, pendingSpeech);
            }
            pendingSpeech = null;
            pendingSpeakerName = null;
        }

        // 2. Proactive initiative scan every 3.5 seconds (70 ticks)
        if (tickCounter % 70 == 0 && (tickCounter - lastInitiativeTick) >= 160) {
            runInitiativeScan(client, all);
        }

        // 3. Mood-driven ambient observations every ~48 seconds
        if (chatterEnabled && tickCounter % 100 == 0 && (tickCounter - lastAmbientTick) >= 960) {
            lastAmbientTick = tickCounter;
            speakMoodObservation(client, all);
        }
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

        // Record into the Collective Mind; only announce if new to the Hive!
        boolean isNewToHive = HiveMemory.getInstance().recordDiscovery(
                found.uniqueKey(), found.label(), found.pos(), scout.getName(), tickCounter, found.isDanger()
        );
        if (!isNewToHive) return;

        lastInitiativeTick = tickCounter;
        String dir = WorldScout.relativeDirection(owner, found.pos());
        int dist = (int) Math.round(owner.getEyePosition().distanceTo(found.pos()));
        String h = randomHonorific();

        if (found.isDanger()) {
            String line = buildDangerLine(scout.getMood(), h, found.label(), dist, dir);
            scout.speakToOwner(client, line);
            level.playLocalSound(origin.x, origin.y, origin.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8f, 0.65f, false);
            if (scout.isBound()) {
                scout.triggerPoint(found.pos());
            } else {
                scout.focusOnLocation(found.pos(), 85);
            }
        } else {
            String line = buildLootLine(scout.getMood(), h, found.label(), dist, dir);
            scout.speakToOwner(client, line);
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
                scout.focusOnLocation(found.pos(), 125);
            }

            // Collective Mind confirmation from a second familiar!
            if (all.size() >= 2 && rng.nextDouble() < 0.55) {
                Familiar second = pickOtherFamiliar(all, scout);
                if (second != null) {
                    List<String> hiveReplies = List.of(
                            "Подтверждаю, " + randomHonorific() + ": через нашу общую память я тоже вижу " + found.label() + ", о которой доложил " + scout.getName() + ".",
                            "Наш единый разум зафиксировал находку, Повелитель. Я уже запомнил координаты, если прикажете показать путь.",
                            "Вижу то же самое глазами " + scout.getName() + ", Господин. Достойная добыча."
                    );
                    scheduleDelayedSpeech(second.getName(), pickFreshLine(hiveReplies), 48);
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

        if (rawText.startsWith("[Фамильяр]") || rawText.startsWith("[Коллективный разум]")) return;
        for (Familiar f : all) {
            if (rawText.startsWith("[" + f.getName() + "]")) return;
        }

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

        if (mentioned == null && !isDeath && (tickCounter - lastChatReactionTick) < 220) {
            return;
        }

        String quip = generateRespectfulMoodQuip(speaker.getMood(), lower, isPlayerChat, fromOwner, ownerNick, mentioned != null, isDeath);
        if (quip == null) return;

        lastChatReactionTick = tickCounter;
        speaker.speakToOwner(client, quip);

        // Occasional hive-mind chime-in from a second familiar
        if (all.size() >= 2 && rng.nextDouble() < 0.35) {
            Familiar other = pickOtherFamiliar(all, speaker);
            if (other != null) {
                List<String> followUps = List.of(
                        "Простите " + speaker.getName() + " за излишнее красноречие, " + randomHonorific() + ", но по сути он прав.",
                        "Наш общий разум полностью разделяет эту мысль, Повелитель.",
                        "Как верно подметил " + speaker.getName() + ", Господин. Мы всегда начеку."
                );
                scheduleDelayedSpeech(other.getName(), pickFreshLine(followUps), 46);
            }
        }
    }

    public void speakMoodJoke(Minecraft client, Familiar speaker) {
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

    private void speakMoodObservation(Minecraft client, List<Familiar> all) {
        LocalPlayer owner = client.player;
        ClientLevel level = client.level;
        if (owner == null || level == null) return;

        Familiar speaker = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        if (speaker.getMode() == FamiliarMode.FROZEN) return;

        String h = randomHonorific();
        List<String> pool = new ArrayList<>();

        if (owner.getHealth() <= owner.getMaxHealth() * 0.45f) {
            pool.add("Ваши жизненные силы на исходе, " + h + ". Позвольте настоятельно рекомендовать вам подкрепиться!");
            pool.add("Я чувствую вашу боль через нашу связь, " + h + ". Будьте осторожны, вы нам нужны живым.");
        }
        if (level.dimension() == Level.NETHER) {
            pool.add("Жар Нижнего мира обжигает даже эфир, " + h + ", но я не отступлю ни на шаг.");
            pool.add("Будьте бдительны среди этих багровых пустошей, Владыка — пиглины не знают почтения.");
        } else if (level.dimension() == Level.END) {
            pool.add("Холодная бездна Края шепчет древние тайны, " + h + ". Не смотрите в глаза местным стражам.");
        } else if (owner.getY() < 40 && !level.canSeeSky(owner.blockPosition())) {
            pool.add("Глубины земли хранят несметные сокровища для вас, " + h + ". Я продолжаю сканировать породу.");
            pool.add("Сырость и мрак подземелий мне привычны, Господин, но ваш факел радует взор.");
        } else if (level.isRaining()) {
            pool.add("Небеса проливаются дождём, " + h + ". Хорошо, что моему эфирному облику не грозит простуда.");
        }

        switch (speaker.getMood()) {
            case DEVOTED -> {
                pool.add("Все границы вокруг проверены, " + h + ". Служить вам — великая честь.");
                pool.add("Я внимательно слежу за каждым шорохом вокруг, мой господин.");
            }
            case PHILOSOPHICAL -> {
                pool.add("Тишина этого мира обманчива, " + h + ", но под вашим началом нам нечего страшиться.");
                pool.add("Любопытно наблюдать за течением времени смертных, оставаясь вашей верной тенью, Владыка.");
            }
            case MISCHIEVOUS -> {
                pool.add("Скучновато без приключений, " + h + ". Может, устроим засаду или проверим дальние пещеры?");
                pool.add("Мои глазницы так и ищут для вас сундук покрупнее, Господин!");
            }
            case MELANCHOLIC -> {
                pool.add("Ветер странствий вновь ведёт нас вперёд, " + h + ". Куда лежит ваш путь теперь?");
                pool.add("Даже в тишине я слышу отголоски древней магии, что связывает нас, Повелитель.");
            }
        }

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
        if (lower.contains("торг") || lower.contains("продам") || lower.contains("куплю")) {
            return pickFreshLine(List.of(
                    "Местные торговцы суетятся в чате, " + h + ", но истинные сокровища мы добудем сами."
            ));
        }
        if (lower.contains("лаг") || lower.contains("пинг") || lower.contains("tps")) {
            return pickFreshLine(List.of(
                    "Ткань этого мира слегка дрожит, " + h + ", но наша ментальная связь нерушима."
            ));
        }

        if (isPlayerChat && !fromOwner && ThreadLocalRandom.current().nextDouble() < 0.30) {
            return pickFreshLine(List.of(
                    "Другие путники ведут беседы в чате, " + h + ", но их слова не заслуживают вашего внимания.",
                    "Любопытно наблюдать за суетой смертных со стороны, не так ли, Владыка?"
            ));
        }

        return null;
    }

    private String buildDangerLine(FamiliarMood mood, String h, String threatLabel, int dist, String dir) {
        return pickFreshLine(List.of(
                "Берегитесь, " + h + "! В " + dist + " бл. " + dir + " замечен " + threatLabel + "!",
                "Тревога, мой господин! Прямо " + dir + " (" + dist + " бл.) подкрадывается " + threatLabel + ".",
                "Простите за беспокойство, Владыка, но " + threatLabel + " уже в " + dist + " бл. " + dir + " — будьте начеку!"
        ));
    }

    private String buildLootLine(FamiliarMood mood, String h, String lootLabel, int dist, String dir) {
        return pickFreshLine(List.of(
                "Спешу доложить, " + h + "! Я обнаружил ценность: " + lootLabel + " в " + dist + " бл. " + dir + ". Передаю координаты в наш общий разум!",
                "Благоприятная находка, мой господин! " + lootLabel + " (" + dist + " бл. " + dir + ") — я отметил это место для вас.",
                "Позвольте порадовать вас, Владыка: " + dir + " в " + dist + " бл. скрывается " + lootLabel + "."
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
                || lower.contains("разум");
    }
}
