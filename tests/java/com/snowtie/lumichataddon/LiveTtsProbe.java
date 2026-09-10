package com.snowtie.lumichataddon;

import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import com.group_finity.mascot.lumi.ai.TtsClient;

public final class LiveTtsProbe {
    public static void agentmain(String directory, Instrumentation instrumentation) {
        Path root = Path.of(directory);
        try {
            if (!LumiChatAddonPlugin.gptSovitsSelected()) throw new IllegalStateException("GPT-SoVITS is not selected");
            if (!TtsClient.configured()) throw new IllegalStateException("TtsClient is not configured");
            var audio = TtsClient.synthesize("루미 음성 연결을 확인했어요.", null);
            try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(audio.pcm()), audio.format(),
                    audio.pcm().length / audio.format().getFrameSize())) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, root.resolve("live-tts.wav").toFile());
            }
            Files.writeString(root.resolve("live-tts-result.txt"),
                    "PASS\nTtsClient=" + TtsClient.class.getProtectionDomain().getCodeSource().getLocation()
                    + "\nPCM bytes=" + audio.pcm().length + "\nDuration ms=" + audio.millis());
        } catch (Throwable error) {
            try { Files.writeString(root.resolve("live-tts-result.txt"), "FAIL\n" + error); }
            catch (Exception ignored) { error.printStackTrace(); }
        }
    }

    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); }
        finally { vm.detach(); }
    }
}
