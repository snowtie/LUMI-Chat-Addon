from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
RELEASE = PROJECT / "release"
VERSION = "1.1.2"
HELPER = RELEASE / f"lumi-chat-addon-helper-v{VERSION}-windows-x64.exe"
PLUGIN = RELEASE / "workshop-content" / "plugins" / "lumi.chat.addon.jar"
WORKSHOP_ZIP = RELEASE / f"LUMI-Chat-Addon-v{VERSION}-workshop.zip"
UNINSTALLER_ZIP = RELEASE / f"LUMI-to-GPT-Legacy-Uninstaller-v{VERSION}.zip"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_powershell(path: Path) -> None:
    escaped = str(path).replace("'", "''")
    command = (
        "$tokens=$null;$errors=$null;"
        f"[System.Management.Automation.Language.Parser]::ParseFile('{escaped}',[ref]$tokens,[ref]$errors)|Out-Null;"
        "if($errors.Count){$errors|ForEach-Object{$_.ToString()};exit 1}"
    )
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-Command", command],
        text=True,
        capture_output=True,
        encoding="utf-8",
    )
    check(result.returncode == 0, f"PowerShell parse failed for {path}: {result.stdout}{result.stderr}")


def wait_json(url: str, timeout: float = 15.0) -> dict:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            last_error = RuntimeError(f"HTTP {error.code}: {error.read().decode('utf-8', errors='replace')}")
            time.sleep(0.15)
        except Exception as error:
            last_error = error
            time.sleep(0.15)
    raise AssertionError(f"helper did not become ready: {last_error}")


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=3) as response:
        return json.load(response)


def test_artifacts() -> None:
    for path in (HELPER, PLUGIN, WORKSHOP_ZIP, UNINSTALLER_ZIP, RELEASE / "SHA256SUMS.txt"):
        check(path.is_file() and path.stat().st_size > 0, f"missing release artifact: {path}")

    check(not (PROJECT / "install.ps1").exists(), "legacy installer remains in the new repository")
    check(not (PROJECT / "INSTALL.cmd").exists(), "legacy installer launcher remains")
    check(not (PROJECT / "java-patch" / "lumi-to-gpt-little-lumi-patch.jar").exists(), "legacy JAR patch remains")

    with zipfile.ZipFile(PLUGIN) as jar:
        names = set(jar.namelist())
        required = {
            "plugin.json",
            "META-INF/services/com.group_finity.mascot.lumi.plugin.LumiPlugin",
            "com/snowtie/lumichataddon/LumiChatAddonPlugin.class",
            "com/snowtie/lumichataddon/LumiChatTransformer.class",
            "tts-setup.ps1",
            "tts-runtimes.json",
        }
        check(required <= names, f"plugin JAR entries missing: {required - names}")
        check("com/group_finity/mascot/lumi/ai/TtsClient.class" not in names,
              "addon must not shadow the original TtsClient")
        descriptor = json.loads(jar.read("plugin.json"))
        check(descriptor["id"] == "lumi.chat.addon", "wrong plugin id")
        check(descriptor["version"] == VERSION, "wrong plugin version")
        check(descriptor["dependencies"] == ["lumi.ai"], "LUMI Chat dependency missing")
        check(not any("tts." in hook for hook in descriptor["hooks"]), "obsolete TTS hooks remain")
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "tts-setup.ps1"
            script.write_bytes(jar.read("tts-setup.ps1"))
            parse_powershell(script)
            script_text = script.read_text(encoding="utf-8")
            check('.Split("`t", 5)' not in script_text, "voice index metadata can leak into prompt text")
            check('$columns[4].Trim()' in script_text, "voice index text column is not selected")

    with zipfile.ZipFile(WORKSHOP_ZIP) as package:
        names = {name.replace("\\", "/") for name in package.namelist()}
        check("plugins/lumi.chat.addon.jar" in names, "Workshop package has no plugin JAR")
        check("workshop-preview.png" in names, "Workshop package has no upload preview")
        check(not any(Path(name).name.upper() == "DISABLED" for name in names), "Workshop package contains local disabled state")
        check({"LICENSE", "NOTICE.txt", "VOICE_MODEL_NOTICE.txt"} <= names, "Workshop notices are missing")
        check(not any(name.lower().endswith(".exe") for name in names), "Workshop package must not contain EXEs")
        check(not any("install.ps1" in name.lower() for name in names), "Workshop package contains an installer")

    with zipfile.ZipFile(UNINSTALLER_ZIP) as package:
        names = {Path(name).name for name in package.namelist()}
        check({"UNINSTALL.cmd", "uninstall.ps1"} <= names, "legacy uninstaller package is incomplete")

    checksum_entries = {}
    for line in (RELEASE / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(None, 1)
        checksum_entries[name.strip()] = digest
    for path in (HELPER, PLUGIN, WORKSHOP_ZIP, UNINSTALLER_ZIP):
        check(checksum_entries.get(path.name) == sha256(path), f"checksum mismatch: {path.name}")
    check("GPT_weights_v2.7z" in checksum_entries, "voice weights checksum is missing")


def test_scripts() -> None:
    parse_powershell(PROJECT / "build.ps1")
    parse_powershell(PROJECT / "uninstall.ps1")
    parse_powershell(PROJECT / "plugin" / "build.ps1")
    text = (PROJECT / "uninstall.ps1").read_text(encoding="utf-8")
    check("baseJarSha256" in text and "SHA256" in text, "uninstaller does not verify the JAR backup")
    check("[IO.File]::Replace" in text, "uninstaller does not atomically restore the JAR")
    runtime_manager = (
        PROJECT / "plugin" / "src" / "com" / "snowtie" / "lumichataddon" / "RuntimeManager.java"
    ).read_text(encoding="utf-8")
    check('"cmd.exe"' in runtime_manager and '"start"' in runtime_manager, "TTS setup does not open a visible console")
    check("inheritIO()" not in runtime_manager, "TTS setup still inherits the hidden GUI console")


def test_legacy_uninstaller() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        local = root / "Local"
        legacy = local / "LumiToGPT"
        app = root / "Little LUMI" / "app"
        desktop = root / "Desktop"
        (legacy / "app").mkdir(parents=True)
        (legacy / "models" / "LUMI-v2").mkdir(parents=True)
        addon = local / "LumiChatAddon"
        (addon / "models" / "LUMI-v2").mkdir(parents=True)
        (addon / "models" / "LUMI-v2" / "voice.bin").write_bytes(b"new-voice")
        (app / "speech").mkdir(parents=True)
        desktop.mkdir()
        backup = app / "Shimeji-ee.jar.lumi-to-gpt.bak"
        with zipfile.ZipFile(backup, "w") as archive:
            archive.writestr("clean.txt", "original")
        base_hash = sha256(backup)
        with zipfile.ZipFile(app / "Shimeji-ee.jar", "w") as archive:
            archive.writestr("clean.txt", "patched")
            archive.writestr(
                "META-INF/lumi-to-gpt-patch.properties",
                f"name=test\nbaseJarSha256={base_hash}\n",
            )
        (legacy / "app" / "codex-app-server.exe").write_bytes(b"codex")
        (legacy / "models" / "LUMI-v2" / "voice.bin").write_bytes(b"voice")
        (legacy / "settings.json").write_text(
            json.dumps({"lumi_app_dir": str(app), "voice": {"runtime_dir": str(legacy / "gpt-sovits")}}),
            encoding="utf-8",
        )
        (addon / "settings.json").write_text(
            json.dumps({"port": 32123, "voice": {"runtime_dir": str(legacy / "gpt-sovits")}}),
            encoding="utf-8",
        )
        (desktop / "LUMI to GPT.lnk").write_bytes(b"shortcut")
        plugin_settings = app / "plugindata" / "lumi.ai" / "ai.properties"
        plugin_settings.parent.mkdir(parents=True)
        plugin_settings.write_text(
            "tts.gpt_sovits.runtime=" + str(legacy / "gpt-sovits").replace("\\", r"\\").replace(":", r"\:") + "\n",
            encoding="utf-8",
        )
        sleeper = legacy / "app" / "lumi-legacy-worker.exe"
        shutil.copy2(Path(os.environ["WINDIR"]) / "System32" / "ping.exe", sleeper)
        locked_process = subprocess.Popen(
            [str(sleeper), "127.0.0.1", "-t"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(0.2)
        check(locked_process.poll() is None, "legacy child process did not start")
        environment = os.environ.copy()
        environment["LOCALAPPDATA"] = str(local)
        try:
            result = subprocess.run(
                [
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    str(PROJECT / "uninstall.ps1"), "-LumiAppPath", str(app),
                    "-DesktopPath", str(desktop), "-SkipProcessCheck",
                ],
                input="\\n",
                text=True,
                capture_output=True,
                encoding="utf-8",
                env=environment,
            )
        finally:
            if locked_process.poll() is None:
                locked_process.kill()
                locked_process.wait()
        check(result.returncode == 0, result.stdout + result.stderr)
        check(sha256(app / "Shimeji-ee.jar") == base_hash, "legacy JAR was not restored")
        check(not backup.exists(), "legacy JAR backup was not removed")
        check(not legacy.exists(), "legacy application data was not removed")
        check((addon / "models" / "LUMI-v2" / "voice.bin").read_bytes() == b"new-voice", "new voice model was overwritten")
        check((addon / "models" / "LUMI-v2" / "voice.bin.legacy-1").read_bytes() == b"voice", "legacy voice conflict was lost")
        check((addon / "runtime" / "codex-app-server.exe").is_file(), "Codex runtime was not migrated")
        migrated_settings = (addon / "settings.json").read_text(encoding="utf-8")
        check(str(legacy) not in migrated_settings, "migrated settings still reference the legacy data root")
        native_settings = plugin_settings.read_text(encoding="utf-8")
        check("LumiToGPT" not in native_settings and "LumiChatAddon" in native_settings,
              "native LUMI AI settings still reference the legacy data root")
        check(locked_process.poll() is not None, "legacy child process was not stopped")
        check(not (desktop / "LUMI to GPT.lnk").exists(), "legacy shortcut was not removed")


def test_transformer() -> None:
    sdk = Path(os.environ.get(
        "LUMI_PLUGIN_SDK",
        r"D:\Steam\steamapps\common\Little LUMI\app\Shimeji-ee.jar",
    ))
    chat = Path(os.environ.get(
        "LUMI_CHAT_JAR",
        r"D:\Steam\steamapps\common\Little LUMI\mods\workshop-3794360578\plugins\lumi.ai.jar",
    ))
    check(sdk.is_file() and chat.is_file(), "Little LUMI SDK or LUMI Chat test JAR is missing")
    javac = shutil.which("javac")
    java = shutil.which("java")
    candidates = sorted(Path(r"C:\Program Files\Eclipse Adoptium").glob("jdk-25*-hotspot/bin"), reverse=True)
    if candidates:
        javac = str(candidates[0] / "javac.exe")
        java = str(candidates[0] / "java.exe")
    check(bool(javac and java), "JDK 25 was not found")
    source_root = PROJECT / "tests" / "java" / "com" / "snowtie" / "lumichataddon"
    sources = [source_root / name for name in (
        "TransformerSmoke.java", "DialogBridgeSmoke.java", "ClaudeSettingsSmoke.java",
        "TtsClientSmoke.java", "TtsRoutingSmoke.java", "RuntimeIoSmoke.java")]
    with tempfile.TemporaryDirectory() as directory:
        classes = Path(directory) / "classes"
        classes.mkdir()
        classpath = os.pathsep.join((str(sdk), str(PLUGIN), str(chat)))
        compile_result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources)],
            text=True,
            capture_output=True,
            encoding="utf-8",
        )
        check(compile_result.returncode == 0, compile_result.stdout + compile_result.stderr)
        run_result = subprocess.run(
            [java, "-cp", os.pathsep.join((str(classes), classpath)),
             "com.snowtie.lumichataddon.TransformerSmoke", str(chat)],
            text=True,
            capture_output=True,
            encoding="utf-8",
        )
        check(run_result.returncode == 0, run_result.stdout + run_result.stderr)
        dialog_result = subprocess.run(
            [java, "-cp", os.pathsep.join((str(classes), classpath)),
             "com.snowtie.lumichataddon.DialogBridgeSmoke"],
            text=True,
            capture_output=True,
            encoding="utf-8",
        )
        check(dialog_result.returncode == 0, dialog_result.stdout + dialog_result.stderr)
        claude_settings = subprocess.run(
            [java, "-cp", os.pathsep.join((str(classes), classpath)),
             "com.snowtie.lumichataddon.ClaudeSettingsSmoke"],
            text=True, capture_output=True, encoding="utf-8",
        )
        check(claude_settings.returncode == 0, claude_settings.stdout + claude_settings.stderr)
        agent = Path(directory) / "tts-routing-agent.jar"
        with zipfile.ZipFile(agent, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nPremain-Class: com.snowtie.lumichataddon.TtsRoutingSmoke\nCan-Retransform-Classes: true\n\n")
            agent_class = classes / "com/snowtie/lumichataddon/TtsRoutingSmoke.class"
            archive.write(agent_class, "com/snowtie/lumichataddon/TtsRoutingSmoke.class")
        for order in ((sdk, chat, PLUGIN), (sdk, PLUGIN, chat)):
            for mode in ("preloaded", "onload"):
                result = subprocess.run(
                    [java, f"-javaagent:{agent}", "-cp", os.pathsep.join(map(str, (classes, *order))),
                     "com.snowtie.lumichataddon.TtsRoutingSmoke", mode],
                    cwd=directory, text=True, capture_output=True, encoding="utf-8", timeout=20,
                )
                check(result.returncode == 0, result.stdout + result.stderr)
        print("TTS routing: both JAR orders, preloaded/onload and repeated retransformation passed")
        io_result = subprocess.run(
            [java, "-cp", os.pathsep.join((str(classes), classpath)),
             "com.snowtie.lumichataddon.RuntimeIoSmoke", directory],
            cwd=directory, text=True, capture_output=True, encoding="utf-8", timeout=20,
        )
        check(io_result.returncode == 0, io_result.stdout + io_result.stderr)
        print(io_result.stdout.strip())


def test_helper_runtime() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        rustc = shutil.which("rustc")
        check(bool(rustc), "rustc was not found for the Claude mock")
        mock_source = PROJECT / "tests" / "fixtures" / "mock_claude.rs"
        mock_exe = root / "mock-claude.exe"
        mock_mode = root / "mode.txt"
        mock_log = root / "calls.txt"
        mock_mode.write_text("ok", encoding="utf-8")
        compile_mock = subprocess.run(
            [rustc, str(mock_source), "-O", "-o", str(mock_exe)],
            text=True,
            capture_output=True,
            encoding="utf-8",
        )
        check(compile_mock.returncode == 0, compile_mock.stdout + compile_mock.stderr)
        app = root / "Little LUMI" / "app"
        (app / "speech").mkdir(parents=True)
        (app / "plugindata" / "lumi.ai").mkdir(parents=True)
        (app / "conf").mkdir(parents=True)
        (app / "Shimeji-ee.jar").write_bytes(b"test")
        (app / "conf" / "mod_ai_chat.txt").write_text("enabled\n", encoding="utf-8")
        ai = app / "plugindata" / "lumi.ai" / "ai.properties"
        ai.write_text("llm.provider=claude_account\n", encoding="utf-8")

        data = root / "data"
        data.mkdir()
        port = 32187
        (data / "settings.json").write_text(
            json.dumps({"port": port, "lumi_app_dir": str(app), "voice": {}}),
            encoding="utf-8",
        )
        environment = os.environ.copy()
        environment["LUMI_APP_DIR"] = str(app)
        environment["LUMI_CHAT_ADDON_DATA_DIR"] = str(data)
        environment["LUMI_ALLOW_TEST_SHUTDOWN"] = "1"
        environment["LUMI_CLAUDE_CLI"] = str(mock_exe)
        environment["LUMI_CLAUDE_TEST_MODE"] = str(mock_mode)
        environment["LUMI_CLAUDE_TEST_LOG"] = str(mock_log)
        process = subprocess.Popen(
            [str(HELPER), "--headless"],
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            health = wait_json(f"http://127.0.0.1:{port}/health")
            check(health["ok"] is True and health["version"] == VERSION, "unexpected helper health")
            check(health["name"] == "LUMI Chat Addon Helper", "helper branding is stale")
            claude_status = wait_json(f"http://127.0.0.1:{port}/claude/auth/status")
            check(claude_status["connected"] is True, "Claude account status bridge failed")
            completion_url = f"http://127.0.0.1:{port}/claude/v1/chat/completions"
            for model in ("sonnet", "opus", "haiku", "claude-sonnet-4-6", "sonnet[1m]"):
                claude_reply = post_json(completion_url,
                    {"model": model, "messages": [{"role": "user", "content": "테스트"}]})
                check(claude_reply["choices"][0]["message"]["content"] == f"mock claude reply: {model}",
                      f"Claude model was not forwarded: {model}")

            def expect_error(url: str, expected: str, payload: dict | None = None,
                             timeout: float = 5) -> None:
                request = urllib.request.Request(url,
                    data=json.dumps(payload).encode() if payload is not None else None,
                    headers={"Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(request, timeout=timeout) as response:
                        raise AssertionError(f"expected error but received {response.status}")
                except urllib.error.HTTPError as error:
                    body = json.load(error)
                    check(error.code >= 400 and expected in str(body), f"missing error detail: {body}")

            message = {"model": "sonnet", "messages": [{"role": "user", "content": "테스트"}]}
            for mode, expected in (("limit", "usage limit reached"), ("model_error", "model unavailable"),
                                   ("subtype_error", "permission denied"), ("bad_json", "JSON"),
                                   ("empty", "비어"), ("stderr_error", "network unavailable")):
                mock_mode.write_text(mode, encoding="utf-8")
                expect_error(completion_url, expected, message)
            mock_mode.write_text("auth_off", encoding="utf-8")
            check(wait_json(f"http://127.0.0.1:{port}/claude/auth/status")["connected"] is False,
                  "logged-out status was not preserved")
            expect_error(completion_url, "계정 연결", message)
            for mode, expected in (("auth_bad", "올바르지"), ("auth_error", "auth unavailable")):
                mock_mode.write_text(mode, encoding="utf-8")
                expect_error(f"http://127.0.0.1:{port}/claude/auth/status", expected)
            mock_mode.write_text("ok", encoding="utf-8")
            before = mock_log.read_text(encoding="utf-8")
            expect_error(completion_url, "모델 이름", {**message, "model": "--help"})
            check(mock_log.read_text(encoding="utf-8") == before, "invalid model launched Claude")
            check(post_json(f"http://127.0.0.1:{port}/claude/auth/logout", {})["ok"] is True,
                  "Claude logout failed")
            mock_mode.write_text("logout_error", encoding="utf-8")
            expect_error(f"http://127.0.0.1:{port}/claude/auth/logout", "logout denied", {})
            mock_mode.write_text("auth_timeout", encoding="utf-8")
            started = time.monotonic()
            expect_error(f"http://127.0.0.1:{port}/claude/auth/status", "초과", timeout=40)
            check(time.monotonic() - started < 38, "auth timeout was not enforced")
            mock_mode.write_text("ok", encoding="utf-8")
            check(wait_json(f"http://127.0.0.1:{port}/claude/auth/status")["connected"] is True,
                  "Claude did not recover after timeout")
            print("Claude bridge: hidden Windows processes, model forwarding, auth, errors and timeout passed")
            properties = ai.read_text(encoding="utf-8")
            check("llm.provider=claude_account" in properties, "helper overwrote the selected Claude provider")
            result = post_json(
                f"http://127.0.0.1:{port}/test/shutdown",
                {"token": "lumi-smoke-test"},
            )
            check(result["ok"] is True, "helper shutdown endpoint failed")
            process.wait(timeout=8)
            check(process.returncode == 0, "helper exited with an error")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        environment["LUMI_HELPER_PARENT_PIPE"] = "1"
        owned_helper = subprocess.Popen(
            [str(HELPER), "--headless"], env=environment, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            wait_json(f"http://127.0.0.1:{port}/health")
            owned_helper.stdin.close()
            owned_helper.wait(timeout=8)
            check(owned_helper.returncode == 0, "helper failed to stop after parent pipe closed")
            print("Helper parent-pipe shutdown passed")
        finally:
            if owned_helper.poll() is None:
                owned_helper.kill()
                owned_helper.wait(timeout=5)


def main() -> None:
    test_artifacts()
    test_scripts()
    test_legacy_uninstaller()
    test_transformer()
    test_helper_runtime()
    print("release smoke test passed")


if __name__ == "__main__":
    main()
