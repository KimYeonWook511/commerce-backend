---
name: harness
description: 개발 시작 전 문서 탐색, 논의, step 설계, phases 초안 작성 workflow를 수행할 때 사용하는 skill
---

# Dev Start Workflow

이 skill은 아래 상황에서 사용한다.

- 구현 전에 작업을 단계별로 나누고 싶을 때
- 기능별 `phases/` 구조의 계획 파일 초안이 필요할 때
- 큰 작업을 자기완결적인 step으로 분해해야 할 때

이 skill은 개발 전 탐색, step 설계, feature/phases 초안 작성, 준비된 phase의 실행기 연결을 담당한다.
실행기 `execute.py`는 step 4에서 생성한 worktree 안에서 실행되며, commit agent를 통한 커밋과 선택적 push를 수행할 수 있다.

## 필수 준수 규칙

아래 규칙은 반드시 지켜야 한다.

- 이 skill을 사용하는 작업에서는 `phases`가 준비된 이후의 기본 구현 경로를 수동 파일 수정이 아니라 `execute.py` 실행으로 본다.
- 사용자가 명시적으로 `execute.py`를 쓰지 말라고 하지 않은 이상, agent가 직접 구현을 시작하면 안 된다.
- `Implement the plan`은 자동으로 직접 구현을 뜻하지 않는다. `phases` 준비 여부와 실행 승인 여부를 먼저 확인해야 한다.
- Workflow는 phase별 `workflow-checklist.json`으로 추적하며, 다음 단계로 넘어가기 전 이전 단계가 모두 `completed`여야 한다.
- `harness` 진행 상태를 사용자에게 보고할 때는 1~7번 Workflow 상태 표를 함께 보여준다.
- `File Drafting` 완료 후에는 반드시 멈추고 작성된 문서 경로를 사용자에게 보고한 뒤 검토 응답을 기다린다. 바로 `execute.py` 실행 요청으로 넘어가지 않는다.
- `Execution Authorization`은 문서 검토 완료, Plan Mode 승인이 모두 확정되어야 `completed`가 된다.

## Workflow 상태 표

`harness`를 진행하면서 사용자에게 상태를 보고할 때는 아래 표 형식을 사용한다.

| 단계 | Workflow | 상태 |
| --- | --- | --- |
| 1 | Explore |  |
| 2 | Discuss |  |
| 3 | Step Design |  |
| 4 | Worktree 생성 및 이동 |  |
| 5 | File Drafting |  |
| 6 | Execution Authorization |  |
| 7 | Execution |  |

상태 표는 `workflow-checklist.json`이 있으면 그 값을 기준으로 표시한다. checklist 생성 전에는 현재 대화에서 실제 완료한 단계만 `✅`로 표시한다.

## 먼저 읽을 것

항상 먼저 아래를 읽는다.

- `CLAUDE.md`
- `docs/commit-conventions.md`

그 다음 현재 작업 대상 feature 문서를 먼저 읽는다.

- `docs/features/<feature-name>/prd.md`
- `docs/features/<feature-name>/architecture.md`
- `docs/features/<feature-name>/adr.md`
- `docs/features/<feature-name>/api-spec.md`
- `docs/features/<feature-name>/db-schema.md`

feature 문서와 `phases` 문서로 부족한 공통 맥락이 있을 때만 `CLAUDE.md`의 `참고 문서` 섹션을 따라 루트 `docs/` 기준 문서를 추가로 읽는다.
작업 범위에 직접 연결된 코드와 테스트도 함께 읽는다.

## Workflow

### 1. Explore

- `CLAUDE.md`를 읽고 현재 Repo 규칙을 파악한다.
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

- 한 step은 테스트 가능한 사용자 기능 단위를 기본값으로 삼는다.
- API feature는 domain, repository, service, controller, test가 같은 사용자 기능 완성에 필요하면 한 step에 함께 포함한다.
- 레이어별 step 분리는 공통 도메인 선행 작업, 독립 DB 마이그레이션처럼 분리 검증이 명확히 필요한 경우에만 사용한다.
- command/query는 데이터 흐름과 검증 기준이 다르면 분리하고, 같은 정책과 aggregate를 공유하는 command 동작은 묶을 수 있다.
- 각 step 문서는 독립 실행 가능한 자기완결 문서여야 한다.
- step 설계 시 구현 단위와 커밋 단위가 같은 기능/정책 목적을 가리키도록 나눈다. 파일 단위로 과도하게 쪼개지 않는다.
- 관련 문서 경로와 이전 step 결과를 이해하는 데 필요한 파일 경로를 명시한다.
- phase 마지막 두 step은 아래처럼 표준화한다:
  - `step(N-1)`: `sync-root-docs` — 루트 docs 동기화 (ADR, api-spec 등)
  - `step(N)`: `write-retrospective` — 회고록 작성
- 구현 지시는 인터페이스와 핵심 제약 위주로 작성하고, 내부 구현은 과도하게 고정하지 않는다.
- Acceptance Criteria는 실행 가능한 커맨드로만 적는다.
- 주의사항은 `하지 마라. 이유: ...` 형식으로 구체적으로 작성한다.
- step name은 kebab-case slug를 사용한다.

### 4. Worktree 생성 및 이동

Step Design이 완료되면 작업 브랜치 worktree를 생성하고 그 안으로 이동한다.

```bash
cd "$(git rev-parse --git-common-dir)/.."
git worktree add worktrees/<type>-<feature-name> -b <type>/<feature-name> develop
cd worktrees/<type>-<feature-name>
```

`git worktree add` 실행과 `cd` 이동이 모두 완료된 시점에 이 단계가 ✅ 완료된 것으로 본다.
이후 모든 파일 작성과 `execute.py` 실행은 worktree root를 기준으로 수행한다.

### 5. File Drafting

worktree 안에서 아래 파일 초안을 작성한다.

- `docs/features/<feature-name>/prd.md`
- `docs/features/<feature-name>/architecture.md`
- `docs/features/<feature-name>/adr.md`
- `docs/features/<feature-name>/api-spec.md`
- `docs/features/<feature-name>/db-schema.md`
- `docs/features/<feature-name>/phases/index.json`
- `docs/features/<feature-name>/phases/<phase-name>/index.json`
- `docs/features/<feature-name>/phases/<phase-name>/workflow-checklist.json`
- `docs/features/<feature-name>/phases/<phase-name>/step{N}.md`

포맷과 상세 규칙은 `references/phase-files.md`를 따른다.

파일 생성 승인 전 금지:
- feature 문서 초안, `phases/index.json`, step 문서를 직접 만들지 않는다.
- 계획이 완성됐더라도 승인 없이 repo 파일을 수정하지 않는다.

File Drafting 완료 후 필수 중단:
- 작성 또는 수정한 feature 문서, phase index, step 문서, `workflow-checklist.json` 경로를 사용자에게 보고한다.
- checklist의 `File Drafting`까지만 `completed`로 둔다.
- `Execution Authorization`은 사용자가 문서 검토 완료와 실행 승인을 명시하기 전까지 `pending`으로 둔다.
- 이 시점의 checklist는 `Explore`, `Discuss`, `Step Design`, `Worktree 생성 및 이동`, `File Drafting`만 `completed`여야 하고, `Execution Authorization`, `Execution`은 `pending`이어야 한다.
- 사용자의 단순한 "진행해", "계속해", "Implement the plan"은 문서 검토 완료 또는 실행 승인으로 해석하지 않는다.

### 6. Execution Authorization

`execute.py`를 실행하기 전에 Plan Mode로 사용자 승인을 받는다.

- `execute.py`는 worktree 안에서 실행하며, commit agent를 통해 커밋을 수행한다.
- 이 단계에 들어가기 전 checklist의 `Explore`, `Discuss`, `Step Design`, `Worktree 생성 및 이동`, `File Drafting`은 모두 `completed`여야 한다.
- 아래 순서로만 진행한다.
  1. Plan Mode로 구현 계획을 사용자에게 제시한다.
  2. `ExitPlanMode`로 사용자 승인을 받는다.
  3. 승인이 확정되면 `workflow-checklist.json`의 `Execution Authorization`을 `completed`로 갱신한다.
  4. checklist 기록이 끝난 뒤에만 `execute.py` 실행으로 넘어간다.
- 사용자가 승인하지 않으면 구현으로 진행하지 않는다.
- checklist 갱신 시 `Execution Authorization`은 `completed`, top-level `status`는 `authorized`로 기록한다.
- `Execution Authorization.authorization`에는 `escalation_approved`, `approval_prompt_mode`, `prefix_rule`, `approved_by`, `approved_at`을 기록한다.
- `approved_at` 기록 전 아래 명령으로 실제 시각을 확인한다:
  ```bash
  date '+%Y-%m-%dT%H:%M:%S+0900'
  ```

### 7. Execution

`phases` 파일이 준비되면 feature 브랜치 worktree 안에서 실행기를 실행한다.

```bash
# worktrees/<type>-<feature-name>/ 안에서
python3 .claude/skills/harness/scripts/execute.py docs/features/<feature-name>/phases/<phase-name>
python3 .claude/skills/harness/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> --push
```

실행 규칙:
- 구현 요청을 받으면 먼저 `phases` 문서, `workflow-checklist.json`, `Execution Authorization` 완료 여부와 `authorization` 기록을 확인한다.
- 준비 또는 승인이 부족하면 구현하지 않고 누락된 단계로 돌아간다.
- 사용자가 명시적으로 수동 구현을 지시한 경우에만 `execute.py`를 우회할 수 있으며, 이때도 해당 예외를 먼저 사용자 업데이트에 분명히 남긴다.

실행기 운영 규칙:
- 실행기는 `workflow-checklist.json` 승인 상태를 검증한 뒤 가장 앞의 `pending` step부터 순차 실행한다.
- 성공한 step은 `completed`로 기록하고 다음 `pending` step으로 자동 진행한다.
- 실행기는 developer worker, Acceptance Criteria 재검증, reviewer worker를 통해 step 완료 여부를 검증한다. 상세 산출물과 파일 포맷은 `references/phase-files.md`를 따른다.
- 실행 상태 갱신은 자동화 범위다. phase index는 phase 종료 시 커밋하고, 실행 output, Acceptance Criteria output, review output, workflow checklist는 로컬 산출물로만 둔다.
- 실행 중 재시도를 위한 step `pending` reset은 `execute.py` 내부 동작으로만 허용된다.
- `blocked` 또는 3회 재시도 후 최종 `error`가 발생하면 즉시 중단하고 사용자에게 실패 step, 실패 사유, 관련 output 파일 경로를 보고한다.
- 최종 `error` 또는 `blocked` 이후 agent는 사용자 승인 없이 step 상태를 `pending`으로 되돌리지 않는다.
- agent는 사용자 승인 없이 실패 회피 목적으로 step 요구사항, Acceptance Criteria, feature 문서, root docs를 수정해 재시도하지 않는다.
- 실패 원인이 문서 누락, scope 누락, Acceptance Criteria 오류처럼 명확해 보여도 자동 수정하지 않는다. 먼저 원인과 수정 계획을 사용자에게 제시한다.
- 재실행은 사용자가 문서/상태 수정과 `execute.py` 재실행을 명시적으로 승인한 뒤에만 한다.

`--push`는 모든 step이 완료된 뒤 현재 feature 브랜치를 원격 저장소로 push하는 옵션이다.
