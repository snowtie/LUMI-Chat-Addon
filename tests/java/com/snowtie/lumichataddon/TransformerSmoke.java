package com.snowtie.lumichataddon;

import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;

public final class TransformerSmoke {
    private static byte[] read(ZipFile archive, String name) throws Exception {
        try (var input = archive.getInputStream(archive.getEntry(name))) {
            return input.readAllBytes();
        }
    }

    private static void checkTransformed(
            LumiChatTransformer transformer,
            byte[] original,
            String className,
            String... hooks) {
        byte[] transformed = transformer.transform(className, original);
        if (Arrays.equals(original, transformed)) {
            throw new AssertionError("class was not transformed: " + className);
        }
        var errors = ClassFile.of().verify(transformed);
        if (!errors.isEmpty()) {
            throw new AssertionError("JVM verification failed: " + errors);
        }
        String binary = new String(transformed, StandardCharsets.ISO_8859_1);
        for (String hook : hooks) {
            if (!binary.contains(hook)) {
                throw new AssertionError("hook constant missing: " + hook);
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Pass the LUMI Chat plugin JAR.");
        }
        String prefix = "lumi.chat.addon.";
        LumiChatTransformer transformer = new LumiChatTransformer(
                prefix + "dialog.init",
                prefix + "dialog.load",
                prefix + "dialog.apply");
        try (ZipFile archive = new ZipFile(Path.of(arguments[0]).toFile())) {
            checkTransformed(
                    transformer,
                    read(archive, "com/group_finity/mascot/lumi/ai/AiSettingsDialog.class"),
                    "com/group_finity/mascot/lumi/ai/AiSettingsDialog",
                    prefix + "dialog.init",
                    prefix + "dialog.load",
                    prefix + "dialog.apply");
        }
        System.out.println("transformer smoke test passed");
    }
}
