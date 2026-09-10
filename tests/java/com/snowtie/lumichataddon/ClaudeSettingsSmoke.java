package com.snowtie.lumichataddon;

public final class ClaudeSettingsSmoke {
    public static final class Settings {
        private String model;
        private int writes;

        Settings(String model) { this.model = model; }

        public String llmModelFor(String provider) {
            if (!"claude_account".equals(provider) && !"gpt_web".equals(provider)) throw new AssertionError(provider);
            return model;
        }

        public void set(String key, String value) {
            if (!"llm.model.claude_account".equals(key) && !"llm.model.gpt_web".equals(key)) throw new AssertionError(key);
            model = value;
            writes++;
        }
    }

    public static void main(String[] args) throws Exception {
        Settings gpt = new Settings("user-selected-model");
        LumiChatAddonPlugin.initializeModel(gpt, "gpt_web", "gpt-5.6-luna");
        if (!"user-selected-model".equals(gpt.model) || gpt.writes != 0) throw new AssertionError("GPT model reset");
        for (String provider : new String[] { "ChatGPT", "Claude" }) {
            AddonSettingsDialog.requireConnected(java.util.Map.of("connected", true), provider);
            try {
                AddonSettingsDialog.requireConnected(java.util.Map.of("connected", false), provider);
                throw new AssertionError("Unfinished login was accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains(provider)) throw expected;
            }
        }
        for (String model : new String[] { "opus", "haiku", "claude-sonnet-4-6", "sonnet[1m]" }) {
            Settings settings = new Settings(model);
            LumiChatAddonPlugin.initializeClaudeModel(settings);
            LumiChatAddonPlugin.initializeClaudeModel(settings);
            if (!model.equals(settings.model) || settings.writes != 0) throw new AssertionError("model reset: " + model);
        }
        for (String model : new String[] { null, "", " " }) {
            Settings settings = new Settings(model);
            LumiChatAddonPlugin.initializeClaudeModel(settings);
            LumiChatAddonPlugin.initializeClaudeModel(settings);
            if (!"sonnet".equals(settings.model) || settings.writes != 1) throw new AssertionError("default initialization failed");
        }
        System.out.println("Claude model persistence smoke test passed");
    }
}
