---
name: dev-start
description: 개발 시작 전 문서 탐색, 논의, step 설계, phases 초안 작성 workflow를 수행할 때 사용하는 skill
---

# Dev Start Workflow

이 skill은 아래 상황에서 사용한다.

- 구현 전에 작업을 단계별로 나누고 싶을 때
- 기능별 `phases/` 구조의 계획 파일 초안이 필요할 때
- 큰 작업을 자기완결적인 step으로 분해해야 할 때

이 skill은 개발 시작 전 workflow, feature/phases 초안 작성, 준비된 phase의 실행기 연결까지 담당한다.
수동 리뷰 절차와 수동 commit/push 운영 절차는 이 skill의 범위가 아니다.
다만 skill 내부 실행기 `execute.py`는 브랜치 생성, 자동 커밋, 선택적 push를 수행할 수 있다.

skill 검증 기준은 `references/skill-test.md`를 따른다.

## 먼저 읽을 것

항상 먼저 아래를 읽는다.

- `AGENTS.md`

그 다음 현재 작업 대상 feature 문서를 먼저 읽는다.

- `docs/features/<feature-name>/prd.md`
- `docs/features/<feature-name>/architecture.md`
- `docs/features/<feature-name>/adr.md`
- `docs/features/<feature-name>/api-spec.md`
- `docs/features/<feature-name>/db-schema.md`

feature 문서와 `phases` 문서로 부족한 공통 맥락이 있을 때만 `AGENTS.md`의 `참고 문서` 섹션을 따라 루트 `docs/` 기준 문서를 추가로 읽는다.
작업 범위에 직접 연결된 코드와 테스트도 함께 읽는다.

## Workflow

### 1. Explore

- `AGENTS.md`를 읽고 현재 저장소 규칙을 파악한다.
- 현재 작업 대상 feature 폴더의 문서와 `phases` 문서를 우선 읽고 현재 구조와 변경 범위를 파악한다.
- 공통 아키텍처, 다른 도메인 ERD, 전역 ADR 같은 정보가 더 필요할 때만 루트 `docs/` 기준 문서를 추가로 읽는다.
- 작업 범위에 직접 연결된 코드와 테스트를 함께 읽는다.
- 이미 답할 수 있는 질문은 하지 않는다.
- 병렬 탐색이 가능한 환경이면 관련 영역을 나눠 추가 탐색할 수 있다.

### 2. Discuss

아래 경우에는 구현 전에 사용자와 논의한다.

- 요구사항이 둘 이상으로 해석될 수 있을 때
- 설계 선택이 결과에 큰 영향을 줄 때
- 외부 인증, API 키, 수동 설정 등 사용자 개입이 필요할 때
- 기존 구조나 규칙과 충돌 가능성이 있을 때

### 3. Step Design

사용자가 계획 작성 또는 step 분해를 요청하면 `phases` 구조를 기준으로 초안을 만든다.

설계 원칙:

- 한 step은 하나의 레이어 또는 하나의 핵심 관심사만 다룬다.
- 각 step 문서는 독립 실행 가능한 자기완결 문서여야 한다.
- 관련 문서 경로와 이전 step 산출물 경로를 명시한다.
- 구현 지시는 인터페이스와 핵심 제약 위주로 작성하고, 내부 구현은 과도하게 고정하지 않는다.
- Acceptance Criteria는 실행 가능한 커맨드로만 적는다.
- 주의사항은 `하지 마라. 이유: ...` 형식으로 구체적으로 작성한다.
- step name은 kebab-case slug를 사용한다.

### 4. File Drafting

사용자가 실제 파일 생성을 승인하면 아래 파일 초안을 작성한다.

- `docs/features/<feature-name>/prd.md`
- `docs/features/<feature-name>/architecture.md`
- `docs/features/<feature-name>/adr.md`
- `docs/features/<feature-name>/api-spec.md`
- `docs/features/<feature-name>/db-schema.md`
- `docs/features/<feature-name>/phases/index.json`
- `docs/features/<feature-name>/phases/<phase-name>/index.json`
- `docs/features/<feature-name>/phases/<phase-name>/step{N}.md`

포맷과 상세 규칙은 `references/phase-files.md`를 따른다.

### 5. Execution

`phases` 파일이 준비되면 skill 내부 실행기로 step을 순차 실행할 수 있다.

```bash
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name>
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> --push
```

실행 순서는 아래와 같다.

1. `docs/features/<feature-name>/phases/index.json`과 `docs/features/<feature-name>/phases/<phase-name>/index.json`을 읽고 실행 가능한 상태인지 확인한다.
2. 이전 step 중 `error` 또는 `blocked` 상태가 있으면 즉시 중단한다.
3. 현재 feature용 브랜치 `feature/<feature-name>`을 checkout하거나 없으면 새로 만든다.
4. phase에 `created_at`이 없으면 최초 실행 시각을 기록한다.
5. 가장 앞의 `pending` step을 찾고, `started_at`이 없으면 기록한다.
6. 현재 step 문서와 `AGENTS.md`, feature 문서 우선 guardrails, 이전 완료 step의 `summary`, 이전 실패 메시지를 합쳐 preamble을 만든다.
7. Codex CLI로 현재 step을 실행하고, 진행 표시기와 경과 시간을 함께 보여준다.
8. step 실행 후 `docs/features/<feature-name>/phases/<phase-name>/index.json`을 다시 읽어 `completed`, `error`, `blocked` 상태를 확인한다.
9. `completed`이면 `completed_at`을 기록하고, 코드 변경과 metadata 변경을 나눠 feat/chore 2단계 커밋을 수행한다.
10. `blocked`이면 `blocked_at`, `blocked_reason`을 기록하고 기능 내부 `phases/index.json`의 status도 `blocked`로 갱신한 뒤 즉시 중단한다.
11. 실패면 `error_message`를 다음 시도 preamble에 넣어 최대 3회까지 재시도한다.
12. 3회 모두 실패하면 `failed_at`, `error_message`를 기록하고 기능 내부 `phases/index.json`의 status도 `error`로 갱신한 뒤 중단한다.
13. 더 이상 `pending` step이 없으면 phase에 `completed_at`을 기록하고 기능 내부 `phases/index.json`의 status를 `completed`로 갱신한다.
14. `--push` 옵션이 있으면 마지막에 현재 feature 브랜치를 원격으로 push한다.

`--push`는 모든 step이 완료된 뒤 현재 feature 브랜치를 원격 저장소로 push하는 옵션이다.

## 작성 규칙

- 계획만 요청받았으면 구조와 초안만 제안하고 파일을 만들지 않는다.
- 파일 생성 승인 전에는 경로, step 수, Acceptance Criteria를 먼저 보여준다.
- 각 step에는 읽어야 할 파일, 작업, 수정 가능 경로, Acceptance Criteria, 검증 절차, 금지사항이 포함되어야 한다.
- 핵심 규칙은 명시하지만 구현 세부를 불필요하게 과잉 지정하지 않는다.
