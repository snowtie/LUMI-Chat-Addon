package com.snowtie.lumichataddon;

import com.group_finity.mascot.lumi.plugin.PluginContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RuntimeManager {
    static final String BASE_URL = "http://127.0.0.1:32123";
    static final String HELPER_NAME = "lumi-chat-addon-helper-v1.1.0-windows-x64.exe";
    private static final String RELEASE =
            "https://github.com/snowtie/LUMI-Chat-Addon/releases/download/v1.1.0/";
    private static final String HELPER_URL = RELEASE + HELPER_NAME;
    private static final String CHECKSUM_URL = RELEASE + "SHA256SUMS.txt";
    private static final String CODEX_VERSION = "0.153.4";
    private static final String CODEX_ARCHIVE =
            "codex-app-server-" + CODEX_VERSION + "-windows-x64.zip";
    private static final String CODEX_URL =
            "https://github.com/openai/codex/releases/download/rust-v0.153.4/"
                    + "codex-app-server-x86_64-pc-windows-msvc.exe.zip";
    private static final String CODEX_SHA256 =
            "b944b854a150bd3c269d9f17cf58756bc29fa248e93d9e6cd1dac3cee1d8a774";

    private final PluginContext context;
    private final BridgeClient client = new BridgeClient();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path runtimeDir;
    private final Path dataRoot;
    private Process helper;

    RuntimeManager(PluginContext context) {
        this.context = context;
        this.dataRoot = addonRoot();
        this.runtimeDir = dataRoot.resolve("runtime");
        try {
            migrateLegacyData();
        } catch (IOException error) {
            context.log().warning("Legacy data migration: " + error.getMessage());
        }
    }

    BridgeClient client() throws Exception {
        ensureStarted();
        return client;
    }

    synchronized void ensureStarted() throws Exception {
        stopLegacyHelper();
        if (client.healthy()) {
            return;
        }
        Files.createDirectories(runtimeDir);
        Path executable = ensureHelper();
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--headless");
        builder.environment().put("LUMI_APP_DIR", appDir().toString());
        builder.environment().put("LUMI_CHAT_ADDON_DATA_DIR", dataRoot.toString());
        builder.redirectError(dataRoot.resolve("helper-error.log").toFile());
        builder.redirectOutput(dataRoot.resolve("helper.log").toFile());
        helper = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (client.healthy()) {
                return;
            }
            if (!helper.isAlive()) {
                throw new IOException("계정 도우미가 시작 중 종료되었습니다. helper-error.log를 확인해 주세요.");
            }
            Thread.sleep(200);
        }
        throw new IOException("계정 도우미 시작 시간이 초과되었습니다.");
    }

    synchronized Path ensureCodexRuntime() throws Exception {
        Files.createDirectories(runtimeDir);
        Path target = runtimeDir.resolve("codex-app-server.exe");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path legacy = legacyRoot().resolve("app").resolve("codex-app-server.exe");
        if (Files.isRegularFile(legacy)) {
            Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        Path archive = dataRoot.resolve("downloads").resolve(CODEX_ARCHIVE);
        download(CODEX_URL, archive);
        verify(archive, CODEX_SHA256);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            boolean found = false;
            while ((entry = zip.getNextEntry()) != null) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (name.equalsIgnoreCase("codex-app-server-x86_64-pc-windows-msvc.exe")) {
                    Path temporary = target.resolveSibling(target.getFileName() + ".part");
                    Files.copy(zip, temporary, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("공식 Codex 압축 파일에서 App Server를 찾지 못했습니다.");
            }
        }
        return target;
    }

    synchronized Process installTts() throws Exception {
        Files.createDirectories(dataRoot);
        Path script = dataRoot.resolve("tts-setup.ps1");
        Path manifest = dataRoot.resolve("tts-runtimes.json");
        extractResource("/tts-setup.ps1", script);
        extractResource("/tts-runtimes.json", manifest);
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "-LumiAppPath",
                appDir().toString(),
                "-DataRoot",
                dataRoot.toString(),
                "-RuntimeManifest",
                manifest.toString());
        return builder.inheritIO().start();
    }

    void noteVoiceUse() {
        context.prefs().set("last_voice_use_unix", System.currentTimeMillis() / 1000L);
    }

    synchronized void stop() {
        if (helper != null && helper.isAlive()) {
            helper.destroy();
            try {
                if (!helper.waitFor(3, TimeUnit.SECONDS)) {
                    helper.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                helper.destroyForcibly();
            }
        }
        helper = null;
    }

    Path appDir() {
        return context.dataDir().toAbsolutePath().normalize().getParent().getParent();
    }

    Path dataRoot() {
        return dataRoot;
    }

    private Path ensureHelper() throws Exception {
        Path target = runtimeDir.resolve(HELPER_NAME);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path local = localReleaseHelper();
        if (local != null) {
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        String checksums = getText(CHECKSUM_URL);
        String expected = checksums.lines()
                .map(String::trim)
                .filter(line -> line.toLowerCase(Locale.ROOT).endsWith("  " + HELPER_NAME.toLowerCase(Locale.ROOT)))
                .map(line -> line.split("\\s+", 2)[0])
                .findFirst()
                .orElseThrow(() -> new IOException("배포 체크섬에서 계정 도우미를 찾지 못했습니다."));
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        download(HELPER_URL, temporary);
        verify(temporary, expected);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private Path localReleaseHelper() {
        try {
            URI location = LumiChatAddonPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jar = Path.of(location);
            Path modRoot = jar.getParent().getParent();
            Path candidate = modRoot.resolve("tools").resolve(HELPER_NAME);
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void stopLegacyHelper() {
        Path expected = legacyRoot().resolve("app").resolve("lumi-to-gpt.exe").toAbsolutePath().normalize();
        ProcessHandle.allProcesses().forEach(process -> process.info().command().ifPresent(command -> {
            try {
                boolean mcpServer = process.info().arguments()
                        .map(arguments -> java.util.Arrays.asList(arguments).contains("--mcp"))
                        .orElse(false);
                if (!mcpServer && Path.of(command).toAbsolutePath().normalize().equals(expected)) {
                    process.destroyForcibly();
                    process.onExit().get(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
                // 다른 사용자나 보호된 프로세스는 건너뜁니다.
            }
        }));
    }

    private Path legacyRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "LumiToGPT")
                : Path.of(localAppData, "LumiToGPT");
    }

    private Path addonRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? context.dataDir().resolve("runtime-data")
                : Path.of(localAppData, "LumiChatAddon");
    }

    private void migrateLegacyData() throws IOException {
        Path legacy = legacyRoot();
        if (!Files.isDirectory(legacy) || legacy.equals(dataRoot)) {
            return;
        }
        Files.createDirectories(dataRoot);
        for (String name : new String[]{"gpt-sovits", "models", "downloads"}) {
            Path source = legacy.resolve(name);
            Path target = dataRoot.resolve(name);
            if (Files.exists(source) && !Files.exists(target)) {
                Files.move(source, target);
            }
        }
        Path selection = legacy.resolve("gpt-sovits-runtime-selection.json");
        if (Files.isRegularFile(selection) && !Files.exists(dataRoot.resolve(selection.getFileName()))) {
            Files.move(selection, dataRoot.resolve(selection.getFileName()));
        }
        Path settings = legacy.resolve("settings.json");
        Path migratedSettings = dataRoot.resolve("settings.json");
        if (Files.isRegularFile(settings) && !Files.exists(migratedSettings)) {
            String text = Files.readString(settings, StandardCharsets.UTF_8)
                    .replace(legacy.toString(), dataRoot.toString())
                    .replace(legacy.toString().replace("\\", "\\\\"),
                            dataRoot.toString().replace("\\", "\\\\"));
            Files.writeString(migratedSettings, text, StandardCharsets.UTF_8);
        }
    }

    private void extractResource(String name, Path target) throws IOException {
        try (InputStream input = RuntimeManager.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("플러그인 리소스가 없습니다: " + name);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String getText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("다운로드 HTTP " + response.statusCode() + ": " + url);
        }
        return response.body();
    }

    private void download(String url, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();
        HttpResponse<Path> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(target);
            throw new IOException("다운로드 HTTP " + response.statusCode() + ": " + url);
        }
    }

    private static void verify(Path path, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected.trim())) {
            Files.deleteIfExists(path);
            throw new IOException("다운로드 파일의 SHA-256이 올바르지 않습니다: " + path.getFileName());
        }
    }
}
