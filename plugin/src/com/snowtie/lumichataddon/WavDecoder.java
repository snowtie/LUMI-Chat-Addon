package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.PcmAudio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

final class WavDecoder {
    private static final int MAX_PCM_BYTES = 64 * 1024 * 1024;

    private WavDecoder() {
    }

    static PcmAudio decode(byte[] wav) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat original = source.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    original.getSampleRate(),
                    16,
                    original.getChannels(),
                    original.getChannels() * 2,
                    original.getSampleRate(),
                    false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                byte[] data = pcm.readNBytes(MAX_PCM_BYTES + 1);
                if (data.length == 0) {
                    throw new IllegalStateException("GPT-SoVITS가 빈 음성을 반환했습니다.");
                }
                if (data.length > MAX_PCM_BYTES) {
                    throw new IllegalStateException("GPT-SoVITS 음성이 64MB를 넘었습니다.");
                }
                return new PcmAudio(pcmFormat, data, PcmAudio.millisOf(pcmFormat, data.length));
            }
        }
    }
}
