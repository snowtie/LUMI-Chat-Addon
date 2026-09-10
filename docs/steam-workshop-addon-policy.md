# Little LUMI 창작마당 배포 구조

확인일: 2026-09-07

LUMI Chat Addon 1.1.2은 Little LUMI 1.2.0의 공식 코드 플러그인 규격을 사용합니다. STUDIO LUMI로부터 코드 모드 배포와, 플러그인이 공식 외부 런타임을 내려받아 실행하는 방식이 가능하다는 답변을 받은 범위에 맞춥니다.

## 패키지 구조

```text
workshop-content/
├─ LICENSE
├─ NOTICE.txt
├─ README.txt
├─ VOICE_MODEL_NOTICE.txt
└─ plugins/
   └─ lumi.chat.addon.jar
```

플러그인 JAR 루트에는 `plugin.json`과 `META-INF/services/com.group_finity.mascot.lumi.plugin.LumiPlugin`이 들어 있습니다.

## 지켜야 할 경계

- LUMI Chat Workshop ID `3794360578`을 필요 항목으로 지정합니다.
- LUMI Chat JAR, Little LUMI JAR, 캐릭터 이미지, 페르소나 원문을 포함하지 않습니다.
- `Shimeji-ee.jar`를 교체하거나 패치하지 않습니다.
- 계정 도우미는 GitHub Release에서 내려받고 SHA-256을 검증합니다.
- Codex App Server와 Claude Code는 각 제작사의 공식 배포 경로만 사용합니다.
- TTS 가중치는 받은 배포 허가 범위 안에서 별도 Release 자산으로 제공합니다.
- 비공식 커뮤니티 확장임을 설명 첫머리에 표시합니다.

## 업로드

Little LUMI 설정 → 모드에서 새 모드를 만들고 `release\workshop-content`의 내용을 선택합니다. 게시 뒤 Steam 창작마당 편집 화면에서 LUMI Chat을 필요 항목으로 추가합니다.
