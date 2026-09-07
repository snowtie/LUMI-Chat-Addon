# LUMI Chat Addon v1.1.0

## 수정 사항

- Addon 설정창의 잘린 안내문과 어긋난 카드 배치를 수정
- GPT-SoVITS 설치를 별도 PowerShell 창으로 표시하고 중복 실행 차단

## 새 설치 방식

- Little LUMI 1.2.0의 공식 코드 플러그인 API로 전환
- 창작마당 구독만으로 플러그인 설치 및 업데이트
- Little LUMI 본체 JAR과 LUMI Chat JAR을 수정하지 않음
- LUMI Chat Workshop ID 3794360578을 필수 의존성으로 사용
- 앱 업데이트 뒤에도 플러그인과 설정이 유지되는 구조

## 계정과 대화

- ChatGPT 계정 연결은 OpenAI 공식 Codex App Server 사용
- Claude 계정 연결은 수정하지 않은 공식 Claude Code와 공식 로그인 사용
- 기본 ChatGPT 모델 GPT-5.6 Luna, 낮은 추론
- 자율 혼잣말과 화면 구경은 최초 실행 시 기본 비활성화
- LUMI Chat의 기존 대화창, 기억, 페르소나, 말풍선 경로 유지
- 응답과 화면 이미지를 중간에서 잘라내지 않고 원본 흐름으로 전달

## 목소리

- 루미 AI 설정의 목소리 프로바이더에 GPT-SoVITS 추가
- 설정 창에서 공식 통합판과 허가된 LUMI 가중치를 한 번에 설치
- NVIDIA GPU 세대별 런타임 선택과 실제 CUDA 연산 검사
- CUDA 실패 시 CPU + FP32 자동 대체
- 집중 모드에서 음성 생성과 재생 즉시 차단
- 절전 및 초절전 유휴 종료 정책 유지
- 설치 완료 문구까지 창을 닫지 않도록 안내 및 상세 로그 제공

## 이전 버전 정리

- v1.0.9 이하 LUMI to GPT 앱 제거기 제공
- 변경된 JAR은 패치 표식과 백업 SHA-256이 일치할 때만 원상복구
- 이미 깨끗한 JAR에는 오래된 백업을 덮어쓰지 않음
- 기존 GPT-SoVITS, 가중치, Codex 런타임은 새 데이터 폴더로 이전
- 제거 시 남아 있던 `codex-app-server.exe` 등 전용 자식 프로세스까지 종료
- 잠긴 기존 데이터 폴더 삭제를 재시도하고, 이전된 음성 설정 경로를 새 폴더로 갱신
