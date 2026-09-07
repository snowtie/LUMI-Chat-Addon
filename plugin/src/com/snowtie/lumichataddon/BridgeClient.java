package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class BridgeClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    boolean healthy() {
        try {
            Map<?, ?> health = get("/health");
            return Boolean.TRUE.equals(health.get("ok"))
                    && String.valueOf(health.get("name")).startsWith("LUMI Chat Addon");
        } catch (Exception ignored) {
            return false;
        }
    }

    Map<?, ?> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RuntimeManager.BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return sendJson(request);
    }

    Map<?, ?> post(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RuntimeManager.BASE_URL + path))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return sendJson(request);
    }

    byte[] synthesize(String text, Properties settings) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("base_url", property(settings, "tts.gpt_sovits.base", "http://127.0.0.1:9880"));
        payload.put("runtime_dir", property(settings, "tts.gpt_sovits.runtime", ""));
        payload.put("gpt_weights_path", property(settings, "tts.gpt_sovits.gpt_weights", ""));
        payload.put("sovits_weights_path", property(settings, "tts.gpt_sovits.sovits_weights", ""));
        payload.put("reference_audio_path", property(settings, "tts.gpt_sovits.reference_audio", ""));
        payload.put("prompt_text", property(settings, "tts.gpt_sovits.reference_text", ""));
        payload.put("text_language", property(settings, "tts.gpt_sovits.text_language", "ko"));
        payload.put("prompt_language", property(settings, "tts.gpt_sovits.prompt_language", "ko"));
        payload.put("power_mode", property(settings, "tts.gpt_sovits.power_mode", "balanced"));
        payload.put("device_mode", property(settings, "tts.gpt_sovits.device_mode", "auto"));
        payload.put("speed_factor", property(settings, "tts.gpt_sovits.speed", "1.0"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(RuntimeManager.BASE_URL + "/voice/synthesize"))
                .timeout(Duration.ofSeconds(190))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            String message = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("TTS HTTP " + response.statusCode() + ": " + message);
        }
        return response.body();
    }

    private Map<?, ?> sendJson(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Object payload = Json.parse(response.body());
        if (response.statusCode() / 100 != 2) {
            String message = Json.getString(payload, "error");
            if (message == null) {
                message = response.body();
            }
            throw new IOException("HTTP " + response.statusCode() + ": " + message);
        }
        if (!(payload instanceof Map<?, ?> map)) {
            throw new IOException("도우미가 JSON 객체가 아닌 응답을 보냈습니다.");
        }
        return map;
    }

    private static String property(Properties properties, String key, String fallback) {
        return properties.getProperty(key, fallback).trim();
    }
}
