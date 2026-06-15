# Phase Files Reference

이 문서는 `harness` skill이 Task별 문서와 `phases` 구조를 설계하거나 초안을 만들 때 따르는 참조 문서다.

## Task 문서 기본 세트

각 Task는 `docs/tasks/<task-name>/` 아래에서 아래 문서를 기본 생성한다.

- `prd.md`
- `architecture.md`
- `adr.md`
- `api-spec.md`
- `db-schema.md`

각 문서는 `docs/tasks/_templates/` 템플릿을 복사해 시작한다.

### 선택 생성 규칙

모든 Task가 5개 문서를 다 만들 필요는 없다. 그 Task가 실제로 건드리는 관심사에 해당하는 문서만 생성한다.

- `prd.md`: 거의 항상 생성한다. 기능/변경의 의도와 범위를 담는 정본이다.
- `adr.md`: 항상 생성하되, 새로 채택된 설계 결정이 없으면 헤더만 둔 빈 staging으로 둔다.
- `architecture.md`: 구조·레이어·책임 분리에 영향이 있을 때 생성한다. 순수 내부 리팩터링이라 구조 변화가 없으면 생략할 수 있다.
- `api-spec.md`: 추가·변경되는 API가 있을 때만 생성한다. API를 건드리지 않는 Task는 생략한다.
- `db-schema.md`: 추가·변경되는 테이블/컬럼이 있을 때만 생성한다. 스키마를 건드리지 않는 Task는 생략한다.

생략한 문서는 step 문서의 `읽어야 할 파일` 목록과 `execute.py`가 주입하는 컨텍스트에서도 자연히 빠진다. `step_context`는 존재하는 문서만 읽으므로 생략해도 실행이 깨지지 않는다.

phase 구조를 만들 때는 아래 파일도 반드시 생성한다.

- `docs/tasks/<task-name>/phases/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/workflow-checklist.json`
- `docs/tasks/<task-name>/phases/<phase-name>/step{N}.md`

`workflow-checklist.json`은 `docs/tasks/_templates/phases/workflow-checklist.json`을 복사해 시작한다.

## `docs/tasks/<task-name>/phases/index.json`

해당 Task 내부의 phase 목록을 관리하는 인덱스다.

```json
{
  "phases": [
    { "dir": "0-bootstrap", "harness_version": "v3", "status": "pending" },
    { "dir": "1-domain", "harness_version": "v3", "status": "pending" }
  ]
}
```

필드 규칙:

- `dir`: Task 내부 phase 디렉토리명
- `harness_version`: 항상 `v3`. 각 phase 항목이 harness-v3로 설계됐음을 나타내며 `execute.py`가 실행 전 확인한다 (그 phase의 `<dir>/index.json` harness_version과 일치)
- `status`: `pending` | `completed` | `error` | `blocked`
- 타임스탬프 필드는 생성 시 넣지 않는다
- phase 이름은 `<순번>-<slug>` 형식을 사용한다

## `docs/tasks/<task-name>/phases/<phase-name>/index.json`

step 실행 상태 파일이다.

```json
{
  "project": "<project-name>",
  "phase": "<phase-name>",
  "harness_version": "v3",
  "steps": [
    { "step": 1, "name": "project-setup", "status": "pending" },
    { "step": 2, "name": "core-types", "status": "pending" },
    { "step": 3, "name": "api-layer", "status": "pending" }
  ]
}
```

필드 규칙:

- `project`: 프로젝트명
- `phase`: phase 이름이며 디렉토리명과 일치해야 한다
- `harness_version`: 항상 `v3`. task-level `phases/index.json`과 함께 이 phase가 harness-v3로 설계됐음을 나타내며 `execute.py`가 실행 전 확인한다
- `steps[].step`: 1부터 시작하는 순번
- `steps[].name`: kebab-case slug
- `steps[].status`: 초기값은 모두 `pending`

자동 기록 필드는 초안 생성 시 넣지 않는다.

- `created_at`
- `started_at`
- `completed_at`
- `failed_at`
- `blocked_at`
- `execution`: phase 최초 실행 시 `execute.py`가 1회 기록한다. 재실행 시 기존 값을 보존한다. 하위 필드는 `developer_model`, `reviewer_model`, `commit_model` (각각 `--developer-model` / `--reviewer-model` / `--commit-model` CLI 인자값)과 `push` (`--no-push`면 `false`). init·step·finalize가 별도 프로세스라, `step`은 여기서 role별 모델을 읽고 `finalize`는 `push`를 읽는다.

상태별 추가 필드 의미:

- `summary`: 완료한 변경의 한 줄 요약
- `error_message`: 실패 원인
- `blocked_reason`: 사용자 개입 또는 외부 제약으로 인해 막힌 사유

`execution` 필드 예:

```json
{
  "execution": {
    "developer_model": "sonnet",
    "reviewer_model": "opus",
    "commit_model": "haiku",
    "push": true
  }
}
```

## `docs/tasks/<task-name>/phases/<phase-name>/workflow-checklist.json`

`harness` workflow 진행 상태를 기록하는 checklist다. phase를 만들 때 반드시 생성하며, 항목 제목은 `SKILL.md`의 Workflow 제목과 일치해야 한다.

```json
{
  "workflow": "harness",
  "status": "drafting",
  "items": [
    { "order": 1, "title": "Explore", "status": "completed" },
    { "order": 2, "title": "Discuss", "status": "completed" },
    { "order": 3, "title": "Step Design", "status": "completed" },
    { "order": 4, "title": "Worktree 생성 및 이동", "status": "completed" },
    { "order": 5, "title": "File Drafting", "status": "completed" },
    { "order": 6, "title": "Execution", "status": "pending" },
    { "order": 7, "title": "PR Review", "status": "pending" },
    { "order": 8, "title": "Root Sync", "status": "pending" },
    { "order": 9, "title": "Retrospective", "status": "pending" }
  ]
}
```

필드 규칙:

- `workflow`: 항상 `harness`
- `status`: `drafting` | `in_progress` | `completed`
- `items`: `SKILL.md`의 1~9번 Stage 순서와 제목을 그대로 사용한다.
- `Execution`(6)은 `execute.py`가 시작할 때 `in_progress`로, phase 종료 시 `completed`로 갱신된다. 별도 `authorization` 객체는 사용하지 않는다.
- `PR Review`(7), `Root Sync`(8), `Retrospective`(9)는 `execute.py` 바깥에서 일어나며 agent가 진행하면서 수동으로 `completed`로 갱신한다. `execute.py`는 이 세 항목을 건드리지 않는다.
- 단, `Root Sync`(8)·`Retrospective`(9)는 `PR Review`(7)가 `completed`로 갱신된 뒤에만 `completed`로 갱신한다. 리뷰 코멘트가 없다는 이유로 7을 건너뛰고 8/9를 앞당기지 않는다.

## `docs/tasks/<task-name>/phases/<phase-name>/step{N}.md`

각 step은 자기완결적인 작업 문서여야 한다.

````md
# Step {N}: {name}

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/<task-name>/prd.md`
- `/docs/tasks/<task-name>/architecture.md`
- `/docs/tasks/<task-name>/adr.md`
- `/docs/tasks/<task-name>/api-spec.md`
- `/docs/tasks/<task-name>/db-schema.md`
- `{이전 step에서 생성/수정된 파일 경로}`

Task 문서만으로 부족한 공통 맥락이 있으면 루트 문서를 추가로 읽는다. 이 step의 작업이 건드리는 영역에 해당하는 문서를 **반드시** 포함한다 (step 작성 시 아래 매핑으로 골라 명시):

- 구조·레이어·책임 → `/docs/architecture.md`
- 설계 결정·정책 → `/docs/adr.md` (관련 항목)
- try-catch·예외 처리·DB 무결성·외부 캐시 장애 → `/docs/exception-strategy.md`
- 로그 추가·레벨·MDC → `/docs/logging-conventions.md`
- 테스트 작성 → `/docs/testing-conventions.md`
- 스키마·테이블·제약 → `/docs/db-schema.md`
- API 추가·변경 → `/docs/api-spec.md`

(logging/exception/testing의 핵심 원칙 요약은 `step_context`가 항상 자동 주입하지만, 도메인 사례·세부가 필요한 step은 위 경로를 명시해 전문을 함께 읽는다.)

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

{구체적인 구현 지시. 파일 경로, 클래스/함수 시그니처, 핵심 제약을 포함한다.}

## Acceptance Criteria

이 step의 작업 종류에 맞는 실제 실행 커맨드를 적는다. 단위/슬라이스가 기본이며, 아래에 해당하면 해당 테스트를 **반드시 포함**한다 (`/docs/testing-conventions.md`의 태그 기준).

```bash
# 기본 (단위·슬라이스)
./gradlew test

# DB·Testcontainers 통합이 필요하면
./gradlew integrationTest

# Spring Batch 관련이면
./gradlew batchTest

# 동시성·락·race 관련이면 (격리 실행)
./gradlew concurrencyTest
```

(위는 선택용 예시다. 실제 step에는 해당하는 커맨드만 남긴다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
  - architecture.md 디렉토리 구조를 따르는가?
  - ADR 기술 스택을 벗어나지 않았는가?
  - 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- {X를 하지 마라. 이유: Y}
- 기존 테스트를 깨뜨리지 마라
````

## Step 작성 규칙

- step 하나는 테스트 가능한 사용자 기능 단위를 기본값으로 삼는다.
- API feature는 요청 하나 또는 강하게 결합된 요청 묶음 단위로 나눈다.
- 같은 정책과 aggregate를 공유하는 command 동작은 한 step으로 묶을 수 있다.
- command와 query는 데이터 흐름, 권한, 검증 기준이 다르면 별도 step으로 분리한다.
- domain, repository, service, controller, request/response DTO, test는 같은 사용자 기능 완성에 필요하면 한 step에 함께 포함한다.
- 레이어별 step은 공통 도메인 선행 작업, 독립 DB 마이그레이션, 대규모 공유 계약 변경처럼 분리 검증이 명확히 필요한 경우에만 사용한다.
- 루트 docs 동기화와 회고록 작성은 phase의 step으로 두지 않는다. 각각 Stage 8(Root Sync), Stage 9(Retrospective)에서 phase 바깥에서 수행한다. 따라서 phase의 마지막 step은 마지막 구현 step이다.
- 신규 파일이 많거나 reviewer가 한 번에 판단하기 어렵다면 레이어가 아니라 사용자 기능/정책 경계를 기준으로 더 작게 나눈다.
- “이전 대화에서 논의한 바와 같이” 같은 외부 참조를 쓰지 않는다.
- 필요한 파일 경로와 배경은 문서 안에 직접 적는다.
- 구현 단위와 커밋 단위가 같은 기능/정책 목적을 가리키도록 step을 나눈다.
- 여러 파일을 변경해도 하나의 기능 동작을 완성하기 위한 변경이면 하나의 커밋 단위로 묶는다.
- 파일 단위로 과도하게 쪼개지 않는다.
- 목적이 다른 변경은 step을 나누거나 별도 커밋 단위로 분리한다.
- 구현 코드는 인터페이스와 제약 중심으로 유도하고, 내부 구현을 전부 박아넣지 않는다.
- Acceptance Criteria는 추상 문장이 아니라 실행기가 다시 돌릴 수 있는 실제 실행 커맨드여야 한다.
- 기본 예시는 `./gradlew test`를 사용한다.
- 실제 step 초안에서 더 구체적인 Gradle 커맨드로 좁힐 수 있지만, 좁히는 경우 step 문서에 그 이유를 명시한다.
- 아래 변경이 포함된 step은 전체 테스트 `./gradlew test`를 Acceptance Criteria에 포함한다.
  - entity builder/constructor 변경
  - enum 필수화 또는 상태 정책 변경
  - repository 조회 조건 변경
  - 공통 예외/응답 변경
  - 인증/권한 경계 변경
- step의 작업 종류에 맞는 테스트를 Acceptance Criteria에 포함한다. `./gradlew test`(단위·슬라이스)가 기본이고, DB/Testcontainers 통합이면 `integrationTest`, Spring Batch면 `batchTest`, 동시성·락·race면 `concurrencyTest`를 추가한다. 통합/batch/concurrency가 필요한 step에서 `./gradlew test`만 적어 누락하지 않는다.
- step의 `읽어야 할 파일`에 그 작업이 건드리는 영역의 루트 문서를 매핑대로 명시한다. try-catch/예외 → `/docs/exception-strategy.md`, 로그 → `/docs/logging-conventions.md`, 테스트 → `/docs/testing-conventions.md`, 설계 결정 → 관련 `/docs/adr.md` 등 step이 다루는 관심사를 보고 누락 없이 고른다.
- shared domain 계약을 바꾸는 step은 사용처 탐색 커맨드를 `검증 절차`에 포함한다.
  - 예: `rg "Product.builder" src/main/java src/test/java`

## 상태 전이 규칙

- 성공: `status = completed`, `summary` 작성
- 반복 수정 후에도 실패: `status = error`, `error_message` 작성
- 사용자 개입 필요: `status = blocked`, `blocked_reason` 작성

## 에러 복구

- `error` 또는 `blocked` 발생 시 agent는 즉시 중단하고 사용자에게 실패 step, 실패 사유, 관련 output 파일을 보고한다.
- 실행 중 재시도를 위해 `execute.py`가 현재 step을 `pending`으로 되돌리는 것은 정상 실행 상태 갱신이다.
- 사용자 승인 전에는 해당 step의 상태, 실패 필드, step 요구사항, Acceptance Criteria, 문서를 수정하지 않는다.
- 사용자가 복구를 승인한 뒤에만 상태와 실패 필드를 정리하고 재실행할 수 있다.
- 복구 시에도 변경한 문서와 상태 파일을 보고하고, `execute.py` 재실행 승인을 별도로 받는다.

## 실행 (셔틀 루프)

Task별 `phases` 구조가 준비되면 worktree 안에서 메인 에이전트가 `execute.py`와 sub-agent 사이를 오가는 셔틀 루프로 실행한다. 상세 절차는 SKILL.md의 **Stage 6 (Execution)**를 따른다. 요지:

```bash
# worktrees/<type>-<task-name>/ 안에서
python3 .claude/skills/harness-v3/scripts/execute.py init     docs/tasks/<task-name>/phases/<phase-name> \
  --developer-model sonnet --reviewer-model opus --commit-model haiku
# 이후 반복: step → (JSON 지시대로 sub-agent를 Task로 호출) → step ...
python3 .claude/skills/harness-v3/scripts/execute.py step     docs/tasks/<task-name>/phases/<phase-name>
# done 이면:
python3 .claude/skills/harness-v3/scripts/execute.py finalize docs/tasks/<task-name>/phases/<phase-name>
```

- `init`은 게이트(checklist 승인)·worktree 검증·마커·tmux·모델 기록을 1회 수행한다.
- `step`은 활성 step의 `_stage`를 한 칸 진행시키고, 에이전트 호출이 필요하면 `{"action":"invoke_agent", "role":..., "model":..., "prompt_file":..., "handoff_out":...}` JSON을 STDOUT에 한 줄 내보낸다. 메인 에이전트가 그 sub-agent를 Task로 호출하고, 끝나면 다시 `step`을 부른다.
- 각 step은 developer 실행 → Acceptance Criteria 재검증(execute.py) → reviewer 판정 → committer 커밋을 통과해야 `completed`로 인정된다.
- 성공한 step은 phase index에 `completed`로 남는다. handoff·output·AC output·workflow checklist·로그(`logs/`)는 로컬 산출물이다.
- phase 종료 시 `execute.py finalize`가 두 커밋을 추가한다: 잔여 task 문서 `docs:` 커밋, phase index 두 개 `chore:` 커밋.
- retryable failure는 execute.py가 같은 step을 최대 3회 재시도한다. 최종 `blocked`/`error`는 자동 복구하지 않고 사용자 승인을 기다린다.

## per-step 상태 필드 (phase index의 steps[])

`execute.py`가 디스크에 영속하는 실행 상태. 세션이 중단돼도 같은 명령 재실행 시 이 값으로 재개한다.

- `_stage`: 현재 step의 미니 상태(`need_developer` → `ran_developer` → `ran_reviewer` → `ran_commit` → `done`). 분기: reviewer가 `retryable_error`면 `need_developer`로 되돌림, `blocked`면 중단.
- `_attempt`: 현재 step의 시도 순번. 재시도마다 +1, 최대 `MAX_RETRIES`(3).
- `_prev_error`: 직전 실패 사유(재시도 시 developer 프롬프트에 주입). 통과하면 제거.
- `_pre_commit_head`: 커밋 직전 HEAD SHA(committer 후 커밋 생성 여부 판정용).

## 내부 상태 — `.harness/` (worktree 루트)

harness 실행 중에만 쓰는 폴더로 `.gitignore`로 제외된다(`/.harness/`). 세 가지를 담는다.

- `active-phase` (마커): `execute.py init`이 현재 phase 상대경로를 한 줄 적고 `finalize`가 지운다(중단 시엔 남기고 다음 `step`이 자가복구). 로깅 hook이 읽어 로그를 `<phase>/logs/`에 쓴다. hook은 `$CLAUDE_PROJECT_DIR`가 아니라 도구 호출의 `cwd`→`git rev-parse --show-toplevel`로 worktree 루트를 먼저 찾고 실패 시에만 fallback하므로, develop 루트에서 띄워도 worktree 로그로 간다.
- `logstate-<agent_id>.json`: 증분 로깅 북마크(찍은 키 + header + footer 여부). PostToolUse가 갱신한다. **SubagentStop은 지우지 않는다**(재기상 재덤프 방지로 보존). 정리는 `execute.py`가 init/finalize/중단(blocked·error) 시 일괄로 한다.
- `log-panes.json`: `init`이 만든 tmux 로그 pane id(`{"panes":[...], "main_pane":...}`). 별도 프로세스인 `finalize`(및 중단 경로)가 읽어 pane을 kill하고 지운다.

## 핸드오프 — `<phase>/handoff/`

sub-agent가 execute.py에게 결과를 전달하는 유일한 통로(휘발성). execute.py는 부모 프로세스가 아니므로 디스크 파일로만 결과를 읽는다. agent는 작업의 마지막 행동으로 핸드오프를 쓴다.

- `stepN-dev.json`: developer가 작성. `{ "step": N, "attempt": M, "ok": true, "summary": "...", "struggles": "..."|null }`
- `stepN-review.json`: reviewer가 작성. `{ "step": N, "decision": "approved"|"retryable_error"|"blocked", "message": "..." }`
- committer는 핸드오프를 쓰지 않는다. 커밋 생성 여부는 execute.py가 git HEAD 변화로 확인한다.
- `stepN-*-prompt.md`: execute.py가 각 호출 전 작성하는 동적 프롬프트(컨텍스트+step+핸드오프 지시). sub-agent가 Read로 읽는다.

## 실행 산출물

- `stepN-output.json`: execute.py가 검증 통과 시 정본으로 누적하는 회고 재료(아래 구조).
- `stepN-ac-output.json`: Acceptance Criteria 재실행 결과(execute.py 작성).
- `<phase>/handoff/`: 위 핸드오프·프롬프트 파일.
- `logs/`: 각 sub-agent의 transcript를 포맷한 사람용 로그(`harness-v3-developer.log` / `harness-v3-reviewer.log` / `harness-v3-committer.log`). **PostToolUse hook**(`log_progress.sh`)이 도구 경계마다 새로 확정된 부분만 증분 append하고(실시간), **SubagentStop hook**(`log_stop.sh`)이 마지막 보류분을 flush한 뒤 완료 박스(footer)를 붙인다. 상태파일(logstate)은 SubagentStop이 **지우지 않고**(재기상 대비) `execute.py`가 정리한다. tmux 3-pane이 tail한다. 중복은 상태파일(footer 가드 포함)+requestId dedup으로 막는다.

### `stepN-output.json` 구조 (v3 — 경량)

developer의 자기보고가 아니라, execute.py가 **검증을 통과시킨 시도만** 기록하는 회고 자료다. transcript 전문이나 exitCode는 담지 않는다(로그가 따로 있음).

```json
{
  "step": 1,
  "attempts": [
    { "attempt": 1, "ok": true, "struggles": "A 방식 시도, 테스트 X 깨짐 → B로 전환" },
    { "attempt": 2, "ok": true, "struggles": null }
  ]
}
```

- `attempt`: 시도 순번(재시도·재실행을 가로질러 누적).
- `ok`: 해당 시도의 developer 자기보고 성공 여부.
- `struggles`: 해당 시도의 시행착오(handoff에서 옮겨 적음). 없으면 `null`.

이 산출물은 Stage 9(Retrospective)에서 step별 시행착오를 종합하는 1차 자료다. 상세 과정은 `logs/`에 있다.

위 output·handoff·`logs/`·`workflow-checklist.json`은 로컬 실행 추적용이며 커밋하지 않는다(`.gitignore`로 제외 — `logs/`, `handoff/`, `step*-output.json` 등). phase index는 step 진행 기준으로 phase 종료 시 커밋한다. task 문서 초안은 Stage 6 진입 직전 `docs:` 커밋으로 등록한다.

이미 `completed`인 step은 phase index의 `summary`/`completed_at`과 `_stage=done`으로 확인한다.
