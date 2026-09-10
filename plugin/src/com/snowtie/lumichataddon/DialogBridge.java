package com.snowtie.lumichataddon;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Properties;

final class DialogBridge {
    private static final String LABEL = "GPT-SoVITS";
    private static final String UI_BOUND = "lumi.chat.addon.tts.ui.bound";
    private static final String FISH_PANEL = "lumi.chat.addon.tts.ui.fish";
    private static final String ELEVEN_PANEL = "lumi.chat.addon.tts.ui.eleven";
    private static final String CUSTOM_PANEL = "lumi.chat.addon.tts.ui.custom";
    private static final String FISH_SITE_BUTTON = "lumi.chat.addon.tts.ui.fishSite";
    private static final String STATUS_LABEL = "lumi.chat.addon.tts.ui.status";

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
            bindProviderUi(dialog, provider);
            if (apply) {
                if (LABEL.equals(provider.getSelectedItem())) {
                    setProvider("gpt_sovits");
                }
            } else if ("gpt_sovits".equals(properties.getProperty("tts.provider"))) {
                provider.setSelectedItem(LABEL);
            }
            syncProviderUi(provider);
        } catch (ReflectiveOperationException ignored) {
            // LUMI Chat UI 구조가 바뀌면 음성 자체는 계속 동작하고 이 표시만 생략됩니다.
        }
    }

    private static void bindProviderUi(Object dialog, JComboBox<String> provider)
            throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(provider.getClientProperty(UI_BOUND))) {
            return;
        }
        JPanel fishPanel = commonPanel(
                component(dialog, "fishKey"),
                component(dialog, "fishVoice"),
                component(dialog, "fishModel"));
        JPanel elevenPanel = commonPanel(
                component(dialog, "elevenKey"),
                component(dialog, "elevenVoice"),
                component(dialog, "elevenModel"));
        JButton fishSiteButton = findButton((Container) dialog, "Fish Audio");
        JLabel statusLabel = (JLabel) component(dialog, "status");

        provider.putClientProperty(FISH_PANEL, fishPanel);
        provider.putClientProperty(ELEVEN_PANEL, elevenPanel);
        try {
            provider.putClientProperty(CUSTOM_PANEL, commonPanel(
                    component(dialog, "customTtsBase"), component(dialog, "customTtsModel"),
                    component(dialog, "customTtsVoice"), component(dialog, "customTtsKey")));
        } catch (NoSuchFieldException ignored) {
            // 이전 LUMI Chat에는 사용자 지정 TTS 항목이 없습니다.
        }
        provider.putClientProperty(FISH_SITE_BUTTON, fishSiteButton);
        provider.putClientProperty(STATUS_LABEL, statusLabel);
        provider.addActionListener(event -> syncProviderUi(provider));
        provider.putClientProperty(UI_BOUND, Boolean.TRUE);
    }

    private static Component component(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Component) field.get(target);
    }

    private static JPanel commonPanel(Component first, Component... rest) {
        for (Container parent = first.getParent(); parent != null; parent = parent.getParent()) {
            if (!(parent instanceof JPanel panel)) {
                continue;
            }
            boolean containsAll = true;
            for (Component component : rest) {
                if (!SwingUtilities.isDescendingFrom(component, panel)) {
                    containsAll = false;
                    break;
                }
            }
            if (containsAll) {
                return panel;
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String textPart) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getText() != null
                    && button.getText().contains(textPart)) {
                return button;
            }
            if (child instanceof Container container) {
                JButton found = findButton(container, textPart);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void syncProviderUi(JComboBox<String> provider) {
        boolean gptSovits = LABEL.equals(provider.getSelectedItem());
        boolean eleven = "ElevenLabs".equals(provider.getSelectedItem());
        boolean custom = !gptSovits && provider.getClientProperty(CUSTOM_PANEL) != null
                && provider.getSelectedIndex() == 2;
        setVisible(provider.getClientProperty(FISH_PANEL), !gptSovits && !eleven && !custom);
        setVisible(provider.getClientProperty(ELEVEN_PANEL), !gptSovits && eleven);
        setVisible(provider.getClientProperty(CUSTOM_PANEL), custom);
        setVisible(provider.getClientProperty(FISH_SITE_BUTTON), !gptSovits);
        if (gptSovits) {
            clearStaleFishError(provider.getClientProperty(STATUS_LABEL));
        }
        JComponent root = (JComponent) SwingUtilities.getRootPane(provider);
        if (root != null) {
            root.revalidate();
            root.repaint();
        }
    }

    private static void setVisible(Object value, boolean visible) {
        if (value instanceof Component component) {
            component.setVisible(visible);
        }
    }

    private static void clearStaleFishError(Object value) {
        if (!(value instanceof JLabel status) || status.getText() == null
                || !status.getText().toLowerCase(Locale.ROOT).contains("fish")) {
            return;
        }
        status.setText("");
        try {
            Class<?> chatService = Class.forName("com.group_finity.mascot.lumi.ai.ChatService");
            Method clearError = chatService.getDeclaredMethod("clearTtsError");
            clearError.setAccessible(true);
            clearError.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // 현재 창의 잘못된 오류 표시는 이미 지웠으므로 이전 버전에서도 계속 동작합니다.
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
