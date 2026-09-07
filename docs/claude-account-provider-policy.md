# Claude 계정 프로바이더 구현 원칙

확인일: 2026-09-07

이 문서는 법률 자문이 아니라 LUMI Chat Addon의 구현 경계를 기록한 자료입니다. 서비스 정책은 바뀔 수 있으므로 배포 전 공식 문서를 다시 확인합니다.

## 채택한 방식

- 사용자가 직접 설치하고 로그인한 Anthropic 공식 Claude Code만 실행합니다.
- 호출은 공식 프로그램 호출 방식인 `claude -p --output-format json`을 사용합니다.
- 애드온은 OAuth 토큰, 세션 파일, Windows 자격 증명을 읽거나 저장하지 않습니다.
- Claude Code 바이너리를 수정하거나 GitHub·창작마당 패키지에 재배포하지 않습니다.
- 설치가 필요하면 사용자가 버튼을 누른 뒤 Anthropic 공식 Windows 설치 스크립트를 별도 창에서 실행합니다.
- 사용량과 이용 조건은 각 사용자의 Claude 계정에 따릅니다.

## 구현하지 않는 것

- 애드온 자체 OAuth 클라이언트
- Claude.ai 세션 쿠키나 비공개 API 사용
- 사용자 토큰의 추출·중계·공유
- 한도 회피를 위한 다계정 자동 전환
- Claude Code 또는 Anthropic의 공식 제휴·보증을 암시하는 표시

## 공식 근거

- [Claude Code CLI 사용법](https://code.claude.com/docs/en/cli-usage)
- [Claude Code 프로그램 호출](https://code.claude.com/docs/en/headless)
- [Claude Code 인증](https://code.claude.com/docs/en/authentication)
- [Claude Code 설치](https://code.claude.com/docs/en/setup)
- [Anthropic Consumer Terms](https://www.anthropic.com/legal/consumer-terms)
- [Anthropic Commercial Terms](https://www.anthropic.com/legal/commercial-terms)

