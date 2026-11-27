package com.soulfamiliar;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class GrimoireState {
    private static final GrimoireState INSTANCE = new GrimoireState();

    public enum CardRow {
        MELEE("ПЕР", 0xFFE53935, "Передовая (Стойки)"),
        RANGED("ДАЛ", 0xFF29B6F6, "Дальний бой (Эфир)"),
        SIEGE("ОСА", 0xFFFFB300, "Осада (Сундуки)"),
        SPY("ШПИОН", 0xFFAB47BC, "В ряд врага (+2 карты)"),
        WEATHER("ПОГ", 0xFF5C6BC0, "Погодный слот"),
        DECOY("ЧУЧ", 0xFF66BB6A, "Вернуть свой отряд");

        public final String badge;
        public final int color;
        public final String hint;

        CardRow(String badge, int color, String hint) {
            this.badge = badge;
            this.color = color;
            this.hint = hint;
        }
    }

    public static final class CardDef {
        public final String id;
        public final String name;
        public final CardRow row;
        public final int power;
        public final int rarity; // 3, 4, 5
        public final boolean hero;
        public final String ability; // NONE, SPY, DECOY, FROST, FOG, RAIN, CLEAR, BOND, MEDIC
        public final String desc;
        public final int accentColor;
        public final int iconKind; // 0..7 procedural pixel art glyph

        public CardDef(String id, String name, CardRow row, int power, int rarity, boolean hero, String ability, String desc, int accentColor, int iconKind) {
            this.id = id;
            this.name = name;
            this.row = row;
            this.power = power;
            this.rarity = rarity;
            this.hero = hero;
            this.ability = ability;
            this.desc = desc;
            this.accentColor = accentColor;
            this.iconKind = iconKind;
        }
    }

    public static final class WishResult {
        public final CardDef card;
        public final boolean isNew;
        public final int shardRefund;

        public WishResult(CardDef card, boolean isNew, int shardRefund) {
            this.card = card;
            this.isNew = isNew;
            this.shardRefund = shardRefund;
        }
    }

    private final Map<String, CardDef> catalog = new LinkedHashMap<>();
    private final Set<String> unlockedIds = new LinkedHashSet<>();
    private final List<String> activeDeckIds = new ArrayList<>();

    private int ether = 480;
    private double passiveFraction = 0.0;
    private int pityCounter = 0;
    private int totalPulls = 0;
    private int snakeHighScore = 0;
    private int ashWins = 0;

    // Daily quests state (client-side only, reset per LocalDate)
    private String dailyDate = "";
    private int baselineWalkCm = -1;
    private int baselineJumps = -1;
    private int dailyTalkCount = 0;
    private int dailyGrimoireActionCount = 0;

    private boolean dailyWalkClaimed = false;
    private boolean dailyTalkClaimed = false;
    private boolean dailyGrimoireClaimed = false;

    private int currentWalkProgressBlocks = 0;

    private GrimoireState() {
        registerCards();
        initDefaultCollection();
    }

    public static GrimoireState getInstance() {
        return INSTANCE;
    }

    private void registerCards() {
        // Starter & 3* cards
        addCard(new CardDef("watchman", "Деревянный Часовой", CardRow.MELEE, 5, 3, false, "NONE", "Стойкий страж передовой", 0xFFBC8A5F, 0));
        addCard(new CardDef("pigeon", "Плюшевый Голубь", CardRow.RANGED, 4, 3, false, "NONE", "Пернатый вестник эфира", 0xFF90CAF9, 1));
        addCard(new CardDef("chest_whisper", "Шёпот Сундука", CardRow.SIEGE, 6, 3, false, "NONE", "Тяжёлый ларь с секретами", 0xFFFFCA28, 2));
        addCard(new CardDef("scout_gear", "Шарнирный Лазутчик", CardRow.RANGED, 4, 3, false, "NONE", "Наблюдатель из тени", 0xFF80DEEA, 3));
        addCard(new CardDef("ash_guard", "Пепельный Копейщик", CardRow.MELEE, 4, 3, false, "BOND", "Крепкий боец свиты", 0xFFEF9A9A, 0));
        addCard(new CardDef("relic_anvil", "Осадная Наковальня", CardRow.SIEGE, 5, 3, false, "NONE", "Кузнечный молот пепла", 0xFFFFB74D, 2));
        addCard(new CardDef("draft_spy", "Любопытный Сквозняк", CardRow.SPY, 2, 3, false, "SPY", "В ряд врага: тянешь +2 карты", 0xFFCE93D8, 4));
        addCard(new CardDef("decoy_stand", "Подмена Стойки", CardRow.DECOY, 0, 3, false, "DECOY", "Возвращает ваш отряд в руку", 0xFFA5D6A7, 5));
        addCard(new CardDef("frost_rain", "Ливень Забвения", CardRow.WEATHER, 0, 3, false, "FROST", "Погода: ПЕР не-герои -> 1", 0xFF7986CB, 6));
        addCard(new CardDef("ether_fog", "Эфирный Туман", CardRow.WEATHER, 0, 3, false, "FOG", "Погода: ДАЛ не-герои -> 1", 0xFF4DD0E1, 6));
        addCard(new CardDef("siege_rust", "Скрип Петель", CardRow.WEATHER, 0, 3, false, "RAIN", "Погода: ОСА не-герои -> 1", 0xFFFFD54F, 6));
        addCard(new CardDef("clear_bow", "Ясный Поклон", CardRow.WEATHER, 0, 3, false, "CLEAR", "Снимает всю погоду со стола", 0xFFFFF59D, 7));

        // 4* Gacha cards
        addCard(new CardDef("pigeon_knight", "Голубь-Император", CardRow.RANGED, 7, 4, false, "NONE", "Бронированный владыка крыш", 0xFF4FC3F7, 1));
        addCard(new CardDef("wisp_twin", "Блуждающий Огонёк", CardRow.RANGED, 6, 4, false, "NONE", "Чистый сгусток эфира", 0xFF26C6DA, 3));
        addCard(new CardDef("chest_mimic", "Тень Двойного Сундука", CardRow.SIEGE, 8, 4, false, "NONE", "Хранитель редких трофеев", 0xFFFFB300, 2));
        addCard(new CardDef("creeper_agent", "Крипер-Лазутчик", CardRow.SPY, 1, 4, false, "SPY", "Лучший шпион: +2 карты в руку", 0xFFBA68C8, 4));
        addCard(new CardDef("soul_vanguard", "Рыцарь Клятвы", CardRow.MELEE, 7, 4, false, "NONE", "Непоколебимый клинок", 0xFFE57373, 0));

        // 5* Hero Gacha cards (immune to weather)
        addCard(new CardDef("hero_ignis", "Игнис: Владыка Пепла", CardRow.MELEE, 10, 5, true, "HERO", "ГЕРОЙ: иммунитет к погоде", 0xFFFFD700, 0));
        addCard(new CardDef("hero_skull", "Череп Бездны", CardRow.RANGED, 10, 5, true, "HERO", "ГЕРОЙ: иммунитет к погоде", 0xFF00E5FF, 3));
        addCard(new CardDef("hero_architect", "Архитектор Стойких", CardRow.SIEGE, 10, 5, true, "HERO", "ГЕРОЙ: иммунитет к погоде", 0xFFFFAB00, 2));
        addCard(new CardDef("hero_sovereign", "Тень Повелителя", CardRow.MELEE, 11, 5, true, "HERO", "ГЕРОЙ: высшая мощь пепла", 0xFFFF6E40, 7));
    }

    private void addCard(CardDef def) {
        catalog.put(def.id, def);
    }

    private void initDefaultCollection() {
        unlockedIds.clear();
        String[] starters = {
                "watchman", "pigeon", "chest_whisper", "scout_gear",
                "ash_guard", "relic_anvil", "draft_spy", "decoy_stand",
                "frost_rain", "ether_fog", "clear_bow"
        };
        Collections.addAll(unlockedIds, starters);
        rebuildBestDeck();
    }

    public List<CardDef> getAllCatalogCards() {
        return new ArrayList<>(catalog.values());
    }

    public CardDef getCard(String id) {
        return catalog.get(id);
    }

    public boolean isUnlocked(String id) {
        return unlockedIds.contains(id);
    }

    public Set<String> getUnlockedIds() {
        return Collections.unmodifiableSet(unlockedIds);
    }

    public List<String> getActiveDeckIds() {
        if (activeDeckIds.size() < 8) {
            rebuildBestDeck();
        }
        return Collections.unmodifiableList(activeDeckIds);
    }

    public boolean isInActiveDeck(String id) {
        return activeDeckIds.contains(id);
    }

    public void toggleCardInDeck(String id) {
        if (!unlockedIds.contains(id)) return;
        if (activeDeckIds.contains(id)) {
            if (activeDeckIds.size() > 8) {
                activeDeckIds.remove(id);
                save();
            }
        } else if (activeDeckIds.size() < 14) {
            activeDeckIds.add(id);
            save();
        }
    }

    public void rebuildBestDeck() {
        List<CardDef> owned = new ArrayList<>();
        for (String id : unlockedIds) {
            CardDef def = catalog.get(id);
            if (def != null) owned.add(def);
        }
        owned.sort((a, b) -> {
            int rCmp = Integer.compare(b.rarity, a.rarity);
            if (rCmp != 0) return rCmp;
            int pCmp = Integer.compare(b.power, a.power);
            if (pCmp != 0) return pCmp;
            return a.name.compareTo(b.name);
        });
        activeDeckIds.clear();
        for (CardDef def : owned) {
            if (activeDeckIds.size() >= 12) break;
            activeDeckIds.add(def.id);
        }
    }

    public int getEther() {
        return ether;
    }

    public void addEther(int amount) {
        if (amount <= 0) return;
        ether += amount;
        save();
    }

    public int getPityCounter() {
        return pityCounter;
    }

    public int getTotalPulls() {
        return totalPulls;
    }

    public int getSnakeHighScore() {
        return snakeHighScore;
    }

    public void recordSnakeScore(int score) {
        if (score > snakeHighScore) {
            snakeHighScore = score;
        }
        save();
    }

    public int getAshWins() {
        return ashWins;
    }

    public void recordAshMatchWin() {
        ashWins++;
        dailyGrimoireActionCount++;
        addEther(160);
    }

    public void recordGrimoireActivity() {
        ensureDailyDate();
        dailyGrimoireActionCount++;
        save();
    }

    public void recordFamiliarDialogueActivity() {
        ensureDailyDate();
        dailyTalkCount++;
        save();
    }

    public int getPassiveEtherPerMinute(int activeFamiliars) {
        if (activeFamiliars <= 0) return 0;
        // 12 ether/min for 1 familiar + 8 ether/min per extra familiar (up to 6)
        int capped = Math.min(6, activeFamiliars);
        return 12 + (capped - 1) * 8;
    }

    public void tickClient(Minecraft client, int activeFamiliarCount, long tickCounter) {
        ensureDailyDate();

        if (client.player != null && activeFamiliarCount > 0) {
            int perMin = getPassiveEtherPerMinute(activeFamiliarCount);
            passiveFraction += perMin / 1200.0; // 20 ticks/s * 60s = 1200 ticks/min
            if (passiveFraction >= 1.0) {
                int gained = (int) passiveFraction;
                passiveFraction -= gained;
                ether += gained;
                if (tickCounter % 600 == 0) {
                    save();
                }
            }
        }

        if (client.player != null && tickCounter % 20 == 0) {
            updateLocalStatsProgress(client.player);
        }
    }

    private void updateLocalStatsProgress(LocalPlayer player) {
        try {
            int walkCm = player.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM))
                    + player.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM));
            if (baselineWalkCm < 0 || walkCm < baselineWalkCm) {
                baselineWalkCm = walkCm;
            }
            int deltaCm = Math.max(0, walkCm - baselineWalkCm);
            currentWalkProgressBlocks = deltaCm / 100;
        } catch (Throwable ignored) {
            // Safe fallback if stats are not yet synced by server
        }
    }

    private void ensureDailyDate() {
        String today = LocalDate.now().toString();
        if (!today.equals(dailyDate)) {
            dailyDate = today;
            baselineWalkCm = -1;
            baselineJumps = -1;
            dailyTalkCount = 0;
            dailyGrimoireActionCount = 0;
            dailyWalkClaimed = false;
            dailyTalkClaimed = false;
            dailyGrimoireClaimed = false;
            currentWalkProgressBlocks = 0;
        }
    }

    // Daily Quest 1: Walk 250 blocks (tracked via client player stats)
    public int getDailyWalkProgress() {
        return Math.min(250, currentWalkProgressBlocks);
    }

    public boolean isDailyWalkReady() {
        return !dailyWalkClaimed && currentWalkProgressBlocks >= 250;
    }

    public boolean isDailyWalkClaimed() {
        return dailyWalkClaimed;
    }

    public boolean claimDailyWalk(Minecraft client) {
        if (!isDailyWalkReady()) return false;
        dailyWalkClaimed = true;
        addEther(100);
        playClaimSound(client);
        return true;
    }

    // Daily Quest 2: Speak with your familiars 3 times
    public int getDailyTalkProgress() {
        return Math.min(3, dailyTalkCount);
    }

    public boolean isDailyTalkReady() {
        return !dailyTalkClaimed && dailyTalkCount >= 3;
    }

    public boolean isDailyTalkClaimed() {
        return dailyTalkClaimed;
    }

    public boolean claimDailyTalk(Minecraft client) {
        if (!isDailyTalkReady()) return false;
        dailyTalkClaimed = true;
        addEther(80);
        playClaimSound(client);
        return true;
    }

    // Daily Quest 3: Play Chronicles of Ash or Ether Snake 2 times
    public int getDailyGrimoireProgress() {
        return Math.min(2, dailyGrimoireActionCount);
    }

    public boolean isDailyGrimoireReady() {
        return !dailyGrimoireClaimed && dailyGrimoireActionCount >= 2;
    }

    public boolean isDailyGrimoireClaimed() {
        return dailyGrimoireClaimed;
    }

    public boolean claimDailyGrimoire(Minecraft client) {
        if (!isDailyGrimoireReady()) return false;
        dailyGrimoireClaimed = true;
        addEther(120);
        playClaimSound(client);
        return true;
    }

    private void playClaimSound(Minecraft client) {
        if (client != null && client.player != null && client.level != null) {
            client.level.playLocalSound(
                    client.player.getX(), client.player.getY(), client.player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.9f, 1.25f, false
            );
        }
    }

    public List<WishResult> performSummon(int count) {
        int cost = count * 160;
        if (ether < cost) return Collections.emptyList();
        ether -= cost;

        List<WishResult> results = new ArrayList<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        List<CardDef> pool5 = new ArrayList<>();
        List<CardDef> pool4 = new ArrayList<>();
        List<CardDef> pool3 = new ArrayList<>();
        for (CardDef d : catalog.values()) {
            if (d.rarity == 5) pool5.add(d);
            else if (d.rarity == 4) pool4.add(d);
            else pool3.add(d);
        }

        for (int i = 0; i < count; i++) {
            pityCounter++;
            totalPulls++;

            int rarity;
            if (pityCounter >= 10) {
                rarity = 5;
                pityCounter = 0;
            } else {
                double roll = rng.nextDouble();
                if (roll < 0.12) {
                    rarity = 5;
                    pityCounter = 0;
                } else if (roll < 0.42 || (count == 10 && i == 9)) {
                    rarity = 4;
                } else {
                    rarity = 3;
                }
            }

            List<CardDef> chosenPool = rarity == 5 ? pool5 : (rarity == 4 ? pool4 : pool3);
            CardDef picked = chosenPool.get(rng.nextInt(chosenPool.size()));
            boolean isNew = unlockedIds.add(picked.id);
            int refund = 0;
            if (isNew) {
                if (activeDeckIds.size() < 14) {
                    activeDeckIds.add(picked.id);
                }
            } else {
                refund = rarity == 5 ? 80 : (rarity == 4 ? 40 : 20);
                ether += refund;
            }
            results.add(new WishResult(picked, isNew, refund));
        }

        dailyGrimoireActionCount++;
        save();
        return results;
    }

    private Path getSavePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("soulfamiliar_grimoire.properties");
    }

    public void load() {
        ensureDailyDate();
        Path path = getSavePath();
        if (!Files.exists(path)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            this.ether = Integer.parseInt(props.getProperty("ether", "480"));
            this.pityCounter = Integer.parseInt(props.getProperty("pity", "0"));
            this.totalPulls = Integer.parseInt(props.getProperty("pulls", "0"));
            this.snakeHighScore = Integer.parseInt(props.getProperty("snake_high", "0"));
            this.ashWins = Integer.parseInt(props.getProperty("ash_wins", "0"));

            String savedDate = props.getProperty("daily_date", "");
            if (dailyDate.equals(savedDate)) {
                this.baselineWalkCm = Integer.parseInt(props.getProperty("base_walk_cm", "-1"));
                this.dailyTalkCount = Integer.parseInt(props.getProperty("daily_talks", "0"));
                this.dailyGrimoireActionCount = Integer.parseInt(props.getProperty("daily_grimoire", "0"));
                this.dailyWalkClaimed = Boolean.parseBoolean(props.getProperty("claim_walk", "false"));
                this.dailyTalkClaimed = Boolean.parseBoolean(props.getProperty("claim_talk", "false"));
                this.dailyGrimoireClaimed = Boolean.parseBoolean(props.getProperty("claim_grimoire", "false"));
            }

            String unlockedRaw = props.getProperty("unlocked", "");
            if (!unlockedRaw.isBlank()) {
                unlockedIds.clear();
                for (String part : unlockedRaw.split(",")) {
                    String trimmed = part.trim();
                    if (catalog.containsKey(trimmed)) {
                        unlockedIds.add(trimmed);
                    }
                }
            }
            if (unlockedIds.isEmpty()) {
                initDefaultCollection();
            }

            String deckRaw = props.getProperty("deck", "");
            activeDeckIds.clear();
            if (!deckRaw.isBlank()) {
                for (String part : deckRaw.split(",")) {
                    String trimmed = part.trim();
                    if (unlockedIds.contains(trimmed) && !activeDeckIds.contains(trimmed)) {
                        activeDeckIds.add(trimmed);
                    }
                }
            }
            if (activeDeckIds.size() < 8) {
                rebuildBestDeck();
            }
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            Path path = getSavePath();
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            props.setProperty("ether", String.valueOf(ether));
            props.setProperty("pity", String.valueOf(pityCounter));
            props.setProperty("pulls", String.valueOf(totalPulls));
            props.setProperty("snake_high", String.valueOf(snakeHighScore));
            props.setProperty("ash_wins", String.valueOf(ashWins));
            props.setProperty("daily_date", dailyDate);
            props.setProperty("base_walk_cm", String.valueOf(baselineWalkCm));
            props.setProperty("daily_talks", String.valueOf(dailyTalkCount));
            props.setProperty("daily_grimoire", String.valueOf(dailyGrimoireActionCount));
            props.setProperty("claim_walk", String.valueOf(dailyWalkClaimed));
            props.setProperty("claim_talk", String.valueOf(dailyTalkClaimed));
            props.setProperty("claim_grimoire", String.valueOf(dailyGrimoireClaimed));
            props.setProperty("unlocked", String.join(",", unlockedIds));
            props.setProperty("deck", String.join(",", activeDeckIds));
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "SoulFamiliar Grimoire & Spirit Summon Save");
            }
        } catch (Exception ignored) {
        }
    }
}
