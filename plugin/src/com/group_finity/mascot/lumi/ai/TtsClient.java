package com.group_finity.mascot.lumi.ai;

import com.group_finity.mascot.lumi.plugin.PcmAudio;
import com.snowtie.lumichataddon.LumiChatAddonPlugin;

import javax.sound.sampled.AudioFormat;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * LUMI Chat 1.1.0의 기존 TTS 프로바이더를 보존하면서 GPT-SoVITS를 추가합니다.
 */
public final class TtsClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private TtsClient() {
    }

    public static boolean configured() {
        AiSettings settings = AiSettings.get();
        return switch (provider(settings)) {
            case "gpt_sovits" -> LumiChatAddonPlugin.gptSovitsConfigured();
            case "eleven" -> !settings.elevenKey().isEmpty();
            default -> !settings.fishKey().isEmpty();
        };
    }

    public static PcmAudio synthesize(String text) throws Exception {
        return synthesize(text, null);
    }

    public static PcmAudio synthesize(String text, String character) throws Exception {
        AiSettings settings = AiSettings.get();
        return switch (provider(settings)) {
            case "gpt_sovits" -> LumiChatAddonPlugin.synthesizeGptSovits(text, character);
            case "eleven" -> eleven(settings, text, character);
            default -> fish(settings, text, character);
        };
    }

    private static String provider(AiSettings settings) {
        try {
            Method getter = AiSettings.class.getDeclaredMethod("get", String.class, String.class);
            getter.setAccessible(true);
            return String.valueOf(getter.invoke(settings, "tts.provider", "fish"));
        } catch (ReflectiveOperationException error) {
            return settings.ttsProvider();
        }
    }

    private static PcmAudio fish(AiSettings settings, String text, String character) throws Exception {
        if (settings.fishKey().isEmpty()) {
            throw new IllegalStateException("no-tts-key");
        }
        int sampleRate = 44_100;
        String voice = character == null ? settings.fishVoice() : settings.fishVoiceFor(character);
        if (voice.isEmpty()) {
            throw noVoiceId();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("reference_id", voice);
        payload.put("format", "pcm");
        payload.put("sample_rate", sampleRate);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.fish.audio/v1/tts"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.fishKey())
                .header("model", settings.fishModel())
                .POST(HttpRequest.BodyPublishers.ofString(
                        MiniJson.write(payload), StandardCharsets.UTF_8))
                .build();
        return pcmAudio(send(request), sampleRate);
    }

    private static PcmAudio eleven(AiSettings settings, String text, String character) throws Exception {
        if (settings.elevenKey().isEmpty()) {
            throw new IllegalStateException("no-tts-key");
        }
        int sampleRate = 16_000;
        String voice = character == null ? settings.elevenVoice() : settings.elevenVoiceFor(character);
        if (voice.isEmpty()) {
            throw noVoiceId();
        }
        LinkedHashMap<String, String> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("model_id", settings.elevenModel());
        String endpoint = "https://api.elevenlabs.io/v1/text-to-speech/"
                + URLEncoder.encode(voice, StandardCharsets.UTF_8)
                + "?output_format=pcm_" + sampleRate;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("xi-api-key", settings.elevenKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        MiniJson.write(payload), StandardCharsets.UTF_8))
                .build();
        return pcmAudio(send(request), sampleRate);
    }

    private static IllegalStateException noVoiceId() {
        return new IllegalStateException(AiText.localized(
                "AiTtsNoVoiceId",
                "보이스 ID를 넣어 주세요 — 목소리 탭의 보이스 ID 칸이 비어 있어요."));
    }

    private static byte[] send(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            String detail = new String(response.body(), StandardCharsets.UTF_8);
            if (detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            throw new IllegalStateException("TTS HTTP " + response.statusCode() + ": " + detail);
        }
        return response.body();
    }

    private static PcmAudio pcmAudio(byte[] pcm, int sampleRate) {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        return new PcmAudio(format, pcm, PcmAudio.millisOf(format, pcm.length));
    }
}
