package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.PluginContext;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class AddonSettingsDialog extends JDialog {
    private static AddonSettingsDialog instance;

    private final PluginContext context;
    private final RuntimeManager runtime;
    private final JLabel chatGptStatus = new JLabel("확인 중");
    private final JLabel claudeStatus = new JLabel("확인 중");
    private final JLabel ttsStatus = new JLabel("확인 중");
    private final JButton chatGptButton = new JButton("ChatGPT 계정 연결");
    private final JButton claudeButton = new JButton("Claude 계정 연결");
    private final JButton ttsButton = new JButton("GPT-SoVITS 설치 또는 복구");

    static void show(PluginContext context, RuntimeManager runtime) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new AddonSettingsDialog(context, runtime);
        }
        instance.setVisible(true);
        instance.toFront();
        instance.refresh();
    }

    private AddonSettingsDialog(PluginContext context, RuntimeManager runtime) {
        super((java.awt.Window) null, "LUMI Chat Addon 설정");
        this.context = context;
        this.runtime = runtime;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(content(), BorderLayout.CENTER);
        pack();
        setMinimumSize(new Dimension(520, 390));
        setLocationRelativeTo(null);
        context.manageWindow(this, "lumi-chat-addon-settings");

        chatGptButton.addActionListener(event -> connectChatGpt());
        claudeButton.addActionListener(event -> connectClaude());
        ttsButton.addActionListener(event -> installTts());
    }

    private JPanel content() {
        JPanel root = new JPanel();
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel("<html><b>LUMI Chat Addon 1.1.0</b><br>"
                + "대화와 기억은 LUMI Chat이 맡고, 이 플러그인은 계정과 로컬 음성을 연결합니다.</html>");
        root.add(intro);
        root.add(Box.createVerticalStrut(14));
        root.add(row("ChatGPT", "기본 모델 GPT-5.6 Luna · 낮은 추론", chatGptStatus, chatGptButton));
        root.add(Box.createVerticalStrut(10));
        root.add(row("Claude", "공식 Claude Code 로그인과 구독 사용량을 사용", claudeStatus, claudeButton));
        root.add(Box.createVerticalStrut(10));
        root.add(row("LUMI GPT-SoVITS", "선택 기능 · 설치 창이 완료될 때까지 닫지 마세요", ttsStatus, ttsButton));
        root.add(Box.createVerticalStrut(14));
        JLabel note = new JLabel("<html>사용할 두뇌는 루미 AI 설정의 프로바이더에서 고릅니다.<br>"
                + "집중 모드에서는 GPT-SoVITS 소리가 재생되지 않습니다.</html>");
        root.add(note);
        return root;
    }

    private static JPanel row(String title, String description, JLabel status, JButton action) {
        JPanel row = new JPanel(new BorderLayout(12, 5));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel label = new JLabel("<html><b>" + title + "</b><br><small>" + description + "</small></html>");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bottom.add(status);
        bottom.add(action);
        row.add(label, BorderLayout.NORTH);
        row.add(bottom, BorderLayout.CENTER);
        return row;
    }

    private void refresh() {
        setBusy(true);
        context.offEdt(() -> {
            String chat = "연결 필요";
            String claude = "설치 또는 연결 필요";
            String tts = ttsInstalled() ? "설치됨" : "설치 필요";
            try {
                runtime.ensureStarted();
                Map<?, ?> state = runtime.client().get("/auth/status");
                chat = Boolean.TRUE.equals(state.get("connected")) ? "연결됨" : "연결 필요";
                state = runtime.client().get("/claude/auth/status");
                if (Boolean.TRUE.equals(state.get("connected"))) {
                    claude = "연결됨";
                } else if (Boolean.TRUE.equals(state.get("installed"))) {
                    claude = "연결 필요";
                }
            } catch (Exception error) {
                context.log().fine("settings status: " + error.getMessage());
            }
            String chatResult = chat;
            String claudeResult = claude;
            String ttsResult = tts;
            SwingUtilities.invokeLater(() -> {
                chatGptStatus.setText(chatResult);
                claudeStatus.setText(claudeResult);
                ttsStatus.setText(ttsResult);
                setBusy(false);
            });
        });
    }

    private void connectChatGpt() {
        setBusy(true);
        context.offEdt(() -> {
            try {
                runtime.ensureCodexRuntime();
                Map<?, ?> result = runtime.client().post("/auth/login", Map.of());
                String code = string(result, "userCode");
                String url = string(result, "verificationUrl");
                if (url.isBlank()) {
                    url = string(result, "authUrl");
                }
                if (!url.isBlank() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
                if (!code.isBlank()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
                }
                String finalCode = code;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        finalCode.isBlank()
                                ? "브라우저에서 ChatGPT 계정 연결을 완료해 주세요."
                                : "로그인 코드 " + finalCode + "를 복사했습니다. 브라우저에 붙여넣어 주세요.",
                        "ChatGPT 계정 연결",
                        JOptionPane.INFORMATION_MESSAGE));
                selectProvider("gpt_web");
                SwingUtilities.invokeLater(() -> {
                    Timer timer = new Timer(2000, event -> refresh());
                    timer.setRepeats(false);
                    timer.start();
                });
            } catch (Exception error) {
                showError(error);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private void connectClaude() {
        setBusy(true);
        context.offEdt(() -> {
            try {
                runtime.client().post("/claude/auth/login", Map.of());
                selectProvider("claude_account");
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "열린 창에서 Claude Code 설치와 로그인을 끝낸 뒤 새로고침해 주세요.",
                        "Claude 계정 연결",
                        JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception error) {
                showError(error);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private void installTts() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "GPT-SoVITS 공식 통합판은 약 6~9GB입니다. 다운로드 완료 메시지가 나올 때까지 설치 창을 닫지 마세요.",
                "GPT-SoVITS 설치",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        setBusy(true);
        context.offEdt(() -> {
            try {
                runtime.installTts();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "설치 창을 열었습니다. 완료 메시지가 나온 뒤 Little LUMI를 다시 시작해 주세요.",
                        "GPT-SoVITS 설치",
                        JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception error) {
                showError(error);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private boolean ttsInstalled() {
        Path root = runtime.dataRoot();
        return Files.isRegularFile(root.resolve("gpt-sovits-runtime-selection.json"))
                && Files.isDirectory(root.resolve("models").resolve("LUMI-v2"));
    }

    private void selectProvider(String id) throws Exception {
        Class<?> settingsType = Class.forName("com.group_finity.mascot.lumi.ai.AiSettings");
        Object settings = settingsType.getMethod("get").invoke(null);
        settingsType.getMethod("set", String.class, String.class).invoke(settings, "llm.provider", id);
        settingsType.getMethod("save").invoke(settings);
    }

    private void setBusy(boolean busy) {
        chatGptButton.setEnabled(!busy);
        claudeButton.setEnabled(!busy);
        ttsButton.setEnabled(!busy);
    }

    private void showError(Exception error) {
        context.log().warning(error.toString());
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                error.getMessage(),
                "LUMI Chat Addon 오류",
                JOptionPane.ERROR_MESSAGE));
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
