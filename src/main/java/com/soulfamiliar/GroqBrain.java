package com.soulfamiliar;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class GroqBrain {
    private static final GroqBrain INSTANCE = new GroqBrain();
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private volatile String lastWorkingModel = FamiliarConfig.DEFAULT_PRIMARY_MODEL;
    private volatile long rateLimitedUntilMs = 0L;

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

    public record AiReply(String text, String gestureTag) {}

    /**
     * Asynchronously queries Groq (with automatic model cascade fallback + backup provider fallback).
     * Calls onSuccess on the Minecraft client thread, or onFallback if all AI endpoints fail.
     */
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
            // Another request is already running; skip duplicate spam or use local fallback
            return;
        }

        String systemPrompt = buildSystemPrompt(speaker, allFamiliars);
        String userPrompt = buildUserPrompt(worldContext, chatTranscript, triggerInstruction);

        CompletableFuture.supplyAsync(() -> executeWithCascade(cfg, systemPrompt, userPrompt))
                .whenComplete((rawContent, throwable) -> {
                    requestInFlight.set(false);
                    if (client == null) return;
                    client.execute(() -> {
                        if (throwable != null || rawContent == null || rawContent.isBlank()) {
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
        // 1. Try primary endpoint (Groq) across all configured models in cascade
        if (!cfg.getApiUrl().isBlank() && (!cfg.getApiKey().isBlank() || isLocalEndpoint(cfg.getApiUrl()))) {
            List<String> models = cfg.getModelCascade();
            for (String model : models) {
                try {
                    String answer = callChatCompletions(cfg.getApiUrl(), cfg.getApiKey(), model, systemPrompt, userPrompt);
                    if (answer != null && !answer.isBlank()) {
                        this.lastWorkingModel = model;
                        return answer;
                    }
                } catch (RateLimitException rle) {
                    // Try next model in Groq cascade (each model has its own rate limit bucket!)
                } catch (Exception ignored) {
                    // Try next model
                }
            }
            // All Groq models temporarily rate-limited -> brief 20s cooldown
            this.rateLimitedUntilMs = System.currentTimeMillis() + 20_000L;
        }

        // 2. Try backup OpenAI-compatible provider (e.g., Ollama / OpenRouter / GitHub Models)
        if (!cfg.getBackupApiUrl().isBlank()) {
            String backupModel = cfg.getBackupModel().isBlank() ? "llama3.2" : cfg.getBackupModel();
            try {
                String answer = callChatCompletions(cfg.getBackupApiUrl(), cfg.getBackupApiKey(), backupModel, systemPrompt, userPrompt);
                if (answer != null && !answer.isBlank()) {
                    this.lastWorkingModel = backupModel + " (резерв)";
                    return answer;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String callChatCompletions(String url, String apiKey, String model, String systemPrompt, String userPrompt) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0.85);
        root.addProperty("max_tokens", 170);

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
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)));

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equalsIgnoreCase("none")) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 429) {
            throw new RateLimitException();
        }
        if (code < 200 || code >= 300) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return null;

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject messageObj = firstChoice.getAsJsonObject("message");
        if (messageObj == null) return null;

        JsonElement contentEl = messageObj.get("content");
        if (contentEl == null || contentEl.isJsonNull()) return null;

        String raw = contentEl.getAsString().trim();
        // Strip <think>...</think> blocks if a reasoning model (like Qwen3) outputs them
        raw = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        return raw;
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
                + "1. Отвечай ТОЛЬКО на русском языке, кратко и выразительно (1–2 предложения, максимум 220 символов).\n"
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
            if (text.toUpperCase().startsWith(tag)) {
                gesture = tag.substring(1, tag.length() - 1);
                text = text.substring(tag.length()).trim();
                break;
            }
        }

        // Remove accidental wrapping quotes
        if ((text.startsWith("«") && text.endsWith("»")) || (text.startsWith("\"") && text.endsWith("\""))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return new AiReply(text, gesture);
    }

    private static final class RateLimitException extends Exception {}
}
