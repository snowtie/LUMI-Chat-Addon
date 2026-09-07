package com.snowtie.lumichataddon;

import javax.swing.JComboBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

final class DialogBridge {
    private static final String LABEL = "GPT-SoVITS";

    private DialogBridge() {
    }

    static void update(Object dialog, boolean apply, Properties properties) {
        try {
            Field field = dialog.getClass().getDeclaredField("ttsProvider");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            JComboBox<String> provider = (JComboBox<String>) field.get(dialog);
            if (!contains(provider, LABEL)) {
                provider.addItem(LABEL);
            }
            if (apply) {
                if (LABEL.equals(provider.getSelectedItem())) {
                    setProvider("gpt_sovits");
                }
            } else if ("gpt_sovits".equals(properties.getProperty("tts.provider"))) {
                provider.setSelectedItem(LABEL);
            }
        } catch (ReflectiveOperationException ignored) {
            // LUMI Chat UI 구조가 바뀌면 음성 자체는 계속 동작하고 이 표시만 생략됩니다.
        }
    }

    private static boolean contains(JComboBox<String> comboBox, String item) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (item.equals(comboBox.getItemAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static void setProvider(String provider) throws ReflectiveOperationException {
        Class<?> settingsType = Class.forName("com.group_finity.mascot.lumi.ai.AiSettings");
        Object settings = settingsType.getMethod("get").invoke(null);
        Method set = settingsType.getMethod("set", String.class, String.class);
        set.invoke(settings, "tts.provider", provider);
        settingsType.getMethod("save").invoke(settings);
    }
}
