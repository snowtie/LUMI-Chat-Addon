package com.snowtie.lumichataddon;

import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import com.group_finity.mascot.lumi.plugin.PluginHooks;

public final class TransformerSmoke {
    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private static void checkApplyReturns(LumiChatTransformer transformer, String hook) throws Exception {
        String name = "com/group_finity/mascot/lumi/ai/AiSettingsDialog";
        ClassDesc type = ClassDesc.of(name.replace('/', '.'));
        ClassDesc bool = ClassDesc.ofDescriptor("Z");
        ClassDesc object = ClassDesc.of("java.lang.Object");
        MethodTypeDesc noArgs = MethodTypeDesc.ofDescriptor("()V");
        int[] calls = {0};
        PluginHooks.Handler handler = (key, value, context) -> { calls[0]++; return value; };
        var register = PluginHooks.class.getDeclaredMethod("register", String.class, String.class, PluginHooks.Handler.class);
        var unregister = PluginHooks.class.getDeclaredMethod("unregister", String.class, String.class, PluginHooks.Handler.class);
        register.setAccessible(true);
        unregister.setAccessible(true);
        register.invoke(null, "test", hook, handler);
        try {
            for (boolean booleanReturn : new boolean[] {false, true}) {
                byte[] bytes = ClassFile.of().build(type, builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC);
                    builder.withField("success", bool, ClassFile.ACC_PUBLIC);
                    builder.withMethodBody("<init>", noArgs, ClassFile.ACC_PUBLIC,
                            code -> code.aload(0).invokespecial(object, "<init>", noArgs).return_());
                    builder.withMethodBody("apply", MethodTypeDesc.ofDescriptor(booleanReturn ? "()Z" : "()V"),
                            ClassFile.ACC_PUBLIC, code -> {
                                if (booleanReturn) code.aload(0).getfield(type, "success", bool).ireturn();
                                else code.return_();
                            });
                });
                Class<?> fixture = new FixtureLoader().define(transformer.transform(name, bytes));
                Object dialog = fixture.getConstructor().newInstance();
                var apply = fixture.getMethod("apply");
                calls[0] = 0;
                Object result = apply.invoke(dialog);
                if (booleanReturn) {
                    if (!Boolean.FALSE.equals(result) || calls[0] != 0) {
                        throw new AssertionError("failed validation must not save addon settings");
                    }
                    fixture.getField("success").setBoolean(dialog, true);
                    if (!Boolean.TRUE.equals(apply.invoke(dialog)) || calls[0] != 1) {
                        throw new AssertionError("successful boolean apply must save once and keep its result");
                    }
                } else if (result != null || calls[0] != 1) {
                    throw new AssertionError("legacy void apply must save once");
                }
            }
        } finally {
            unregister.invoke(null, "test", hook, handler);
        }
    }
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
        if (className.endsWith("TtsClient") && !Arrays.equals(transformed, transformer.transform(className, transformed))) {
            throw new AssertionError("TTS transform must be idempotent");
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
        checkApplyReturns(transformer, prefix + "dialog.apply");
        try (ZipFile archive = new ZipFile(Path.of(arguments[0]).toFile())) {
            checkTransformed(transformer,
                    read(archive, "com/group_finity/mascot/lumi/ai/TtsClient.class"),
                    "com/group_finity/mascot/lumi/ai/TtsClient", "gptSovitsSelected", "synthesizeGptSovits");
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
