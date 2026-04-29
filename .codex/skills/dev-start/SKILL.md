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

## 필수 준수 규칙

아래 규칙은 반드시 지켜야 한다.

- 이 skill을 사용하는 작업에서는 `phases`가 준비된 이후의 기본 구현 경로를 수동 파일 수정이 아니라 `execute.py` 실행으로 본다.
- 사용자가 명시적으로 `execute.py`를 쓰지 말라고 하지 않은 이상, agent가 직접 구현을 시작하면 안 된다.
- `Implement the plan`은 자동으로 직접 구현을 뜻하지 않는다. `phases` 준비 여부와 실행 승인 여부를 먼저 확인해야 한다.

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

- `AGENTS.md`를 읽고 현재 Repo 규칙을 파악한다.
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
- API feature는 domain/service/controller/test/docs sync를 한 step에 몰아넣지 않는다.
- command API가 여러 동작을 포함하면 create, update/delete, controller test, docs sync처럼 나눈다.
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

파일 생성 승인 전 금지:
- feature 문서 초안, `phases/index.json`, step 문서를 직접 만들지 않는다.
- 계획이 완성됐더라도 승인 없이 repo 파일을 수정하지 않는다.

### 5. Execution

`phases` 파일이 준비되면 skill 내부 실행기로 step을 순차 실행할 수 있다.

```bash
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name>
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> --push
```

실행 규칙:
- `phases` 문서가 준비되지 않았으면 구현을 시작하지 말고 문서 준비 단계로 되돌아간다.
- 사용자가 구현을 요청하더라도, 기본 동작은 agent의 직접 수정이 아니라 `execute.py` 실행 제안 또는 실행 승인 대기다.
- 사용자가 `execute.py` 실행을 승인하지 않았으면 agent는 코드를 직접 수정하지 않는다.
- 사용자가 명시적으로 수동 구현을 지시한 경우에만 `execute.py`를 우회할 수 있으며, 이때도 해당 예외를 먼저 사용자 업데이트에 분명히 남긴다.

실행기는 아래 규칙으로 동작한다.

- phase/step 상태를 확인하고, 실행 가능한 경우에만 가장 앞의 `pending` step을 수행한다.
- 현재 step 문서와 관련 문서를 모아 developer 컨텍스트를 만들고 `developer_worker`를 실행한다.
- developer worker는 실행기 내부 `codex exec --ephemeral -c approval_policy="never" -s workspace-write` 명령으로 호출해 승인 프롬프트 없이 repo 작업 파일을 수정한다.
- reviewer worker는 실행기 내부 `codex exec --ephemeral -c approval_policy="never" -s read-only` 명령으로 호출해 승인 프롬프트 없이 실제 repo 파일을 읽기 전용으로 검토한다.
- 브랜치 생성, add, commit 전에 Git 메타데이터 디렉터리 쓰기 가능 여부를 preflight로 확인한다. preflight는 권한을 부여하지 않고 권한 부족을 조기에 감지한다.
- Codex가 실행기를 대신 돌릴 때 `execute.py` 자체의 Git sandbox 권한 문제가 예상되면 `python3 .codex/skills/dev-start/scripts/execute.py ...` 명령 자체를 권한 상승으로 실행한다.
- 실행 후 `step_verifier`로 상태와 output을 먼저 검증한다.
- step이 `completed`면 실행기가 Acceptance Criteria를 직접 재실행한다.
- 후검증을 통과하면 `reviewer_worker`가 변경 경로와 output을 기준으로 실제 repo 파일을 read-only 검토한다.
- verifier, AC 재검증, reviewer 중 하나라도 실패하면 사유를 다음 시도 컨텍스트에 넣어 최대 3회까지 재시도한다.
- `completed` + verifier 통과 + AC 재검증 통과 + reviewer 통과면 자동 커밋을 수행한다.
- 실행 시작 시 이미 `completed`인 step은 `stepN-output.json`, `stepN-review-output.json`, Acceptance Criteria가 있으면 `stepN-ac-output.json`이 모두 있어야 한다. 산출물이 없으면 수동 완료로 보고 실행을 중단한다.
- `blocked` 또는 최종 `error`면 phase 상태를 함께 갱신하고 즉시 중단한다.

`--push`는 모든 step이 완료된 뒤 현재 feature 브랜치를 원격 저장소로 push하는 옵션이다.
