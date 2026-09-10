package com.snowtie.lumichataddon;

import java.lang.instrument.Instrumentation;

public final class TtsRoutingSmoke {
    private static Instrumentation instrumentation;

    public static void premain(String args, Instrumentation agent) {
        instrumentation = agent;
    }

    public static void main(String[] args) throws Exception {
        if (instrumentation == null) throw new AssertionError("test agent missing");
        String name = "com.group_finity.mascot.lumi.ai.TtsClient";
        Class<?> loaded = args[0].equals("preloaded") ? Class.forName(name) : null;
        LumiChatTransformer transformer = new LumiChatTransformer("test.init", "test.load", "test.apply");
        instrumentation.addTransformer(transformer.asClassFileTransformer(), true);
        if (loaded != null) instrumentation.retransformClasses(loaded);
        TtsClientSmoke.main(new String[0]);
        instrumentation.retransformClasses(Class.forName(name));
        TtsClientSmoke.main(new String[0]);
        System.out.println("TTS original-class routing and retransformation passed: " + args[0]);
    }
}
