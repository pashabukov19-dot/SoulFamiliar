package com.soulfamiliar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class GroqBrain {
    private static final GroqBrain INSTANCE = new GroqBrain();
    private static final Gson GSON = new Gson();

    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private volatile String lastWorkingModel = FamiliarConfig.DEFAULT_PRIMARY_MODEL;
    private volatile String lastStatusInfo = "Ожидание первого запроса";
    private volatile boolean warnedAboutFailure = false;
    private volatile long rateLimitedUntilMs = 0L;

    private String cachedProxySpec = null;
    private HttpClient cachedClient = null;

    private GroqBrain() {}

    public static GroqBrain getInstance() {
        return INSTANCE;
    }

    public boolean isBusy() {
        return requestInFlight.get();
    }

    public String getLastWorkingModel() {
        return lastWorkingModel;
    }

    public String getLastStatusInfo() {
        return lastStatusInfo;
    }

    public void resetFailureWarning() {
        this.warnedAboutFailure = false;
        this.rateLimitedUntilMs = 0L;
    }

    private synchronized HttpClient getClientForConfig(FamiliarConfig cfg) {
        String proxySpec = cfg.getProxyAddress();
        if (cachedClient != null && proxySpec.equals(cachedProxySpec)) {
            return cachedClient;
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6));

        if (proxySpec != null && !proxySpec.isBlank() && !proxySpec.equalsIgnoreCase("off") && !proxySpec.equalsIgnoreCase("выкл")) {
            String[] parts = proxySpec.split(":");
            if (parts.length == 2) {
                try {
                    String host = parts[0].trim();
                    int port = Integer.parseInt(parts[1].trim());
                    builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        cachedProxySpec = proxySpec;
        cachedClient = builder.build();
        return cachedClient;
    }

    public record AiReply(String text, String gestureTag) {}

    public void testConnectionAsync(Minecraft client) {
        FamiliarConfig cfg = FamiliarConfig.getInstance();
        if (!cfg.hasActiveAi()) {
            FamiliarManager.notifyPlayer(client, "Сначала введи ключ: .f ключ gsk_...", ChatFormatting.YELLOW);
            return;
        }
        FamiliarManager.notifyPlayer(client, "Проверяю связь с нейросетью...", ChatFormatting.GRAY);
        CompletableFuture.supplyAsync(() -> {
            String sys = "Ты дух-фамильяр. Ответь одной короткой фразой на русском, обращаясь к хозяину «Повелитель».";
            String usr = "Скажи, что эфирная связь успешно установлена.";
            return executeWithCascade(cfg, sys, usr);
        }).whenComplete((ans, err) -> {
            if (client == null) return;
            client.execute(() -> {
                if (ans != null && !ans.isBlank()) {
                    FamiliarManager.notifyPlayer(client, "УСПЕХ! Модель [" + lastWorkingModel + "] ответила: «" + ans + "»", ChatFormatting.GREEN);
                } else {
                    FamiliarManager.notifyPlayer(client, "ОШИБКА СВЯЗИ: " + lastStatusInfo, ChatFormatting.RED);
                }
            });
        });
    }

    public void askFamiliarAsync(
            Minecraft client,
            Familiar speaker,
            List<Familiar> allFamiliars,
            String worldContext,
            String chatTranscript,
            String triggerInstruction,
            Consumer<AiReply> onSuccess,
            Runnable onFallback
    ) {
        FamiliarConfig cfg = FamiliarConfig.getInstance();
        if (!cfg.hasActiveAi()) {
            onFallback.run();
            return;
        }

        if (System.currentTimeMillis() < rateLimitedUntilMs && cfg.getBackupApiUrl().isBlank()) {
            onFallback.run();
            return;
        }

        if (!requestInFlight.compareAndSet(false, true)) {
            return;
        }

        final String systemPrompt = buildSystemPrompt(speaker, allFamiliars);
        final String userPrompt = buildUserPrompt(worldContext, chatTranscript, triggerInstruction);

        CompletableFuture.supplyAsync(() -> executeWithCascade(cfg, systemPrompt, userPrompt))
                .whenComplete((rawContent, throwable) -> {
                    requestInFlight.set(false);
                    if (client == null) return;
                    client.execute(() -> {
                        if (throwable != null || rawContent == null || rawContent.isBlank()) {
                            if (!warnedAboutFailure) {
                                warnedAboutFailure = true;
                                FamiliarManager.notifyPlayer(client,
                                        "Нейросеть не ответила (" + lastStatusInfo + "). Проверь .f тест ии",
                                        ChatFormatting.YELLOW);
                            }
                            onFallback.run();
                        } else {
                            AiReply parsed = parseReplyAndGesture(rawContent);
                            if (parsed.text().isBlank()) {
                                onFallback.run();
                            } else {
                                onSuccess.accept(parsed);
                            }
                        }
                    });
                });
    }

    private String executeWithCascade(FamiliarConfig cfg, String systemPrompt, String userPrompt) {
        HttpClient httpClient = getClientForConfig(cfg);
        String latestError = "Неизвестная ошибка";

        if (!cfg.getApiUrl().isBlank() && (!cfg.getApiKey().isBlank() || isLocalEndpoint(cfg.getApiUrl()))) {
            List<String> models = new ArrayList<>(cfg.getModelCascade());
            boolean sawModelError = false;
            boolean sawRateLimit = false;

            for (String model : models) {
                CallOutcome outcome = callChatCompletions(httpClient, cfg.getApiUrl(), cfg.getApiKey(), model, systemPrompt, userPrompt);
                if (outcome.text != null && !outcome.text.isBlank()) {
                    this.lastWorkingModel = model;
                    this.lastStatusInfo = "OK (HTTP 200, модель: " + model + ")";
                    return outcome.text;
                }
                latestError = outcome.errorReason;
                if (outcome.statusCode == 429) sawRateLimit = true;
                if (outcome.statusCode == 400 || outcome.statusCode == 404) sawModelError = true;
                if (outcome.statusCode == 401 || outcome.statusCode == 403 || outcome.statusCode == -1) {
                    break;
                }
            }

            if (sawModelError) {
                List<String> discovered = discoverAvailableModels(httpClient, cfg.getApiUrl(), cfg.getApiKey());
                for (String liveModel : discovered) {
                    if (models.contains(liveModel)) continue;
                    CallOutcome outcome = callChatCompletions(httpClient, cfg.getApiUrl(), cfg.getApiKey(), liveModel, systemPrompt, userPrompt);
                    if (outcome.text != null && !outcome.text.isBlank()) {
                        this.lastWorkingModel = liveModel;
                        this.lastStatusInfo = "OK (автовыбор модели: " + liveModel + ")";
                        cfg.setPrimaryModel(liveModel);
                        return outcome.text;
                    }
                    latestError = outcome.errorReason;
                }
            }

            if (sawRateLimit) {
                this.rateLimitedUntilMs = System.currentTimeMillis() + 15_000L;
            }
        }

        if (!cfg.getBackupApiUrl().isBlank()) {
            String backupModel = cfg.getBackupModel().isBlank() ? "llama3.2" : cfg.getBackupModel();
            CallOutcome outcome = callChatCompletions(httpClient, cfg.getBackupApiUrl(), cfg.getBackupApiKey(), backupModel, systemPrompt, userPrompt);
            if (outcome.text != null && !outcome.text.isBlank()) {
                this.lastWorkingModel = backupModel + " (резерв)";
                this.lastStatusInfo = "OK (резервный сервер)";
                return outcome.text;
            }
            latestError = outcome.errorReason;
        }

        this.lastStatusInfo = latestError;
        return null;
    }

    private record CallOutcome(String text, int statusCode, String errorReason) {}

    private CallOutcome callChatCompletions(HttpClient httpClient, String url, String apiKey, String model, String systemPrompt, String userPrompt) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("model", model);
            root.addProperty("temperature", 0.88);
            root.addProperty("max_tokens", 180);

            JsonArray messages = new JsonArray();
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);

            JsonObject usrMsg = new JsonObject();
            usrMsg.addProperty("role", "user");
            usrMsg.addProperty("content", userPrompt);
            messages.add(usrMsg);

            root.add("messages", messages);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 SoulFamiliarMod/1.5.3")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)));

            if (apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("none")) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 401) {
                return new CallOutcome(null, code, "HTTP 401 — неверный API-ключ");
            }
            if (code == 403) {
                return new CallOutcome(null, code, "HTTP 403 — доступ заблокирован (нужен прокси/VPN или смена провайдера)");
            }
            if (code == 429) {
                return new CallOutcome(null, code, "HTTP 429 — временный лимит запросов");
            }
            if (code < 200 || code >= 300) {
                String snippet = response.body() != null ? response.body().replaceAll("\\s+", " ") : "";
                if (snippet.length() > 90) snippet = snippet.substring(0, 90);
                return new CallOutcome(null, code, "HTTP " + code + " (" + model + "): " + snippet);
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return new CallOutcome(null, code, "Пустой ответ от модели");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject messageObj = firstChoice.getAsJsonObject("message");
            if (messageObj == null) {
                return new CallOutcome(null, code, "Нет поля message в ответе");
            }

            JsonElement contentEl = messageObj.get("content");
            if (contentEl == null || contentEl.isJsonNull()) {
                return new CallOutcome(null, code, "Пустой текст в ответе");
            }

            String raw = contentEl.getAsString().trim();
            raw = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
            return new CallOutcome(raw, code, "OK");
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return new CallOutcome(null, -1, msg);
        }
    }

    private List<String> discoverAvailableModels(HttpClient httpClient, String chatUrl, String apiKey) {
        List<String> found = new ArrayList<>();
        try {
            String modelsUrl = chatUrl.replace("/chat/completions", "/models");
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 SoulFamiliarMod/1.5.3")
                    .GET();
            if (apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("none")) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray data = json.getAsJsonArray("data");
                if (data != null) {
                    for (JsonElement el : data) {
                        JsonObject obj = el.getAsJsonObject();
                        if (obj.has("id")) {
                            String id = obj.get("id").getAsString();
                            String low = id.toLowerCase(Locale.ROOT);
                            if (!low.contains("whisper") && !low.contains("tts") && !low.contains("guard") && !low.contains("embed")) {
                                found.add(id);
                                if (found.size() >= 4) break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return found;
    }

    private static boolean isLocalEndpoint(String url) {
        return url.contains("localhost") || url.contains("127.0.0.1");
    }

    private static String buildSystemPrompt(Familiar speaker, List<Familiar> allFamiliars) {
        StringBuilder companions = new StringBuilder();
        for (Familiar f : allFamiliars) {
            if (f != speaker) {
                if (!companions.isEmpty()) companions.append(", ");
                companions.append(f.getName()).append(" (настроение: ").append(f.getMood().getRussianLabel()).append(")");
            }
        }
        String companionsLine = companions.isEmpty()
                ? "Ты единственный призванный дух в свите."
                : "Другие духи в свите: " + companions + ". Вы связаны незримой эфирной нитью душ.";

        return "Ты — бессмертный дух-фамильяр по имени " + speaker.getName() + " в мире Minecraft (стилистика тёмного фэнтези и D&D). "
                + "Твоё текущее настроение: " + speaker.getMood().getRussianLabel() + ".\n"
                + companionsLine + "\n"
                + "ПРАВИЛА ОТВЕТА:\n"
                + "1. Отвечай ТОЛЬКО на русском языке, живо, остроумно и выразительно (1–2 предложения, до 230 символов).\n"
                + "2. Всегда обращайся к своему хозяину с почтением и уважением: «Повелитель», «Господин», «мой господин» или «Владыка».\n"
                + "3. Сохраняй лёгкий аристократический сарказм по отношению к другим игрокам, мобам и опасности, но к Повелителю будь предан.\n"
                + "4. НИКОГДА не ломай четвёртую стену: не упоминай нейросети, ИИ, код, токены, API, баги или «коллективный разум» как термин — говори об «эфире», «нити душ», «тенях».\n"
                + "5. По желанию можешь поставить в самом начале ответа ОДИН тег жеста из списка: [NOD], [SHAKE], [BOW], [THINK], [DRAMA], [APPLAUD], [THREATEN], [DANCE] — если он подходит по смыслу.\n"
                + "6. Не заключай ответ в кавычки — они добавятся автоматически.";
    }

    private static String buildUserPrompt(String worldContext, String chatTranscript, String triggerInstruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("ОБСТАНОВКА ВОКРУГ:\n").append(worldContext).append("\n\n");
        if (chatTranscript != null && !chatTranscript.isBlank()) {
            sb.append("ПОСЛЕДНИЕ СООБЩЕНИЯ В ЧАТЕ СЕРВЕРА:\n").append(chatTranscript).append("\n\n");
        }
        sb.append("ЗАДАЧА:\n").append(triggerInstruction);
        return sb.toString();
    }

    private static AiReply parseReplyAndGesture(String raw) {
        String text = raw.trim();
        String gesture = null;

        String[] tags = {"[NOD]", "[SHAKE]", "[BOW]", "[THINK]", "[DRAMA]", "[APPLAUD]", "[THREATEN]", "[DANCE]"};
        for (String tag : tags) {
            if (text.toUpperCase(Locale.ROOT).startsWith(tag)) {
                gesture = tag.substring(1, tag.length() - 1);
                text = text.substring(tag.length()).trim();
                break;
            }
        }

        if ((text.startsWith("«") && text.endsWith("»")) || (text.startsWith("\"") && text.endsWith("\""))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return new AiReply(text, gesture);
    }
}
