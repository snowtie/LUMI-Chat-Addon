package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.LumiPlugin;
import com.group_finity.mascot.lumi.plugin.PcmAudio;
import com.group_finity.mascot.lumi.plugin.PluginContext;
import com.group_finity.mascot.lumi.plugin.PluginHooks;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public final class LumiChatAddonPlugin implements LumiPlugin {
    public static final String ID = "lumi.chat.addon";
    public static final String VERSION = "1.1.0";
    private static final String TTS_CONFIGURED_HOOK = ID + ".tts.configured";
    private static final String TTS_SYNTHESIZE_HOOK = ID + ".tts.synthesize";
    private static final String DIALOG_INIT_HOOK = ID + ".dialog.init";
    private static final String DIALOG_LOAD_HOOK = ID + ".dialog.load";
    private static final String DIALOG_APPLY_HOOK = ID + ".dialog.apply";

    private PluginContext context;
    private RuntimeManager runtime;

    @Override
    public void start(PluginContext context) {
        this.context = context;
        this.runtime = new RuntimeManager(context);
        installProviders();
        registerHooks();
        registerTransformers();

        context.addTrayItem("LUMI Chat Addon 설정", this::openSettings);
        context.addSettingsButton("계정 및 GPT-SoVITS 설정", this::openSettings);
        context.offEdt(() -> {
            try {
                runtime.ensureStarted();
            } catch (Exception error) {
                context.log().warning("helper startup deferred: " + error.getMessage());
            }
        });
        context.log().info("LUMI Chat Addon " + VERSION + " started");
    }

    @Override
    public void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void openSettings() {
        context.onEdt(() -> AddonSettingsDialog.show(context, runtime));
    }

    @SuppressWarnings("unchecked")
    private void installProviders() {
        try {
            Class<?> settingsType = Class.forName("com.group_finity.mascot.lumi.ai.AiSettings");
            Map<String, String[]> providers =
                    (Map<String, String[]>) settingsType.getField("LLM_PROVIDERS").get(null);
            providers.put("gpt_web", new String[] {
                    "ChatGPT 계정", RuntimeManager.BASE_URL + "/v1", "gpt-5.6-luna", "openai"
            });
            providers.put("claude_account", new String[] {
                    "Claude 계정", RuntimeManager.BASE_URL + "/claude/v1", "sonnet", "openai"
            });

            Object settings = settingsType.getMethod("get").invoke(null);
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.base.gpt_web", RuntimeManager.BASE_URL + "/v1");
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.model.gpt_web", "gpt-5.6-luna");
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.key.gpt_web", "account");
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.base.claude_account", RuntimeManager.BASE_URL + "/claude/v1");
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.model.claude_account", "sonnet");
            settingsType.getMethod("set", String.class, String.class)
                    .invoke(settings, "llm.key.claude_account", "account");
            if (!context.prefs().getBoolean("provider_initialized", false)) {
                settingsType.getMethod("set", String.class, String.class)
                        .invoke(settings, "llm.provider", "gpt_web");
                settingsType.getMethod("set", String.class, String.class)
                        .invoke(settings, "chatter.enabled", "false");
                settingsType.getMethod("set", String.class, String.class)
                        .invoke(settings, "screenwatch.enabled", "false");
                context.prefs().set("provider_initialized", true);
            }
            settingsType.getMethod("save").invoke(settings);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("LUMI Chat 1.1.0 이상이 필요합니다.", error);
        }
    }

    private void registerHooks() {
        context.hook(TTS_CONFIGURED_HOOK, this::configuredHook);
        context.hook(TTS_SYNTHESIZE_HOOK, this::synthesizeHook);
        context.hook(DIALOG_INIT_HOOK, this::dialogHook);
        context.hook(DIALOG_LOAD_HOOK, this::dialogHook);
        context.hook(DIALOG_APPLY_HOOK, this::dialogHook);
    }

    private Object configuredHook(String hook, Object current, Map<String, Object> values) {
        if (!"gpt_sovits".equals(ttsProvider())) {
            return null;
        }
        return !context.quietNow() && voiceSettings().getProperty("tts.enabled", "false").equals("true");
    }

    private Object synthesizeHook(String hook, Object current, Map<String, Object> values) {
        if (!"gpt_sovits".equals(ttsProvider())) {
            return null;
        }
        if (context.quietNow()) {
            throw new IllegalStateException("집중 모드라서 음성을 재생하지 않습니다.");
        }
        String text = String.valueOf(values.getOrDefault("text", "")).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("읽을 문장이 비어 있습니다.");
        }
        try {
            byte[] wav = runtime.client().synthesize(text, voiceSettings());
            PcmAudio audio = WavDecoder.decode(wav);
            runtime.noteVoiceUse();
            return audio;
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private Object dialogHook(String hook, Object current, Map<String, Object> values) {
        Object dialog = current != null ? current : values.get("dialog");
        if (dialog != null) {
            DialogBridge.update(dialog, hook.endsWith("apply"), voiceSettings());
        }
        return dialog;
    }

    private String ttsProvider() {
        try {
            Class<?> settingsType = Class.forName("com.group_finity.mascot.lumi.ai.AiSettings");
            Object settings = settingsType.getMethod("get").invoke(null);
            return String.valueOf(settingsType.getMethod("ttsProvider").invoke(settings));
        } catch (ReflectiveOperationException error) {
            return "";
        }
    }

    private Properties voiceSettings() {
        Properties properties = new Properties();
        Path path = context.dataDir().toAbsolutePath().normalize().getParent()
                .resolve("lumi.ai").resolve("ai.properties");
        if (!Files.isRegularFile(path)) {
            path = appDir().resolve("conf").resolve("ai.properties");
        }
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (Exception error) {
            context.log().fine("voice settings unavailable: " + error.getMessage());
        }
        return properties;
    }

    Path appDir() {
        return context.dataDir().toAbsolutePath().normalize().getParent().getParent();
    }

    private void registerTransformers() {
        LumiChatTransformer transformer = new LumiChatTransformer(
                TTS_CONFIGURED_HOOK,
                TTS_SYNTHESIZE_HOOK,
                DIALOG_INIT_HOOK,
                DIALOG_LOAD_HOOK,
                DIALOG_APPLY_HOOK);
        if (!context.onLoad("com.group_finity.mascot.lumi.ai", transformer)) {
            throw new IllegalStateException("Little LUMI 플러그인 변환기를 등록하지 못했습니다.");
        }
        ClassFileTransformer adapter = transformer.asClassFileTransformer();
        context.patch("com.group_finity.mascot.lumi.ai.TtsClient", adapter);
        context.patch("com.group_finity.mascot.lumi.ai.AiSettingsDialog", adapter);
    }
}
