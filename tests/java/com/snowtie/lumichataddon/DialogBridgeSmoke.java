package com.snowtie.lumichataddon;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.util.Properties;

public final class DialogBridgeSmoke {
    private static final class FakeDialog extends JPanel {
        private final JComboBox<String> ttsProvider = new JComboBox<>(new String[] {"Fish Audio", "ElevenLabs"});
        private final JPasswordField fishKey = new JPasswordField();
        private final JTextField fishVoice = new JTextField();
        private final JComboBox<String> fishModel = new JComboBox<>();
        private final JPasswordField elevenKey = new JPasswordField();
        private final JTextField elevenVoice = new JTextField();
        private final JTextField elevenModel = new JTextField();
        private final JLabel status = new JLabel("직전 음성 재생 실패: Fish API 키가 비어 있어요");
        private final JPanel fishPanel = new JPanel();
        private final JPanel elevenPanel = new JPanel();
        private final JButton fishSite = new JButton("Fish Audio 사이트 열기");

        FakeDialog() {
            fishPanel.add(fishKey);
            fishPanel.add(fishVoice);
            fishPanel.add(fishModel);
            elevenPanel.add(elevenKey);
            elevenPanel.add(elevenVoice);
            elevenPanel.add(elevenModel);
            add(ttsProvider);
            add(fishPanel);
            add(elevenPanel);
            add(fishSite);
            add(status);
            ttsProvider.addActionListener(event -> {
                boolean eleven = ttsProvider.getSelectedIndex() == 1;
                fishPanel.setVisible(!eleven);
                elevenPanel.setVisible(eleven);
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] arguments) {
        FakeDialog dialog = new FakeDialog();
        Properties properties = new Properties();
        properties.setProperty("tts.provider", "gpt_sovits");
        DialogBridge.update(dialog, false, properties);

        check(dialog.ttsProvider.getItemCount() == 3, "GPT-SoVITS option was not added");
        check("GPT-SoVITS".equals(dialog.ttsProvider.getSelectedItem()), "GPT-SoVITS was not selected");
        check(!dialog.fishPanel.isVisible(), "Fish Audio panel remained visible");
        check(!dialog.elevenPanel.isVisible(), "ElevenLabs panel remained visible");
        check(!dialog.fishSite.isVisible(), "Fish Audio site button remained visible");
        check(dialog.status.getText().isEmpty(), "stale Fish Audio error remained visible");

        dialog.ttsProvider.setSelectedItem("Fish Audio");
        check(dialog.fishPanel.isVisible(), "Fish Audio panel did not return");
        check(!dialog.elevenPanel.isVisible(), "ElevenLabs panel appeared for Fish Audio");
        check(dialog.fishSite.isVisible(), "Fish Audio site button did not return");

        dialog.ttsProvider.setSelectedItem("ElevenLabs");
        check(!dialog.fishPanel.isVisible(), "Fish Audio panel appeared for ElevenLabs");
        check(dialog.elevenPanel.isVisible(), "ElevenLabs panel did not appear");
        check(dialog.fishSite.isVisible(), "existing ElevenLabs footer behavior changed");
        System.out.println("dialog bridge smoke test passed");
    }
}
