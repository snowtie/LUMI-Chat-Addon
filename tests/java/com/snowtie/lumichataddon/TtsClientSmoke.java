package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.ai.AiSettings;
import com.group_finity.mascot.lumi.ai.TtsClient;

public final class TtsClientSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] arguments) throws Exception {
        AiSettings settings = AiSettings.get();
        settings.set("tts.provider", "gpt_sovits");

        check(!TtsClient.configured(), "GPT-SoVITS was configured without the addon runtime");
        try {
            TtsClient.synthesize("테스트");
            throw new AssertionError("GPT-SoVITS synthesis unexpectedly succeeded");
        } catch (IllegalStateException error) {
            check(!"no-tts-key".equals(error.getMessage()), "GPT-SoVITS fell through to Fish Audio");
            check(error.getMessage().contains("아직 시작되지 않았습니다"),
                    "unexpected GPT-SoVITS routing error: " + error.getMessage());
        }

        settings.set("tts.provider", "fish");
        settings.set("tts.fish.key", "");
        try {
            TtsClient.synthesize("테스트");
            throw new AssertionError("Fish Audio synthesis unexpectedly succeeded");
        } catch (IllegalStateException error) {
            check("no-tts-key".equals(error.getMessage()), "Fish Audio fallback behavior changed");
        }
        System.out.println("tts client smoke test passed");
    }
}
