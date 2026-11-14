package com.nightfall.voice.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-owned configuration. API keys are read from environment variables only. */
public final class VoiceConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public String locale = "pt";
    public String narrativeSetting = "Freedom County, Estados Unidos, 2013. O sobrenatural existe, mas a maioria dos moradores desconhece isso.";
    public double maxInteractionDistance = 7.0;
    public float replyHearingDistance = 22.0f;
    public double voiceThresholdRms = 370.0;
    public int speechSilenceMs = 780;
    public int minSpeechMs = 350;
    public int maxSpeechMs = 12000;
    public int cooldownMs = 2200;
    public int httpTimeoutSeconds = 28;
    public int maxResponseCharacters = 500;
    public int maxStoredTurnsPerNpc = 80;
    public int maxBeliefsPerNpc = 48;
    public boolean enableTts = true;
    public boolean showSubtitles = true;
    public String chatUrl = "https://api.openai.com/v1/chat/completions";
    public String sttUrl = "https://api.openai.com/v1/audio/transcriptions";
    public String ttsUrl = "https://api.openai.com/v1/audio/speech";
    public String chatModel = "gpt-4o-mini";
    public String sttModel = "whisper-1";
    public String ttsModel = "tts-1";
    public String defaultVoice = "alloy";

    public static VoiceConfig load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            VoiceConfig initial = new VoiceConfig();
            Files.writeString(path, GSON.toJson(initial), StandardCharsets.UTF_8);
            return initial;
        }
        VoiceConfig read = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), VoiceConfig.class);
        if (read == null) throw new IOException("Config vazio: " + path);
        if (read.maxInteractionDistance < 1 || read.maxInteractionDistance > 32) throw new IOException("maxInteractionDistance invalido");
        if (read.maxSpeechMs < 1000 || read.maxSpeechMs > 30000) throw new IOException("maxSpeechMs invalido");
        if (read.httpTimeoutSeconds < 5 || read.httpTimeoutSeconds > 120) throw new IOException("httpTimeoutSeconds invalido");
        if (read.chatUrl == null || read.sttUrl == null || read.ttsUrl == null) throw new IOException("URLs ausentes");
        // Guard against accidental insecure HTTP credentials.
        for (String url : new String[]{read.chatUrl, read.sttUrl, read.ttsUrl}) {
            if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) {
                throw new IOException("Somente HTTPS ou localhost sao permitidos para servicos de IA: " + url);
            }
        }
        return read;
    }

    public String apiKey() {
        String key = System.getenv("NIGHTFALL_AI_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("OPENAI_API_KEY");
        return key == null ? "" : key.trim();
    }
}
