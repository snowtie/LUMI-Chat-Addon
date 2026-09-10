package com.snowtie.lumichataddon;

import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeIoSmoke {
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]).resolve("download.bin");
        Files.writeString(target, "preserve me");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/failed", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[] {1, 2, 3});
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try {
                RuntimeManager.download(client, base + "/failed", target);
                throw new AssertionError("failed download was accepted");
            } catch (java.io.IOException expected) {
                if (!Files.readString(target).equals("preserve me")) throw new AssertionError("existing download lost");
            }
            RuntimeManager.download(client, base + "/ok", target);
            if (Files.size(target) != 3) throw new AssertionError("successful download failed");
            try (var files = Files.list(target.getParent())) {
                if (files.anyMatch(path -> path.toString().endsWith(".download"))) throw new AssertionError("partial download left behind");
            }
        } finally {
            server.stop(0);
        }
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        for (int frames : new int[] { 0, 160 }) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(new byte[frames * 2]), format, frames)) {
                AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
            }
            if (frames == 0) {
                try {
                    WavDecoder.decode(output.toByteArray());
                    throw new AssertionError("empty audio was accepted");
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("빈 음성")) throw expected;
                }
            } else if (WavDecoder.decode(output.toByteArray()).pcm().length != frames * 2) {
                throw new AssertionError("PCM length changed");
            }
        }
        System.out.println("Download rollback and WAV validation passed");
    }
}
