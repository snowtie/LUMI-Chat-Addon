# LUMI Chat Addon

<img src="ui/lumi-chat-addon.png" alt="LUMI Chat Addon" width="280">

Little LUMI의 공식 코드 플러그인 방식으로 동작하는 비공식 LUMI Chat 확장입니다. LUMI Chat의 대화창·말풍선·기억·페르소나는 그대로 두고 다음 기능만 추가합니다.

- ChatGPT 계정: 공식 Codex App Server와 사용자의 Codex 로그인 사용
- Claude 계정: 수정하지 않은 공식 Claude Code와 사용자의 직접 로그인 사용
- LUMI GPT-SoVITS: 선택 설치, 자동 GPU 호환 판정 및 CPU 대체
- 집중 모드 즉시 무음, 음성 절전 및 초절전
- Codex 작업 완료 알림 MCP

STUDIO LUMI, LUMI Chat 제작자, OpenAI 또는 Anthropic의 공식 제품이 아닙니다.

## 필요한 것

- Windows 11 x64
- Little LUMI 1.2.0 이상
- Steam 창작마당의 [LUMI Chat](https://steamcommunity.com/sharedfiles/filedetails/?id=3794360578)
- ChatGPT의 Codex 사용 권한 또는 Claude 계정
- LUMI GPT-SoVITS를 쓸 경우 충분한 저장 공간과 LUMI Voice Pack

Python과 별도 설치기는 필요하지 않습니다. ChatGPT 연결에는 일반 ChatGPT 메시지 한도가 아니라 계정에 제공되는 Codex 사용량이 적용됩니다. Claude 연결에는 해당 계정의 Claude Code 사용량과 정책이 적용됩니다.

## 설치와 사용

1. 창작마당에서 LUMI Chat과 LUMI Chat Addon을 구독합니다.
2. Little LUMI를 다시 시작합니다.
3. 설정 → 모드에서 두 모드의 코드 실행을 승인하고 활성화합니다. 재시작 안내가 나오면 다시 시작한 뒤 LUMI Chat Addon 설정을 엽니다.
4. ChatGPT 계정 연결 또는 Claude 계정 연결을 누르고 공식 로그인 절차를 완료합니다.
5. 루미 AI 설정 → 두뇌에서 ChatGPT 계정 또는 Claude 계정을 선택합니다.
6. 꼬미의 기존 말 걸기 창에서 대화합니다.

처음 ChatGPT 연결을 누르면 플러그인이 OpenAI 공식 배포본의 Codex App Server를 내려받고 SHA-256을 검증합니다. Claude 연결을 누르면 설치된 공식 Claude Code를 사용하며, 없다면 Anthropic의 공식 Windows 설치 창을 엽니다. 애드온은 로그인 토큰을 읽거나 저장하지 않습니다.

Claude는 열린 로그인 창에서 연결을 마친 뒤 애드온의 확인 버튼을 눌러 주세요. 로그인 상태가 확인된 경우에만 두뇌가 Claude로 바뀌며, 취소하거나 실패하면 기존 선택을 유지합니다. 로그인 창은 표시하지만 상태 확인·대화·로그아웃은 명령창 없이 실행합니다. 인증 확인은 최대 15초이며, 실패 상세는 Claude 상태 문구에 마우스를 올리면 볼 수 있습니다.

Claude의 기본 모델은 `sonnet`입니다. ChatGPT와 Claude 모두 루미 AI 설정 → 두뇌에서 바꾼 모델은 다시 실행해도 유지됩니다. Claude에는 `opus`, `haiku` 또는 계정에서 지원하는 모델 ID를 입력할 수 있으며, 실제 사용 가능 여부는 Claude의 응답으로 확인합니다. 사용량 제한·모델 접근 오류는 정상 대사로 읽지 않고 오류로 표시합니다. ChatGPT도 로그인 완료 후 확인 버튼을 눌렀을 때만 두뇌가 전환됩니다.

GPT-SoVITS 연결은 원본 LUMI Chat의 TTS 함수에 메모리상 분기를 추가합니다. 원본 JAR를 수정하거나 같은 이름의 TTS 클래스를 덮어쓰지 않으며, 이미 로드된 경우와 나중에 로드되는 경우 모두 처리합니다. 집중 모드의 음성 차단은 유지됩니다. 애드온이 시작한 도우미는 앱과 연결된 파이프가 닫히면 정상 종료하며 관리 중인 GPT-SoVITS도 정리합니다.

새 설치에서는 사용량을 줄이기 위해 자율 혼잣말과 화면 구경이 기본으로 꺼집니다.

## LUMI GPT-SoVITS

LUMI Chat Addon 설정에서 GPT-SoVITS 설치 또는 복구를 누릅니다. 공식 통합판은 GPU에 따라 약 6~9GB이고, 허가받은 LUMI 음성 가중치는 약 420MB입니다. 완료 문구가 나올 때까지 PowerShell 창을 닫지 마세요.

설치가 끝나면 루미 AI 설정 → 목소리에서 GPT-SoVITS를 선택할 수 있습니다. 장치 기본값은 자동이며 실제 CUDA 연산이 실패하면 CPU + FP32로 전환합니다. 집중 모드에서는 음성을 생성하거나 재생하지 않습니다.

런타임과 모델은 `%LOCALAPPDATA%\LumiChatAddon`에 저장됩니다. 음성 가중치의 배포 범위는 [VOICE_MODEL_NOTICE.txt](VOICE_MODEL_NOTICE.txt)를 따릅니다.

## 이전 LUMI to GPT 제거

v1.0.9 이하의 설치형 LUMI to GPT를 썼다면 GitHub Release의 `LUMI-to-GPT-Legacy-Uninstaller-v1.1.2.zip`을 받아 `UNINSTALL.cmd`를 실행하세요.

제거기는 다음만 처리합니다.

- `%LOCALAPPDATA%\LumiToGPT`의 기존 앱과 바탕화면 바로가기 제거
- 패치 표식과 SHA-256이 일치할 때만 `Shimeji-ee.jar` 원본 복원
- 기존 GPT-SoVITS·가중치·Codex 런타임을 `%LOCALAPPDATA%\LumiChatAddon`으로 이동

백업이 없거나 해시가 다르면 JAR을 덮어쓰지 않고 Steam 파일 무결성 검사를 안내합니다.

## 제거

새 코드 플러그인은 창작마당에서 구독 해제하면 제거됩니다. 내려받은 계정 도우미와 GPT-SoVITS 데이터까지 지우려면 Little LUMI를 종료한 뒤 `%LOCALAPPDATA%\LumiChatAddon` 폴더를 삭제합니다.

## 개발

JDK 25와 Rust/Tauri가 필요합니다. Little LUMI 플러그인 SDK JAR과 LUMI Chat 1.1.0 JAR을 지정해 빌드할 수 있습니다.

```powershell
cargo test --manifest-path .\src-tauri\Cargo.toml
.\plugin\build.ps1 -SdkJar "D:\Steam\steamapps\common\Little LUMI\app\Shimeji-ee.jar" -LumiChatJar "D:\Steam\steamapps\common\Little LUMI\mods\workshop-3794360578\plugins\lumi.ai.jar"
.\build.ps1 -SdkJar "D:\Steam\steamapps\common\Little LUMI\app\Shimeji-ee.jar" -LumiChatJar "D:\Steam\steamapps\common\Little LUMI\mods\workshop-3794360578\plugins\lumi.ai.jar"
python .\tests\smoke_release.py
```

완성된 창작마당 패키지는 `release\LUMI-Chat-Addon-v1.1.2-workshop.zip`입니다. Steam 항목의 필요 항목에는 LUMI Chat Workshop ID `3794360578`을 지정합니다.

로컬 테스트본은 내 모드에서 활성화하고 같은 애드온의 구독본은 꺼 두세요. 구독본 폴더를 직접 덮어쓰면 Steam 동기화 때 기존 배포본으로 돌아갈 수 있습니다. 코드가 바뀌어 실행 승인을 다시 요구하면 모드 탭에서 확인하세요. `DISABLED` 같은 로컬 상태 파일은 배포 패키지에 넣지 않습니다.

소스 코드는 BSD 3-Clause로 공개합니다. 외부 구성요소와 상표 고지는 [NOTICE.txt](NOTICE.txt)를 확인하세요.
