package com.soulfamiliar;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class FamiliarConfig {
    private static final FamiliarConfig INSTANCE = new FamiliarConfig();

    public static final String DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String DEFAULT_PRIMARY_MODEL = "openai/gpt-oss-120b";
    public static final String DEFAULT_FALLBACK_MODELS = "openai/gpt-oss-20b,qwen/qwen3.8-27b";

    private String apiUrl = DEFAULT_GROQ_URL;
    private String apiKey = "";
    private String primaryModel = DEFAULT_PRIMARY_MODEL;
    private String fallbackModels = DEFAULT_FALLBACK_MODELS;
    private String proxyAddress = "";

    private String backupApiUrl = "";
    private String backupApiKey = "";
    private String backupModel = "";

    private boolean aiEnabled = true;

    private FamiliarConfig() {}

    public static FamiliarConfig getInstance() {
        return INSTANCE;
    }

    private Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("soulfamiliar.properties");
    }

    public void load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            this.apiUrl = props.getProperty("api_url", DEFAULT_GROQ_URL).trim();
            this.apiKey = props.getProperty("api_key", "").trim();
            String loadedPrimary = props.getProperty("primary_model", DEFAULT_PRIMARY_MODEL).trim();
            // Auto-migrate retired 2026 free-tier Llama IDs to active Groq free models
            if (loadedPrimary.equals("llama-3.3-70b-versatile") || loadedPrimary.equals("llama-3.1-8b-instant")) {
                loadedPrimary = DEFAULT_PRIMARY_MODEL;
            }
            this.primaryModel = loadedPrimary;
            this.fallbackModels = props.getProperty("fallback_models", DEFAULT_FALLBACK_MODELS).trim();
            this.proxyAddress = props.getProperty("proxy_address", "").trim();
            this.backupApiUrl = props.getProperty("backup_api_url", "").trim();
            this.backupApiKey = props.getProperty("backup_api_key", "").trim();
            this.backupModel = props.getProperty("backup_model", "").trim();
            this.aiEnabled = Boolean.parseBoolean(props.getProperty("ai_enabled", "true"));
            save();
        } catch (IOException ignored) {
        }
    }

    public void save() {
        Path path = getConfigPath();
        Properties props = new Properties();
        props.setProperty("api_url", apiUrl);
        props.setProperty("api_key", apiKey);
        props.setProperty("primary_model", primaryModel);
        props.setProperty("fallback_models", fallbackModels);
        props.setProperty("proxy_address", proxyAddress);
        props.setProperty("backup_api_url", backupApiUrl);
        props.setProperty("backup_api_key", backupApiKey);
        props.setProperty("backup_model", backupModel);
        props.setProperty("ai_enabled", Boolean.toString(aiEnabled));

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "SoulFamiliar AI & Groq Configuration");
            }
        } catch (IOException ignored) {
        }
    }

    public boolean hasActiveAi() {
        if (!aiEnabled) return false;
        boolean hasPrimary = !apiKey.isBlank() || apiUrl.contains("localhost") || apiUrl.contains("127.0.0.1");
        boolean hasBackup = !backupApiUrl.isBlank() && (!backupApiKey.isBlank() || backupApiUrl.contains("localhost") || backupApiUrl.contains("127.0.0.1"));
        return hasPrimary || hasBackup;
    }

    public List<String> getModelCascade() {
        List<String> models = new ArrayList<>();
        if (!primaryModel.isBlank()) {
            models.add(primaryModel);
        }
        if (!fallbackModels.isBlank()) {
            for (String m : Arrays.asList(fallbackModels.split(","))) {
                String clean = m.trim();
                if (!clean.isEmpty() && !models.contains(clean)) {
                    models.add(clean);
                }
            }
        }
        if (models.isEmpty()) {
            models.add(DEFAULT_PRIMARY_MODEL);
        }
        return models;
    }

    public String getMaskedKey() {
        if (apiKey == null || apiKey.isBlank()) return "не задан";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl.trim(); save(); }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey.trim(); save(); }

    public String getPrimaryModel() { return primaryModel; }
    public void setPrimaryModel(String primaryModel) { this.primaryModel = primaryModel.trim(); save(); }

    public String getFallbackModels() { return fallbackModels; }
    public void setFallbackModels(String fallbackModels) { this.fallbackModels = fallbackModels.trim(); save(); }

    public String getProxyAddress() { return proxyAddress; }
    public void setProxyAddress(String proxyAddress) { this.proxyAddress = proxyAddress != null ? proxyAddress.trim() : ""; save(); }

    public String getBackupApiUrl() { return backupApiUrl; }
    public String getBackupApiKey() { return backupApiKey; }
    public String getBackupModel() { return backupModel; }

    public void setBackupProvider(String url, String key, String model) {
        this.backupApiUrl = url != null ? url.trim() : "";
        this.backupApiKey = key != null ? key.trim() : "";
        this.backupModel = model != null ? model.trim() : "";
        save();
    }

    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; save(); }
}
