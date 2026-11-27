package com.soulfamiliar;

import com.soulfamiliar.GrimoireState.CardDef;
import com.soulfamiliar.GrimoireState.CardRow;
import com.soulfamiliar.GrimoireState.WishResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class GrimoireScreen extends Screen {
    public enum Tab {
        ASH_CHRONICLES("ПЕПЕЛ"),
        ETHER_SNAKE("ЗМЕЙ"),
        SOUL_SUMMON("ПРИЗЫВ"),
        DECK_BUILDER("КОЛОДА"),
        DAILIES("ДЕЙЛИКИ"),
        COMMANDS("КОМАНДЫ");

        public final String label;
        Tab(String label) { this.label = label; }
    }

    private Tab currentTab = Tab.ASH_CHRONICLES;
    private int animTicks = 0;
    private String speakerName = "Игнис";
    private FamiliarMood speakerMood = FamiliarMood.MISCHIEVOUS;
    private String familiarComment = "Открой страницы Гримуара, Повелитель. Эфир течёт, а карты ждут твоего хода!";

    // --- CHRONICLES OF ASH (Card Game) State ---
    private int roundNum = 1;
    private int playerGems = 2;
    private int enemyGems = 2;
    private boolean playerPassed = false;
    private boolean enemyPassed = false;
    private boolean matchOver = false;
    private boolean awaitingDecoyReturn = false;

    private final List<CardDef> playerDeck = new ArrayList<>();
    private final List<CardDef> playerHand = new ArrayList<>();
    private final List<CardDef> enemyDeck = new ArrayList<>();
    private final List<CardDef> enemyHand = new ArrayList<>();

    private final List<CardDef> pMelee = new ArrayList<>();
    private final List<CardDef> pRanged = new ArrayList<>();
    private final List<CardDef> pSiege = new ArrayList<>();

    private final List<CardDef> eMelee = new ArrayList<>();
    private final List<CardDef> eRanged = new ArrayList<>();
    private final List<CardDef> eSiege = new ArrayList<>();

    private boolean weatherFrost = false;
    private boolean weatherFog = false;
    private boolean weatherRain = false;

    private int hoveredHandIndex = -1;

    // --- ETHER SNAKE State ---
    private static final int SNAKE_W = 22;
    private static final int SNAKE_H = 14;
    private final List<Point> snakeBody = new ArrayList<>();
    private int snakeDirX = 1;
    private int snakeDirY = 0;
    private int nextDirX = 1;
    private int nextDirY = 0;
    private Point shardPos = new Point(14, 7);
    private Point applePos = null;
    private int snakeScore = 0;
    private int snakeEarnedEther = 0;
    private boolean snakeAlive = true;
    private int snakeMoveAccumulator = 0;

    // --- SOUL SUMMON (Gacha) State ---
    private final List<WishResult> lastWishResults = new ArrayList<>();
    private int wishAnimTicks = 0;

    // --- DECK BUILDER & COMMANDS Scroll ---
    private int deckScrollRow = 0;
    private int commandScrollRow = 0;

    private static final String[][] FAMILIAR_COMMANDS = {
            {"Восстань [Имя]", "Призвать фамильяра (нужен череп в руке)"},
            {"[Имя], вселись", "Вселить духа в стойку под прицелом"},
            {"[Имя], вернись / Вернись", "Выйти из стойки / вернуть зрение себе"},
            {"Упокойся [Имя]", "Отпустить и развеять фамильяра"},
            {"[Имя], глаза / клавиша [V]", "Смотреть глазами вселённого фамильяра"},
            {"Книга в руке + «гримуар»", "Открыть Гримуар (или клавиша [K])"},
            {"[Имя], осмотрись / что видишь", "Доклад об окружении, мобах, дропе и руде"},
            {"[Имя], где [предмет/моб/руда]", "Найти ближайшую цель или сундук с вещью"},
            {"[Имя], что я вижу", "Описать блок или существо под вашим взглядом"},
            {"[Имя], общая память / находки", "Вспомнить тайны и открытые сундуки свиты"},
            {"[Имя], погуляй / на плечо", "Отпустить в свободный дозор или вернуть к плечу"},
            {"[Имя], ко мне / сторожи", "Позвать к себе или поставить на охрану"},
            {"[Имя], преследуй [цель]", "Незримо следить за мобом или игроком"},
            {"[Имя], напугай [ник]", "Подкрасться и напугать игрока перед лицом"},
            {"[Имя], поклон / укажи", "Отвесить поклон или указать рукой на цель"},
            {"[Имя], танец / колени / медитация", "Эмоции и позы стойки (ещё: аплодисменты, драма, думай)"},
            {"[Имя], в круг / в шеренгу / марш", "Построение свиты и синхронный шаг"},
            {"[Имя], замри / вольно", "Зафиксировать позу статуи или вернуть свободу"},
            {"[Имя], смени настроение", "Переключить характер (Озорной, Философ, Преданный...)"},
            {"[Имя], молчи / болтай / шутка", "Управление репликами в чате и случайной шуткой"},
            {"Доска a1 / Доска h8 / Сброс партии", "Разметка шахматной доски 8x8 и сброс фигур"},
            {"[Имя], e2 на e4 / рокировка", "Ход фигурой (добавь «бьёт» при взятии)"},
            {".f [сообщение или команда]", "Тихая команда/разговор с духом без отправки в общий чат"},
            {".f ключ [API_KEY] / статус ии", "Настройка ключа Groq/OpenRouter/Ollama и проверка ИИ"}
    };

    public GrimoireScreen() {
        this(Tab.ASH_CHRONICLES);
    }

    public GrimoireScreen(Tab initialTab) {
        super(Component.literal("Гримуар Фамильяра"));
        this.currentTab = initialTab;
    }

    @Override
    protected void init() {
        super.init();
        pickActiveFamiliarSpeaker();
        if (playerHand.isEmpty() && !matchOver) {
            startNewAshMatch();
        }
        if (snakeBody.isEmpty()) {
            resetSnake();
        }
    }

    private void pickActiveFamiliarSpeaker() {
        List<Familiar> all = new ArrayList<>(FamiliarManager.getInstance().getAll());
        if (!all.isEmpty()) {
            Familiar f = all.get(0);
            this.speakerName = f.getName();
            this.speakerMood = f.getMood();
        } else {
            this.speakerName = "Дух Гримуара";
            this.speakerMood = FamiliarMood.PHILOSOPHICAL;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        animTicks++;
        if (wishAnimTicks > 0) {
            wishAnimTicks--;
        }

        if (currentTab == Tab.ETHER_SNAKE && snakeAlive) {
            snakeMoveAccumulator++;
            int interval = Math.max(2, 4 - (snakeScore / 80));
            if (snakeMoveAccumulator >= interval) {
                snakeMoveAccumulator = 0;
                stepSnake();
            }
        }
    }

    // =========================================================================
    // CHRONICLES OF ASH LOGIC
    // =========================================================================
    private void startNewAshMatch() {
        GrimoireState state = GrimoireState.getInstance();
        state.recordGrimoireActivity();

        roundNum = 1;
        playerGems = 2;
        enemyGems = 2;
        matchOver = false;
        awaitingDecoyReturn = false;

        playerDeck.clear();
        for (String id : state.getActiveDeckIds()) {
            CardDef def = state.getCard(id);
            if (def != null) playerDeck.add(def);
        }
        while (playerDeck.size() < 10) {
            playerDeck.add(state.getCard("watchman"));
        }
        Collections.shuffle(playerDeck);

        enemyDeck.clear();
        String[] enemyPool = {
                "watchman", "pigeon", "chest_whisper", "scout_gear",
                "ash_guard", "relic_anvil", "pigeon_knight", "wisp_twin",
                "chest_mimic", "soul_vanguard", "hero_skull", "frost_rain", "ether_fog"
        };
        for (String id : enemyPool) {
            CardDef def = state.getCard(id);
            if (def != null) enemyDeck.add(def);
        }
        Collections.shuffle(enemyDeck);

        playerHand.clear();
        enemyHand.clear();
        for (int i = 0; i < 7 && !playerDeck.isEmpty(); i++) {
            playerHand.add(playerDeck.remove(0));
        }
        for (int i = 0; i < 7 && !enemyDeck.isEmpty(); i++) {
            enemyHand.add(enemyDeck.remove(0));
        }

        clearBoardForNextRound();
        setRemark("Новая партия «Хроник Пепла»! Выбери карту внизу и переиграй мою свиту.");
    }

    private void clearBoardForNextRound() {
        pMelee.clear();
        pRanged.clear();
        pSiege.clear();
        eMelee.clear();
        eRanged.clear();
        eSiege.clear();
        weatherFrost = false;
        weatherFog = false;
        weatherRain = false;
        playerPassed = false;
        enemyPassed = false;
        awaitingDecoyReturn = false;
    }

    private int calcRowPower(List<CardDef> cards, boolean weatherActive) {
        int sum = 0;
        int bondCount = 0;
        for (CardDef c : cards) {
            if ("BOND".equals(c.ability)) bondCount++;
        }
        for (CardDef c : cards) {
            if (c.hero) {
                sum += c.power;
            } else {
                int p = weatherActive ? Math.min(1, c.power) : c.power;
                if (!weatherActive && "BOND".equals(c.ability) && bondCount > 1) {
                    p *= bondCount;
                }
                sum += p;
            }
        }
        return sum;
    }

    private int getPlayerTotalPower() {
        return calcRowPower(pMelee, weatherFrost)
                + calcRowPower(pRanged, weatherFog)
                + calcRowPower(pSiege, weatherRain);
    }

    private int getEnemyTotalPower() {
        return calcRowPower(eMelee, weatherFrost)
                + calcRowPower(eRanged, weatherFog)
                + calcRowPower(eSiege, weatherRain);
    }

    private void playPlayerCard(int handIdx) {
        if (matchOver || playerPassed) return;
        if (handIdx < 0 || handIdx >= playerHand.size()) return;

        CardDef card = playerHand.get(handIdx);

        if (card.row == CardRow.DECOY) {
            if (pMelee.isEmpty() && pRanged.isEmpty() && pSiege.isEmpty()) {
                setRemark("На твоей стороне нет отрядов, чтобы подменить их чучелом!");
                playRetroSound(0.6f);
                return;
            }
            playerHand.remove(handIdx);
            awaitingDecoyReturn = true;
            setRemark("Кликни по любому своему отряду на поле, чтобы вернуть его в руку!");
            playRetroSound(1.2f);
            return;
        }

        playerHand.remove(handIdx);
        applyCardToBoard(card, true);
        playRetroSound(1.05f);

        if (playerHand.isEmpty()) {
            playerPassed = true;
            setRemark("У тебя закончились карты — автоматический пас!");
        }

        if (!enemyPassed) {
            performEnemyTurn();
        }
        checkRoundEnd();
    }

    private void applyCardToBoard(CardDef card, boolean byPlayer) {
        switch (card.row) {
            case MELEE -> (byPlayer ? pMelee : eMelee).add(card);
            case RANGED -> (byPlayer ? pRanged : eRanged).add(card);
            case SIEGE -> (byPlayer ? pSiege : eSiege).add(card);
            case SPY -> {
                if (byPlayer) {
                    eMelee.add(card);
                    for (int i = 0; i < 2 && !playerDeck.isEmpty(); i++) {
                        playerHand.add(playerDeck.remove(0));
                    }
                    setRemark("Коварный шпион! Отдал мне " + card.power + " силы и вытянул 2 карты из колоды.");
                } else {
                    pMelee.add(card);
                    for (int i = 0; i < 2 && !enemyDeck.isEmpty(); i++) {
                        enemyHand.add(enemyDeck.remove(0));
                    }
                    setRemark("Мой лазутчик проскользнул в твой ряд и принёс мне свежие карты!");
                }
            }
            case WEATHER -> {
                if ("FROST".equals(card.ability)) {
                    weatherFrost = true;
                    setRemark("Ливень Забвения накрыл Передовую! Все обычные бойцы ослаблены до 1.");
                } else if ("FOG".equals(card.ability)) {
                    weatherFog = true;
                    setRemark("Эфирный Туман окутал Дальний бой! Обычные стрелки видят лишь на 1 шаг.");
                } else if ("RAIN".equals(card.ability)) {
                    weatherRain = true;
                    setRemark("Скрип несмазанных петель парализовал Осадные машины до 1!");
                } else if ("CLEAR".equals(card.ability)) {
                    weatherFrost = false;
                    weatherFog = false;
                    weatherRain = false;
                    setRemark("Ясный Поклон Повелителя разогнал всю непогоду над полем!");
                }
            }
            case DECOY -> {
                // Handled interactively
            }
        }
    }

    private void passPlayerRound() {
        if (matchOver || playerPassed) return;
        playerPassed = true;
        playRetroSound(0.75f);
        setRemark("Ты спасовал при счёте " + getPlayerTotalPower() + " : " + getEnemyTotalPower() + ". Мой черёд решать!");
        while (!enemyPassed && !matchOver) {
            performEnemyTurn();
            if (enemyPassed) break;
        }
        checkRoundEnd();
    }

    private void performEnemyTurn() {
        if (enemyPassed || matchOver) return;

        int pScore = getPlayerTotalPower();
        int eScore = getEnemyTotalPower();

        if (enemyHand.isEmpty()) {
            enemyPassed = true;
            setRemark("Мои карты иссякли — я пасую!");
            return;
        }

        // Smart pass logic
        if (playerPassed && eScore > pScore) {
            enemyPassed = true;
            setRemark("Мне незачем тратить лишние карты — я пасую и забираю этот раунд!");
            return;
        }
        if (!playerPassed && eScore - pScore >= 13 && enemyHand.size() <= 4) {
            enemyPassed = true;
            setRemark("Мой перевес велик. Посмотрим, рискнёшь ли ты догонять — пас!");
            return;
        }
        if (playerPassed && pScore - eScore >= 16 && enemyGems == 2) {
            enemyPassed = true;
            setRemark("Хитрый ход, Повелитель. Уступлю этот раунд, сберегу силы на финал!");
            return;
        }

        // Choose best card from enemy hand
        int bestIdx = 0;
        int bestVal = -999;
        for (int i = 0; i < enemyHand.size(); i++) {
            CardDef c = enemyHand.get(i);
            int val = c.power;
            if (c.row == CardRow.WEATHER) {
                if ("FROST".equals(c.ability) && !weatherFrost && calcRowPower(pMelee, false) > calcRowPower(eMelee, false) + 3) {
                    val = 9;
                } else if ("FOG".equals(c.ability) && !weatherFog && calcRowPower(pRanged, false) > calcRowPower(eRanged, false) + 3) {
                    val = 9;
                } else {
                    val = -5;
                }
            } else if (c.row == CardRow.DECOY) {
                val = -10;
            }
            if (val > bestVal) {
                bestVal = val;
                bestIdx = i;
            }
        }

        CardDef chosen = enemyHand.remove(bestIdx);
        applyCardToBoard(chosen, false);
        if (chosen.row != CardRow.WEATHER && chosen.row != CardRow.SPY) {
            setRemark("Я разыгрываю «" + chosen.name + "» (" + chosen.power + " силы)!");
        }
    }

    private void checkRoundEnd() {
        if (!playerPassed || !enemyPassed || matchOver) return;

        int pScore = getPlayerTotalPower();
        int eScore = getEnemyTotalPower();

        if (pScore > eScore) {
            enemyGems--;
            GrimoireState.getInstance().addEther(60);
            setRemark("Раунд " + roundNum + " за тобой (" + pScore + " : " + eScore + ")! +60 ✦ Эфира.");
            playRetroSound(1.35f);
        } else if (eScore > pScore) {
            playerGems--;
            setRemark("Раунд " + roundNum + " достаётся мне (" + pScore + " : " + eScore + ")! Один твой кристалл погас.");
            playRetroSound(0.65f);
        } else {
            playerGems--;
            enemyGems--;
            setRemark("Ничья в раунде (" + pScore + " : " + eScore + ")! Оба теряем по кристаллу.");
            playRetroSound(0.8f);
        }

        if (playerGems <= 0 || enemyGems <= 0) {
            matchOver = true;
            if (enemyGems <= 0 && playerGems > 0) {
                GrimoireState.getInstance().recordAshMatchWin();
                setRemark("ПОБЕДА В ПАРТИИ! Твоя стратегия безупречна, Повелитель. Награда: +160 ✦ Эфира!");
                playRetroSound(1.5f);
            } else if (playerGems <= 0 && enemyGems > 0) {
                setRemark("Партия за мной! Но даже поражение питает Гримуар — жми «НОВАЯ ПАРТИЯ» для реванша.");
            } else {
                setRemark("Боевая ничья в Гримуаре! Оба кристалла разбиты одновременно.");
            }
            return;
        }

        roundNum++;
        clearBoardForNextRound();
        if (!playerDeck.isEmpty()) playerHand.add(playerDeck.remove(0));
        if (!enemyDeck.isEmpty()) enemyHand.add(enemyDeck.remove(0));
    }

    // =========================================================================
    // ETHER SNAKE LOGIC
    // =========================================================================
    private void resetSnake() {
        snakeBody.clear();
        snakeBody.add(new Point(6, 7));
        snakeBody.add(new Point(5, 7));
        snakeBody.add(new Point(4, 7));
        snakeDirX = 1;
        snakeDirY = 0;
        nextDirX = 1;
        nextDirY = 0;
        snakeScore = 0;
        snakeEarnedEther = 0;
        snakeAlive = true;
        snakeMoveAccumulator = 0;
        spawnSnakeFood();
    }

    private void spawnSnakeFood() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int tries = 0; tries < 80; tries++) {
            int fx = rng.nextInt(SNAKE_W);
            int fy = rng.nextInt(SNAKE_H);
            Point candidate = new Point(fx, fy);
            if (!snakeBody.contains(candidate)) {
                shardPos = candidate;
                break;
            }
        }
        if (rng.nextDouble() < 0.35 && applePos == null) {
            for (int tries = 0; tries < 50; tries++) {
                int ax = rng.nextInt(SNAKE_W);
                int ay = rng.nextInt(SNAKE_H);
                Point cand = new Point(ax, ay);
                if (!snakeBody.contains(cand) && !cand.equals(shardPos)) {
                    applePos = cand;
                    break;
                }
            }
        }
    }

    private void stepSnake() {
        if (!snakeAlive || snakeBody.isEmpty()) return;
        snakeDirX = nextDirX;
        snakeDirY = nextDirY;

        Point head = snakeBody.get(0);
        int nx = head.x + snakeDirX;
        int ny = head.y + snakeDirY;

        if (nx < 0 || nx >= SNAKE_W || ny < 0 || ny >= SNAKE_H) {
            failSnake("Стена обсидиана оказалась крепче эфирного хвоста!");
            return;
        }
        Point nextHead = new Point(nx, ny);
        if (snakeBody.contains(nextHead)) {
            failSnake("Эфирный змей укусил сам себя!");
            return;
        }

        snakeBody.add(0, nextHead);

        if (nextHead.equals(shardPos)) {
            snakeScore += 10;
            snakeEarnedEther += 10;
            GrimoireState.getInstance().addEther(10);
            GrimoireState.getInstance().recordSnakeScore(snakeScore);
            playRetroSound(1.3f);
            spawnSnakeFood();
            if (snakeScore % 50 == 0) {
                setRemark("Отличный вираж! Уже +" + snakeEarnedEther + " ✦ Эфира собрано за забег.");
            }
        } else if (applePos != null && nextHead.equals(applePos)) {
            snakeScore += 30;
            snakeEarnedEther += 30;
            GrimoireState.getInstance().addEther(30);
            GrimoireState.getInstance().recordSnakeScore(snakeScore);
            applePos = null;
            playRetroSound(1.5f);
            setRemark("Золотое яблоко душ! +30 ✦ Эфира в казну!");
        } else {
            snakeBody.remove(snakeBody.size() - 1);
        }
    }

    private void failSnake(String reason) {
        snakeAlive = false;
        GrimoireState.getInstance().recordSnakeScore(snakeScore);
        GrimoireState.getInstance().recordGrimoireActivity();
        playRetroSound(0.55f);
        setRemark(reason + " Счёт: " + snakeScore + " (добыто +" + snakeEarnedEther + " ✦). Жми R или кнопку заново!");
    }

    private void setRemark(String text) {
        this.familiarComment = text;
    }

    private void playRetroSound(float pitch) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.level != null) {
            client.level.playLocalSound(
                    client.player.getX(), client.player.getY(), client.player.getZ(),
                    SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.55f, pitch, false
            );
        }
    }

    // =========================================================================
    // RENDERING (Pixel-Art CRT Terminal Aesthetic)
    // =========================================================================
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Custom dark CRT backdrop is drawn in extractRenderState; skip vanilla blur to avoid "Can only blur once per frame"
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Dark vignette backdrop
        g.fill(0, 0, this.width, this.height, 0xCC06070A);

        int boxW = Math.min(460, this.width - 16);
        int boxH = Math.min(312, this.height - 14);
        int left = (this.width - boxW) / 2;
        int top = (this.height - boxH) / 2;
        int right = left + boxW;
        int bottom = top + boxH;

        // Outer CRT bezel & double gold/emerald pixel border
        g.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF1B1E24);
        drawPixelBorder(g, left - 2, top - 2, boxW + 4, boxH + 4, 0xFFD4AF37);
        drawPixelBorder(g, left, top, boxW, boxH, 0xFF3E4A3D);
        g.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFF0B0E11);

        // Subtle CRT scanlines
        for (int sy = top + 2; sy < bottom - 2; sy += 3) {
            g.fill(left + 2, sy, right - 2, sy + 1, 0x1600FF99);
        }

        GrimoireState state = GrimoireState.getInstance();
        int activeCount = FamiliarManager.getInstance().getAll().size();
        int passiveRate = state.getPassiveEtherPerMinute(activeCount);

        // --- TOP HEADER BAR ---
        g.fill(left + 2, top + 2, right - 2, top + 22, 0xFF13181E);
        g.text(this.font, "✦ ГРИМУАР ФАМИЛЬЯРА", left + 8, top + 8, 0xFFFFD700, true);

        String etherText = "ЭФИР: " + state.getEther() + " ✦ (+" + passiveRate + "/мин | Духов: " + activeCount + ")";
        int etherW = this.font.width(etherText);
        g.text(this.font, etherText, right - etherW - 26, top + 8, 0xFF4DD0E1, true);

        // Close [X] button
        int closeX = right - 18;
        int closeY = top + 5;
        boolean hoverClose = isInside(mouseX, mouseY, closeX, closeY, 14, 14);
        g.fill(closeX, closeY, closeX + 14, closeY + 14, hoverClose ? 0xFFB71C1C : 0xFF2B1A1A);
        drawPixelBorder(g, closeX, closeY, 14, 14, 0xFFE57373);
        g.text(this.font, "X", closeX + 4, closeY + 3, 0xFFFFFFFF, false);

        // --- NAVIGATION TABS ---
        Tab[] tabs = Tab.values();
        int tabAreaY = top + 24;
        int tabW = (boxW - 12) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int tx = left + 6 + i * tabW;
            boolean active = (t == currentTab);
            boolean hov = isInside(mouseX, mouseY, tx, tabAreaY, tabW - 2, 16);
            int bg = active ? 0xFF263238 : (hov ? 0xFF1A2228 : 0xFF101418);
            int border = active ? 0xFFFFD700 : 0xFF455A64;
            g.fill(tx, tabAreaY, tx + tabW - 2, tabAreaY + 16, bg);
            drawPixelBorder(g, tx, tabAreaY, tabW - 2, 16, border);

            String lbl = t.label;
            if (t == Tab.DAILIES && (state.isDailyWalkReady() || state.isDailyTalkReady() || state.isDailyGrimoireReady())) {
                lbl = "!" + lbl;
            }
            int lw = this.font.width(lbl);
            g.text(this.font, lbl, tx + (tabW - 2 - lw) / 2, tabAreaY + 4, active ? 0xFFFFE082 : 0xFFB0BEC5, false);
        }

        // --- FAMILIAR DIALOGUE STRIP ---
        int chatY = top + 43;
        g.fill(left + 6, chatY, right - 6, chatY + 22, 0xFF12171D);
        drawPixelBorder(g, left + 6, chatY, boxW - 12, 22, 0xFF2E4045);
        // Pixel skull portrait box
        g.fill(left + 9, chatY + 3, left + 25, chatY + 19, 0xFF1C272C);
        drawPixelSkullIcon(g, left + 11, chatY + 5, 0xFF4DD0E1);

        String speakerTag = "[" + speakerName + "]: ";
        g.text(this.font, speakerTag, left + 29, chatY + 3, 0xFF4DD0E1, false);
        int tagW = this.font.width(speakerTag);
        String clippedMsg = trimToWidth(familiarComment, boxW - 42 - tagW);
        g.text(this.font, clippedMsg, left + 29 + tagW, chatY + 3, 0xFFE0F2F1, false);

        // --- TAB CONTENT AREA ---
        int contentTop = top + 68;
        int contentBottom = bottom - 6;
        switch (currentTab) {
            case ASH_CHRONICLES -> renderAshChronicles(g, left + 6, contentTop, right - 6, contentBottom, mouseX, mouseY);
            case ETHER_SNAKE -> renderEtherSnake(g, left + 6, contentTop, right - 6, contentBottom, mouseX, mouseY);
            case SOUL_SUMMON -> renderSoulSummon(g, left + 6, contentTop, right - 6, contentBottom, mouseX, mouseY);
            case DECK_BUILDER -> renderDeckBuilder(g, left + 6, contentTop, right - 6, contentBottom, mouseX, mouseY);
            case DAILIES -> renderDailies(g, left + 6, contentTop, right - 6, contentBottom, mouseX, mouseY);
            case COMMANDS -> renderCommandsTab(g, left + 6, contentTop, right - 6, contentBottom);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    // =========================================================================
    // TAB 0: CHRONICLES OF ASH RENDER
    // =========================================================================
    private void renderAshChronicles(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        int w = x2 - x1;
        int pTotal = getPlayerTotalPower();
        int eTotal = getEnemyTotalPower();

        // Status bar: round, gems, scores, Pass / Restart buttons
        g.fill(x1, y1, x2, y1 + 18, 0xFF141920);
        String status = "Р." + roundNum
                + " | Враг:" + gemsStr(enemyGems) + " [" + eTotal + "]"
                + " vs Ты:" + gemsStr(playerGems) + " [" + pTotal + "]";
        g.text(this.font, status, x1 + 5, y1 + 5, 0xFFFFE082, false);

        // Pass button
        int passX = x2 - 118;
        boolean hovPass = isInside(mouseX, mouseY, passX, y1 + 2, 48, 14);
        g.fill(passX, y1 + 2, passX + 48, y1 + 16, playerPassed ? 0xFF263238 : (hovPass ? 0xFF8E24AA : 0xFF4A148C));
        drawPixelBorder(g, passX, y1 + 2, 48, 14, 0xFFCE93D8);
        g.text(this.font, playerPassed ? "ПАС!" : "ПАС", passX + 12, y1 + 5, 0xFFFFFFFF, false);

        // New Match button
        int newX = x2 - 66;
        boolean hovNew = isInside(mouseX, mouseY, newX, y1 + 2, 62, 14);
        g.fill(newX, y1 + 2, newX + 62, y1 + 16, hovNew ? 0xFF2E7D32 : 0xFF1B5E20);
        drawPixelBorder(g, newX, y1 + 2, 62, 14, 0xFF81C784);
        g.text(this.font, "ЗАНОВО", newX + 11, y1 + 5, 0xFFFFFFFF, false);

        // Determine hovered card target row highlight
        CardDef hoveredCard = (hoveredHandIndex >= 0 && hoveredHandIndex < playerHand.size()) ? playerHand.get(hoveredHandIndex) : null;

        int boardY = y1 + 20;
        int rowH = 19;
        // Enemy rows (Siege -> Ranged -> Melee)
        renderBoardRow(g, x1, boardY, w, rowH, "ВРАГ ОСА", eSiege, weatherRain, false, false, mouseX, mouseY);
        renderBoardRow(g, x1, boardY + rowH + 1, w, rowH, "ВРАГ ДАЛ", eRanged, weatherFog, false, false, mouseX, mouseY);
        boolean hlEnemyMelee = hoveredCard != null && hoveredCard.row == CardRow.SPY;
        renderBoardRow(g, x1, boardY + (rowH + 1) * 2, w, rowH, "ВРАГ ПЕР", eMelee, weatherFrost, hlEnemyMelee, false, mouseX, mouseY);

        // Weather divider strip
        int divY = boardY + (rowH + 1) * 3 + 1;
        boolean hlWeather = hoveredCard != null && hoveredCard.row == CardRow.WEATHER;
        g.fill(x1, divY, x2, divY + 13, hlWeather ? 0xFF1A237E : 0xFF0E1318);
        drawPixelBorder(g, x1, divY, w, 13, hlWeather ? 0xFFFFD700 : 0xFF263238);
        String wLabel = "ПОГОДА: "
                + (weatherFrost ? "[ЛИВЕНЬ:ПЕР] " : "")
                + (weatherFog ? "[ТУМАН:ДАЛ] " : "")
                + (weatherRain ? "[СКРИП:ОСА] " : "")
                + (!weatherFrost && !weatherFog && !weatherRain ? "ЯСНО" : "");
        g.text(this.font, wLabel, x1 + 6, divY + 3, 0xFF90CAF9, false);

        // Player rows (Melee -> Ranged -> Siege)
        int pStart = divY + 15;
        boolean hlPMelee = hoveredCard != null && hoveredCard.row == CardRow.MELEE;
        boolean hlPRanged = hoveredCard != null && hoveredCard.row == CardRow.RANGED;
        boolean hlPSiege = hoveredCard != null && hoveredCard.row == CardRow.SIEGE;

        renderBoardRow(g, x1, pStart, w, rowH, "ТВОЙ ПЕР", pMelee, weatherFrost, hlPMelee || awaitingDecoyReturn, true, mouseX, mouseY);
        renderBoardRow(g, x1, pStart + rowH + 1, w, rowH, "ТВОЙ ДАЛ", pRanged, weatherFog, hlPRanged || awaitingDecoyReturn, true, mouseX, mouseY);
        renderBoardRow(g, x1, pStart + (rowH + 1) * 2, w, rowH, "ТВОЙ ОСА", pSiege, weatherRain, hlPSiege || awaitingDecoyReturn, true, mouseX, mouseY);

        // Hover hint bar
        int hintY = pStart + (rowH + 1) * 3 + 2;
        g.fill(x1, hintY, x2, hintY + 13, 0xFF14181E);
        if (awaitingDecoyReturn) {
            g.text(this.font, "ЧУЧЕЛО АКТИВНО: нажми на любую СВОЮ карту на поле выше, чтобы забрать её в руку!", x1 + 4, hintY + 3, 0xFFA5D6A7, false);
        } else if (hoveredCard != null) {
            String hStr = "[" + hoveredCard.row.badge + "] " + hoveredCard.name + " -> " + hoveredCard.row.hint + " | " + hoveredCard.desc;
            g.text(this.font, trimToWidth(hStr, w - 8), x1 + 4, hintY + 3, 0xFFFFE082, false);
        } else {
            g.text(this.font, "Наведи на карту в руке для подсветки ряда • Клик по карте разыгрывает её", x1 + 4, hintY + 3, 0xFF78909C, false);
        }

        // Player Hand Cards at bottom
        int handY = hintY + 16;
        int cardH = Math.max(46, y2 - handY - 2);
        hoveredHandIndex = -1;
        if (playerHand.isEmpty()) {
            g.text(this.font, "Рука пуста — нажми «ПАС» или «ЗАНОВО»", x1 + 10, handY + 16, 0xFF90A4AE, false);
            return;
        }

        int count = playerHand.size();
        int gap = 3;
        int cardW = Math.min(60, (w - (count - 1) * gap) / count);
        int startX = x1 + (w - (count * cardW + (count - 1) * gap)) / 2;

        for (int i = 0; i < count; i++) {
            CardDef c = playerHand.get(i);
            int cx = startX + i * (cardW + gap);
            boolean hov = isInside(mouseX, mouseY, cx, handY, cardW, cardH);
            if (hov) hoveredHandIndex = i;
            int lift = hov ? -4 : 0;
            renderPixelCard(g, c, cx, handY + lift, cardW, cardH, hov);
        }
    }

    private void renderBoardRow(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, List<CardDef> cards, boolean weathered, boolean highlight, boolean isPlayerSide, int mouseX, int mouseY) {
        int bg = weathered ? 0xFF132230 : (isPlayerSide ? 0xFF131918 : 0xFF1A1417);
        if (highlight && (animTicks / 6) % 2 == 0) {
            bg = 0xFF283529;
        }
        g.fill(x, y, x + w, y + h, bg);
        drawPixelBorder(g, x, y, w, h, highlight ? 0xFFFFD700 : 0xFF2B353D);

        int rowSum = calcRowPower(cards, weathered);
        String prefix = label + " [" + rowSum + "]";
        g.text(this.font, prefix, x + 4, y + 5, weathered ? 0xFF81D4FA : 0xFFB0BEC5, false);

        int chipX = x + 76;
        for (int i = 0; i < cards.size(); i++) {
            CardDef c = cards.get(i);
            int effPow = c.hero ? c.power : (weathered ? Math.min(1, c.power) : c.power);
            String chip = (c.hero ? "★" : "") + effPow + " " + shortName(c.name, 7);
            int cw = this.font.width(chip) + 6;
            if (chipX + cw > x + w - 4) break;
            boolean hovChip = isPlayerSide && awaitingDecoyReturn && isInside(mouseX, mouseY, chipX, y + 2, cw, h - 4);
            g.fill(chipX, y + 2, chipX + cw, y + h - 2, hovChip ? 0xFF388E3C : (c.hero ? 0xFF3E2E12 : 0xFF1F2933));
            drawPixelBorder(g, chipX, y + 2, cw, h - 4, c.hero ? 0xFFFFD700 : c.row.color);
            g.text(this.font, chip, chipX + 3, y + 5, c.hero ? 0xFFFFE082 : 0xFFFFFFFF, false);
            chipX += cw + 3;
        }
    }

    private void renderPixelCard(GuiGraphicsExtractor g, CardDef c, int x, int y, int w, int h, boolean hovered) {
        int bg = c.hero ? 0xFF261E10 : 0xFF151B22;
        g.fill(x, y, x + w, y + h, bg);
        drawPixelBorder(g, x, y, w, h, hovered ? 0xFFFFFFFF : (c.hero ? 0xFFFFD700 : c.row.color));

        // Top row badge strip
        g.fill(x + 1, y + 1, x + w - 1, y + 11, c.row.color);
        String topBadge = (c.hero ? "★" : "") + c.power + " " + c.row.badge;
        g.text(this.font, trimToWidth(topBadge, w - 4), x + 3, y + 2, 0xFF0A0C0E, false);

        // Center pixel emblem
        int iconCx = x + w / 2 - 6;
        int iconCy = y + 14;
        drawCardPixelGlyph(g, c.iconKind, iconCx, iconCy, c.accentColor);

        // Card short title at bottom
        String title = shortName(c.name, Math.max(5, w / 6));
        g.text(this.font, title, x + 3, y + h - 10, c.hero ? 0xFFFFE082 : 0xFFE0E0E0, false);
    }

    // =========================================================================
    // TAB 1: ETHER SNAKE RENDER
    // =========================================================================
    private void renderEtherSnake(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        int w = x2 - x1;
        GrimoireState state = GrimoireState.getInstance();

        g.fill(x1, y1, x2, y1 + 18, 0xFF141920);
        String hud = "СЧЁТ: " + snakeScore + " | РЕКОРД: " + state.getSnakeHighScore() + " | ДОБЫТО: +" + snakeEarnedEther + " ✦";
        g.text(this.font, hud, x1 + 6, y1 + 5, 0xFF4DD0E1, false);

        int btnX = x2 - 68;
        boolean hov = isInside(mouseX, mouseY, btnX, y1 + 2, 64, 14);
        g.fill(btnX, y1 + 2, btnX + 64, y1 + 16, hov ? 0xFF00838F : 0xFF006064);
        drawPixelBorder(g, btnX, y1 + 2, 64, 14, 0xFF80DEEA);
        g.text(this.font, "ЗАНОВО [R]", btnX + 5, y1 + 5, 0xFFFFFFFF, false);

        int cell = Math.min((w - 16) / SNAKE_W, (y2 - y1 - 34) / SNAKE_H);
        cell = Math.max(8, cell);
        int gridW = SNAKE_W * cell;
        int gridH = SNAKE_H * cell;
        int gx = x1 + (w - gridW) / 2;
        int gy = y1 + 22;

        g.fill(gx - 2, gy - 2, gx + gridW + 2, gy + gridH + 2, 0xFF080B0E);
        drawPixelBorder(g, gx - 2, gy - 2, gridW + 4, gridH + 4, 0xFF26A69A);

        // Checkerboard subtle grid
        for (int cy = 0; cy < SNAKE_H; cy++) {
            for (int cx = 0; cx < SNAKE_W; cx++) {
                if ((cx + cy) % 2 == 0) {
                    g.fill(gx + cx * cell, gy + cy * cell, gx + (cx + 1) * cell, gy + (cy + 1) * cell, 0xFF0E1419);
                }
            }
        }

        // Ether shard (+10)
        if (shardPos != null) {
            int sx = gx + shardPos.x * cell + 2;
            int sy = gy + shardPos.y * cell + 2;
            g.fill(sx, sy, sx + cell - 4, sy + cell - 4, 0xFF00E5FF);
        }

        // Golden apple (+30)
        if (applePos != null) {
            int ax = gx + applePos.x * cell + 2;
            int ay = gy + applePos.y * cell + 2;
            g.fill(ax, ay, ax + cell - 4, ay + cell - 4, 0xFFFFD700);
        }

        // Snake segments
        for (int i = 0; i < snakeBody.size(); i++) {
            Point p = snakeBody.get(i);
            int px = gx + p.x * cell + 1;
            int py = gy + p.y * cell + 1;
            int col = (i == 0) ? 0xFFFFE082 : (i % 2 == 0 ? 0xFF66BB6A : 0xFF43A047);
            g.fill(px, py, px + cell - 2, py + cell - 2, col);
        }

        g.text(this.font, "Управление: WASD или Стрелки • Бирюза = +10 ✦ • Золото = +30 ✦", x1 + 8, gy + gridH + 4, 0xFF90A4AE, false);
    }

    // =========================================================================
    // TAB 2: SOUL SUMMON (GACHA) RENDER
    // =========================================================================
    private void renderSoulSummon(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        int w = x2 - x1;
        GrimoireState state = GrimoireState.getInstance();

        // Banner box
        g.fill(x1, y1, x2, y1 + 48, 0xFF171324);
        drawPixelBorder(g, x1, y1, w, 48, 0xFFFFD700);
        g.text(this.font, "★ АЛТАРЬ ПРИЗЫВА ДУШ: ЗОВ ПОВЕЛИТЕЛЯ ★", x1 + 10, y1 + 6, 0xFFFFD700, true);
        g.text(this.font, "Гарант 5★ Героя каждые 10 круток! Прогресс гаранта: " + state.getPityCounter() + "/10", x1 + 10, y1 + 19, 0xFFCE93D8, false);
        g.text(this.font, "Выбитые карты сразу открываются для «Хроник Пепла» (дубликаты возвращают Эфир).", x1 + 10, y1 + 32, 0xFFB0BEC5, false);

        // 1x and 10x Summon buttons
        int btnY = y1 + 54;
        int b1W = 150;
        int b10W = 166;
        int b1X = x1 + (w - (b1W + b10W + 14)) / 2;
        int b10X = b1X + b1W + 14;

        boolean can1 = state.getEther() >= 160;
        boolean hov1 = can1 && isInside(mouseX, mouseY, b1X, btnY, b1W, 22);
        g.fill(b1X, btnY, b1X + b1W, btnY + 22, can1 ? (hov1 ? 0xFF00838F : 0xFF006064) : 0xFF1C2226);
        drawPixelBorder(g, b1X, btnY, b1W, 22, can1 ? 0xFF4DD0E1 : 0xFF455A64);
        g.text(this.font, "ПРИЗЫВ x1 (160 ✦)", b1X + 20, btnY + 7, can1 ? 0xFFFFFFFF : 0xFF78909C, false);

        boolean can10 = state.getEther() >= 1600;
        boolean hov10 = can10 && isInside(mouseX, mouseY, b10X, btnY, b10W, 22);
        g.fill(b10X, btnY, b10X + b10W, btnY + 22, can10 ? (hov10 ? 0xFFFF8F00 : 0xFFE65100) : 0xFF1C2226);
        drawPixelBorder(g, b10X, btnY, b10W, 22, can10 ? 0xFFFFD700 : 0xFF455A64);
        g.text(this.font, "ПРИЗЫВ x10 (1600 ✦)", b10X + 20, btnY + 7, can10 ? 0xFFFFFFFF : 0xFF78909C, false);

        // Results area
        int resY = btnY + 28;
        g.fill(x1, resY, x2, y2, 0xFF0E1217);
        drawPixelBorder(g, x1, resY, w, y2 - resY, 0xFF37474F);

        if (lastWishResults.isEmpty()) {
            g.text(this.font, "Соверши призыв, чтобы призвать новых духов и героев в свою колоду!", x1 + 14, resY + 18, 0xFF90A4AE, false);
            g.text(this.font, "Совет: Эфир пассивно добывается призванными фамильярами в мире и в мини-играх.", x1 + 14, resY + 34, 0xFF78909C, false);
            return;
        }

        int cols = Math.min(5, lastWishResults.size());
        int cw = (w - 16 - (cols - 1) * 4) / cols;
        int ch = 48;
        for (int i = 0; i < lastWishResults.size(); i++) {
            WishResult wr = lastWishResults.get(i);
            int col = i % cols;
            int row = i / cols;
            int cx = x1 + 8 + col * (cw + 4);
            int cy = resY + 6 + row * (ch + 4);
            if (cy + ch > y2 - 2) break;

            int rCol = wr.card.rarity == 5 ? 0xFFFFD700 : (wr.card.rarity == 4 ? 0xFFCE93D8 : 0xFF4FC3F7);
            g.fill(cx, cy, cx + cw, cy + ch, wr.card.rarity == 5 ? 0xFF2B210B : 0xFF161D24);
            drawPixelBorder(g, cx, cy, cw, ch, rCol);

            String stars = "★".repeat(wr.card.rarity);
            g.text(this.font, stars, cx + 4, cy + 4, rCol, false);
            g.text(this.font, trimToWidth(wr.card.name, cw - 6), cx + 4, cy + 16, 0xFFFFFFFF, false);
            String badge = wr.isNew ? "НОВАЯ!" : ("+" + wr.shardRefund + " ✦");
            g.text(this.font, badge, cx + 4, cy + 32, wr.isNew ? 0xFF69F0AE : 0xFF80DEEA, false);
        }
    }

    // =========================================================================
    // TAB 3: DECK BUILDER & CATALOG RENDER
    // =========================================================================
    private void renderDeckBuilder(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        int w = x2 - x1;
        GrimoireState state = GrimoireState.getInstance();
        List<CardDef> all = state.getAllCatalogCards();

        g.fill(x1, y1, x2, y1 + 18, 0xFF141920);
        String header = "КОЛОДА: " + state.getActiveDeckIds().size() + "/12 карт | Открыто: " + state.getUnlockedIds().size() + "/" + all.size();
        g.text(this.font, header, x1 + 6, y1 + 5, 0xFFFFE082, false);

        int autoX = x2 - 96;
        boolean hovAuto = isInside(mouseX, mouseY, autoX, y1 + 2, 92, 14);
        g.fill(autoX, y1 + 2, autoX + 92, y1 + 16, hovAuto ? 0xFF2E7D32 : 0xFF1B5E20);
        drawPixelBorder(g, autoX, y1 + 2, 92, 14, 0xFF81C784);
        g.text(this.font, "АВТО-СБОРКА", autoX + 10, y1 + 5, 0xFFFFFFFF, false);

        int listY = y1 + 22;
        int itemH = 18;
        int maxRows = Math.max(1, (y2 - listY - 14) / itemH);
        int maxScroll = Math.max(0, all.size() - maxRows);
        if (deckScrollRow > maxScroll) deckScrollRow = maxScroll;

        for (int r = 0; r < maxRows; r++) {
            int idx = deckScrollRow + r;
            if (idx >= all.size()) break;
            CardDef c = all.get(idx);
            int ry = listY + r * itemH;
            boolean unlocked = state.isUnlocked(c.id);
            boolean inDeck = state.isInActiveDeck(c.id);
            boolean hov = unlocked && isInside(mouseX, mouseY, x1, ry, w, itemH - 2);

            int bg = !unlocked ? 0xFF0E1115 : (inDeck ? 0xFF192B24 : (hov ? 0xFF1C242C : 0xFF13181E));
            g.fill(x1, ry, x2, ry + itemH - 2, bg);
            drawPixelBorder(g, x1, ry, w, itemH - 2, !unlocked ? 0xFF263238 : (inDeck ? 0xFF66BB6A : 0xFF37474F));

            String statusTag = !unlocked ? "[ЗАКРЫТО]" : (inDeck ? "[В КОЛОДЕ]" : "[РЕЗЕРВ]");
            int tagCol = !unlocked ? 0xFF546E7A : (inDeck ? 0xFF69F0AE : 0xFF90A4AE);
            g.text(this.font, statusTag, x1 + 4, ry + 4, tagCol, false);

            String line = "★".repeat(c.rarity) + " [" + c.row.badge + "|" + c.power + "] " + c.name + " — " + c.desc;
            g.text(this.font, trimToWidth(line, w - 76), x1 + 72, ry + 4, unlocked ? 0xFFECEFF1 : 0xFF607D8B, false);
        }

        g.text(this.font, "Клик по открытой карте добавляет/убирает её из колоды (8..14 карт) • Колёсико мыши: прокрутка", x1 + 4, y2 - 11, 0xFF78909C, false);
    }

    // =========================================================================
    // TAB 4: DAILIES & PASSIVE ETHER RENDER
    // =========================================================================
    private void renderDailies(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        int w = x2 - x1;
        GrimoireState state = GrimoireState.getInstance();
        int activeCount = FamiliarManager.getInstance().getAll().size();
        int rate = state.getPassiveEtherPerMinute(activeCount);

        g.fill(x1, y1, x2, y1 + 34, 0xFF121C22);
        drawPixelBorder(g, x1, y1, w, 34, 0xFF4DD0E1);
        g.text(this.font, "ПАССИВНАЯ ДОБЫЧА ЭФИРА: +" + rate + " ✦/мин (призвано духов: " + activeCount + ")", x1 + 8, y1 + 6, 0xFF4DD0E1, false);
        g.text(this.font, "Чем больше фамильяров в вашей свите (до 6), тем быстрее наполняется чаша Эфира!", x1 + 8, y1 + 19, 0xFFB0BEC5, false);

        int qY = y1 + 40;
        renderDailyRow(g, x1, qY, w, 34,
                "1. Дозорный обход: пройти 250 блоков по миру",
                "Прогресс (статистика клиента): " + state.getDailyWalkProgress() + " / 250 бл.",
                100, state.isDailyWalkReady(), state.isDailyWalkClaimed(), mouseX, mouseY);

        renderDailyRow(g, x1, qY + 38, w, 34,
                "2. Совет с духами: пообщаться с фамильярами 3 раза",
                "Прогресс бесед за сегодня: " + state.getDailyTalkProgress() + " / 3",
                80, state.isDailyTalkReady(), state.isDailyTalkClaimed(), mouseX, mouseY);

        renderDailyRow(g, x1, qY + 76, w, 34,
                "3. Испытание Гримуара: сыграть в мини-игры 2 раза",
                "Прогресс партий/призывов: " + state.getDailyGrimoireProgress() + " / 2",
                120, state.isDailyGrimoireReady(), state.isDailyGrimoireClaimed(), mouseX, mouseY);
    }

    private void renderDailyRow(GuiGraphicsExtractor g, int x, int y, int w, int h, String title, String sub, int reward, boolean ready, boolean claimed, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, claimed ? 0xFF101614 : 0xFF151B22);
        drawPixelBorder(g, x, y, w, h, ready ? 0xFFFFD700 : (claimed ? 0xFF2E7D32 : 0xFF37474F));

        g.text(this.font, title, x + 8, y + 6, 0xFFFFFFFF, false);
        g.text(this.font, sub, x + 8, y + 19, 0xFF90A4AE, false);

        int bx = x + w - 96;
        int by = y + 8;
        boolean hov = ready && isInside(mouseX, mouseY, bx, by, 88, 18);
        int bBg = claimed ? 0xFF1B3320 : (ready ? (hov ? 0xFFFFA000 : 0xFFE65100) : 0xFF1E262E);
        g.fill(bx, by, bx + 88, by + 18, bBg);
        drawPixelBorder(g, bx, by, 88, 18, ready ? 0xFFFFD700 : 0xFF455A64);
        String bLbl = claimed ? "ПОЛУЧЕНО" : (ready ? "ЗАБРАТЬ +" + reward + "✦" : "+" + reward + " ✦");
        g.text(this.font, bLbl, bx + 8, by + 5, ready ? 0xFFFFFFFF : 0xFF78909C, false);
    }


    // =========================================================================
    // TAB 5: COMMANDS REFERENCE RENDER
    // =========================================================================
    private void renderCommandsTab(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        int w = x2 - x1;
        g.fill(x1, y1, x2, y1 + 18, 0xFF141920);
        g.text(this.font, "СВИТОК КОМАНД ФАМИЛЬЯРОВ (" + FAMILIAR_COMMANDS.length + " приказов) • Колёсико: прокрутка", x1 + 6, y1 + 5, 0xFFFFE082, false);

        int listY = y1 + 21;
        int itemH = 19;
        int maxRows = Math.max(1, (y2 - listY - 12) / itemH);
        int maxScroll = Math.max(0, FAMILIAR_COMMANDS.length - maxRows);
        if (commandScrollRow > maxScroll) commandScrollRow = maxScroll;

        for (int r = 0; r < maxRows; r++) {
            int idx = commandScrollRow + r;
            if (idx >= FAMILIAR_COMMANDS.length) break;
            String[] entry = FAMILIAR_COMMANDS[idx];
            int ry = listY + r * itemH;

            g.fill(x1, ry, x2, ry + itemH - 2, (idx % 2 == 0) ? 0xFF12171D : 0xFF161C23);
            drawPixelBorder(g, x1, ry, w, itemH - 2, 0xFF2B353D);

            String cmd = entry[0];
            String desc = entry[1];
            int cmdCol = cmd.startsWith(".f") ? 0xFFCE93D8 : 0xFF4DD0E1;
            g.text(this.font, cmd, x1 + 5, ry + 4, cmdCol, false);
            int cmdW = this.font.width(cmd) + 12;
            if (cmdW < w - 20) {
                g.text(this.font, "— " + trimToWidth(desc, w - cmdW - 14), x1 + cmdW, ry + 4, 0xFFCFD8DC, false);
            }
        }

        String scrollInfo = "Показаны " + (commandScrollRow + 1) + "-" + Math.min(FAMILIAR_COMMANDS.length, commandScrollRow + maxRows) + " из " + FAMILIAR_COMMANDS.length + " • Можно обращаться по имени или «Все, ...»";
        g.text(this.font, scrollInfo, x1 + 5, y2 - 10, 0xFF78909C, false);
    }

    // =========================================================================
    // INPUT HANDLING
    // =========================================================================
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button != 0) return super.mouseClicked(event, doubleClick);

        int boxW = Math.min(460, this.width - 16);
        int boxH = Math.min(312, this.height - 14);
        int left = (this.width - boxW) / 2;
        int top = (this.height - boxH) / 2;
        int right = left + boxW;
        int bottom = top + boxH;

        // Close [X]
        if (isInside(mouseX, mouseY, right - 18, top + 5, 14, 14)) {
            this.onClose();
            return true;
        }

        // Tabs
        Tab[] tabs = Tab.values();
        int tabAreaY = top + 24;
        int tabW = (boxW - 12) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + 6 + i * tabW;
            if (isInside(mouseX, mouseY, tx, tabAreaY, tabW - 2, 16)) {
                currentTab = tabs[i];
                playRetroSound(1.1f);
                return true;
            }
        }

        int x1 = left + 6;
        int y1 = top + 68;
        int x2 = right - 6;
        int y2 = bottom - 6;
        int w = x2 - x1;

        if (currentTab == Tab.ASH_CHRONICLES) {
            // Pass button
            if (isInside(mouseX, mouseY, x2 - 118, y1 + 2, 48, 14)) {
                passPlayerRound();
                return true;
            }
            // Restart button
            if (isInside(mouseX, mouseY, x2 - 66, y1 + 2, 62, 14)) {
                startNewAshMatch();
                playRetroSound(1.15f);
                return true;
            }
            // Decoy click on player board rows
            if (awaitingDecoyReturn) {
                int boardY = y1 + 20;
                int rowH = 19;
                int divY = boardY + (rowH + 1) * 3 + 1;
                int pStart = divY + 15;
                List<List<CardDef>> pRows = List.of(pMelee, pRanged, pSiege);
                for (int r = 0; r < 3; r++) {
                    int ry = pStart + r * (rowH + 1);
                    if (isInside(mouseX, mouseY, x1 + 76, ry, w - 76, rowH)) {
                        List<CardDef> rowList = pRows.get(r);
                        if (!rowList.isEmpty()) {
                            CardDef returned = rowList.remove(rowList.size() - 1);
                            playerHand.add(returned);
                            awaitingDecoyReturn = false;
                            setRemark("Подмена сработала! «" + returned.name + "» вернулся в твою руку.");
                            playRetroSound(1.25f);
                            if (!enemyPassed) performEnemyTurn();
                            checkRoundEnd();
                            return true;
                        }
                    }
                }
            }
            // Hand cards click
            int boardY = y1 + 20;
            int rowH = 19;
            int divY = boardY + (rowH + 1) * 3 + 1;
            int pStart = divY + 15;
            int hintY = pStart + (rowH + 1) * 3 + 2;
            int handY = hintY + 16;
            int cardH = Math.max(46, y2 - handY - 2);
            if (!playerHand.isEmpty()) {
                int count = playerHand.size();
                int gap = 3;
                int cardW = Math.min(60, (w - (count - 1) * gap) / count);
                int startX = x1 + (w - (count * cardW + (count - 1) * gap)) / 2;
                for (int i = 0; i < count; i++) {
                    int cx = startX + i * (cardW + gap);
                    if (isInside(mouseX, mouseY, cx, handY - 4, cardW, cardH + 6)) {
                        playPlayerCard(i);
                        return true;
                    }
                }
            }
        } else if (currentTab == Tab.ETHER_SNAKE) {
            if (isInside(mouseX, mouseY, x2 - 68, y1 + 2, 64, 14)) {
                resetSnake();
                playRetroSound(1.15f);
                return true;
            }
        } else if (currentTab == Tab.SOUL_SUMMON) {
            int btnY = y1 + 54;
            int b1W = 150;
            int b10W = 166;
            int b1X = x1 + (w - (b1W + b10W + 14)) / 2;
            int b10X = b1X + b1W + 14;
            if (isInside(mouseX, mouseY, b1X, btnY, b1W, 22)) {
                List<WishResult> res = GrimoireState.getInstance().performSummon(1);
                if (!res.isEmpty()) {
                    lastWishResults.clear();
                    lastWishResults.addAll(res);
                    wishAnimTicks = 20;
                    WishResult wr = res.get(0);
                    playRetroSound(wr.card.rarity == 5 ? 1.55f : 1.2f);
                    setRemark("Призыв явил: «" + wr.card.name + "» (" + "★".repeat(wr.card.rarity) + ")!");
                }
                return true;
            }
            if (isInside(mouseX, mouseY, b10X, btnY, b10W, 22)) {
                List<WishResult> res = GrimoireState.getInstance().performSummon(10);
                if (!res.isEmpty()) {
                    lastWishResults.clear();
                    lastWishResults.addAll(res);
                    wishAnimTicks = 30;
                    playRetroSound(1.6f);
                    setRemark("Великий десятеричный призыв завершён! Проверь новых духов в колоде.");
                }
                return true;
            }
        } else if (currentTab == Tab.DECK_BUILDER) {
            if (isInside(mouseX, mouseY, x2 - 96, y1 + 2, 92, 14)) {
                GrimoireState.getInstance().rebuildBestDeck();
                GrimoireState.getInstance().save();
                playRetroSound(1.25f);
                setRemark("Колода автоматически собрана из твоих сильнейших карт!");
                return true;
            }
            List<CardDef> all = GrimoireState.getInstance().getAllCatalogCards();
            int listY = y1 + 22;
            int itemH = 18;
            int maxRows = Math.max(1, (y2 - listY - 14) / itemH);
            for (int r = 0; r < maxRows; r++) {
                int idx = deckScrollRow + r;
                if (idx >= all.size()) break;
                int ry = listY + r * itemH;
                if (isInside(mouseX, mouseY, x1, ry, w, itemH - 2)) {
                    GrimoireState.getInstance().toggleCardInDeck(all.get(idx).id);
                    playRetroSound(1.1f);
                    return true;
                }
            }
        } else if (currentTab == Tab.DAILIES) {
            int qY = y1 + 40;
            int bx = x1 + w - 96;
            Minecraft mc = Minecraft.getInstance();
            if (isInside(mouseX, mouseY, bx, qY + 8, 88, 18) && GrimoireState.getInstance().claimDailyWalk(mc)) {
                setRemark("Награда за дозорный обход получена: +100 ✦ Эфира!");
                return true;
            }
            if (isInside(mouseX, mouseY, bx, qY + 38 + 8, 88, 18) && GrimoireState.getInstance().claimDailyTalk(mc)) {
                setRemark("Награда за беседы со свитой получена: +80 ✦ Эфира!");
                return true;
            }
            if (isInside(mouseX, mouseY, bx, qY + 76 + 8, 88, 18) && GrimoireState.getInstance().claimDailyGrimoire(mc)) {
                setRemark("Награда за испытания Гримуара получена: +120 ✦ Эфира!");
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentTab == Tab.DECK_BUILDER) {
            int total = GrimoireState.getInstance().getAllCatalogCards().size();
            if (scrollY < 0 && deckScrollRow < Math.max(0, total - 8)) {
                deckScrollRow++;
            } else if (scrollY > 0 && deckScrollRow > 0) {
                deckScrollRow--;
            }
            return true;
        }
        if (currentTab == Tab.COMMANDS) {
            int total = FAMILIAR_COMMANDS.length;
            if (scrollY < 0 && commandScrollRow < Math.max(0, total - 9)) {
                commandScrollRow++;
            } else if (scrollY > 0 && commandScrollRow > 0) {
                commandScrollRow--;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (currentTab == Tab.ETHER_SNAKE) {
            // Up: W (87) or UP (265)
            if ((key == 87 || key == 265) && snakeDirY != 1) {
                nextDirX = 0; nextDirY = -1;
                return true;
            }
            // Down: S (83) or DOWN (264)
            if ((key == 83 || key == 264) && snakeDirY != -1) {
                nextDirX = 0; nextDirY = 1;
                return true;
            }
            // Left: A (65) or LEFT (263)
            if ((key == 65 || key == 263) && snakeDirX != 1) {
                nextDirX = -1; nextDirY = 0;
                return true;
            }
            // Right: D (68) or RIGHT (262)
            if ((key == 68 || key == 262) && snakeDirX != -1) {
                nextDirX = 1; nextDirY = 0;
                return true;
            }
            // R (82) restart
            if (key == 82) {
                resetSnake();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    // =========================================================================
    // PIXEL ART HELPERS
    // =========================================================================
    private static void drawPixelBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void drawPixelSkullIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 2, y, x + 10, y + 2, color);
        g.fill(x + 1, y + 2, x + 11, y + 7, color);
        g.fill(x + 3, y + 4, x + 5, y + 6, 0xFF0A0E12);
        g.fill(x + 7, y + 4, x + 9, y + 6, 0xFF0A0E12);
        g.fill(x + 3, y + 7, x + 9, y + 10, color);
        g.fill(x + 5, y + 8, x + 6, y + 10, 0xFF0A0E12);
        g.fill(x + 7, y + 8, x + 8, y + 10, 0xFF0A0E12);
    }

    private static void drawCardPixelGlyph(GuiGraphicsExtractor g, int kind, int x, int y, int color) {
        switch (kind % 8) {
            case 0 -> { // Sword / Stand
                g.fill(x + 5, y + 1, x + 7, y + 11, color);
                g.fill(x + 2, y + 8, x + 10, y + 10, color);
                g.fill(x + 4, y + 11, x + 8, y + 13, 0xFFFFD700);
            }
            case 1 -> { // Pigeon / Wing
                g.fill(x + 2, y + 4, x + 9, y + 10, color);
                g.fill(x + 7, y + 2, x + 11, y + 5, color);
                g.fill(x + 11, y + 3, x + 13, y + 4, 0xFFFFB300);
            }
            case 2 -> { // Chest / Siege
                g.fill(x + 1, y + 3, x + 11, y + 11, color);
                g.fill(x + 1, y + 6, x + 11, y + 7, 0xFF1A1A1A);
                g.fill(x + 5, y + 5, x + 7, y + 8, 0xFFFFD700);
            }
            case 3 -> drawPixelSkullIcon(g, x, y + 1, color);
            case 4 -> { // Spy Eye
                g.fill(x + 1, y + 5, x + 11, y + 9, color);
                g.fill(x + 4, y + 4, x + 8, y + 10, 0xFFFFFFFF);
                g.fill(x + 5, y + 6, x + 7, y + 8, 0xFF111111);
            }
            case 5 -> { // Decoy Mask
                g.fill(x + 2, y + 2, x + 10, y + 11, color);
                g.fill(x + 4, y + 4, x + 5, y + 6, 0xFF111111);
                g.fill(x + 7, y + 4, x + 8, y + 6, 0xFF111111);
                g.fill(x + 4, y + 8, x + 8, y + 9, 0xFF111111);
            }
            case 6 -> { // Weather Cloud
                g.fill(x + 2, y + 3, x + 10, y + 7, color);
                g.fill(x + 3, y + 9, x + 4, y + 12, 0xFF81D4FA);
                g.fill(x + 8, y + 9, x + 9, y + 12, 0xFF81D4FA);
            }
            default -> { // Crown / Sun
                g.fill(x + 2, y + 7, x + 10, y + 11, color);
                g.fill(x + 2, y + 4, x + 4, y + 7, color);
                g.fill(x + 5, y + 3, x + 7, y + 7, color);
                g.fill(x + 8, y + 4, x + 10, y + 7, color);
            }
        }
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String gemsStr(int gems) {
        return "◆".repeat(Math.max(0, gems)) + "◇".repeat(Math.max(0, 2 - gems));
    }

    private static String shortName(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, Math.max(1, maxChars - 1)) + ".";
    }

    private String trimToWidth(String text, int maxPx) {
        if (text == null) return "";
        if (this.font.width(text) <= maxPx) return text;
        String t = text;
        while (t.length() > 1 && this.font.width(t + "..") > maxPx) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "..";
    }
}
