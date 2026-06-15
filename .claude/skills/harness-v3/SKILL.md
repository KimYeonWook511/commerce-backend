---
name: harness-v3
description: 개발 시작 전 문서 탐색, 논의, step 설계, phases 초안 작성 workflow를 수행할 때 사용하는 skill
---

# Harness Workflow

이 skill은 아래 상황에서 사용한다.

- 구현 전에 작업을 단계별로 나누고 싶을 때
- Task별 `phases/` 구조의 계획 파일 초안이 필요할 때
- 큰 작업을 자기완결적인 step으로 분해해야 할 때

이 skill은 개발 전 탐색, step 설계, task 문서/phases 초안 작성, 준비된 phase의 실행기 연결을 담당한다.
실행기 `execute.py`는 step 4에서 생성한 worktree 안에서 실행된다. v3에서는 `execute.py`가 직접 에이전트를 띄우지 않고, 메인 에이전트(셔틀)가 `execute.py`의 지시(JSON)에 따라 native sub-agent(developer/reviewer/committer)를 Task로 호출한다. 커밋과 선택적 push는 committer sub-agent와 finalize가 담당한다.

## 용어

이 skill은 두 종류의 "단계"를 구분해서 쓴다. 혼동을 막기 위해 용어를 고정한다.

- **Stage**: harness workflow 전체의 진행 단계(1~9). 아래 "Workflow 상태 표"의 각 행이 하나의 Stage다.
- **step**: phase 내부의 구현 작업 단위. 커밋 1개에 대응한다. `execute.py`가 상태를 진행시키고, 메인 에이전트가 sub-agent를 호출한다.

즉 Stage 6(Execution) "안에서" 메인 에이전트가 `execute.py`와 sub-agent 사이를 오가는 셔틀 루프로 phase의 step들을 진행하는 포함 관계다.

---

## 필수 준수 규칙

아래 규칙은 반드시 지켜야 한다.

- 이 skill을 사용하는 작업에서는 `phases`가 준비된 이후의 기본 구현 경로를 수동 파일 수정이 아니라 `execute.py` 실행으로 본다.
- 사용자가 명시적으로 `execute.py`를 쓰지 말라고 하지 않은 이상, agent가 직접 구현을 시작하면 안 된다.
- `Implement the plan`은 자동으로 직접 구현을 뜻하지 않는다. `phases` 준비 여부와 실행 승인 여부를 먼저 확인해야 한다.
- Workflow는 phase별 `workflow-checklist.json`으로 추적하며, 다음 Stage로 넘어가기 전 이전 Stage가 모두 `completed`여야 한다.
- `harness` 진행 상태를 사용자에게 보고할 때는 1~9번 Workflow 상태 표를 함께 보여준다.
- `File Drafting` 완료 후에는 반드시 멈추고 작성된 문서 경로를 사용자에게 보고한 뒤 검토 응답을 기다린다. 바로 `execute.py` 실행 요청으로 넘어가지 않는다.
- `execute.py` 실행 전 반드시 사용자에게 진행 의사를 확인하고, 사용자가 진행을 승인한 뒤에만 실행한다(가벼운 확인 — 별도 Plan Mode·`ExitPlanMode` 절차는 거치지 않는다). 자동 코드 검증은 없으므로 이 룰은 agent가 직접 지킨다.
- Stage 6에서 PR을 연 뒤 agent는 멈추고 사용자의 Stage 7(PR Review) 검토 완료 신호를 기다린다. "리뷰 코멘트가 아직 없음"은 Stage 7 완료가 아니다. Stage 7 완료가 확인되기 전에는 Stage 8(Root Sync)·Stage 9(Retrospective)에 착수하지 않는다.
- Stage 8(Root Sync)에서 루트 문서를 갱신할 때는 ADR=append, 스냅샷(architecture/db-schema/api-spec)=overwrite, 루트 PRD=목록 갱신으로 동작이 다르다. 한 지시로 뭉치지 않는다.

---

## Workflow 상태 표

`harness`를 진행하면서 사용자에게 상태를 보고할 때는 아래 표 형식을 사용한다.

| 단계 | Stage | 상태 |
| --- | --- | --- |
| 1 | Explore |  |
| 2 | Discuss |  |
| 3 | Step Design |  |
| 4 | Worktree 생성 및 이동 |  |
| 5 | File Drafting |  |
| 6 | Execution |  |
| 7 | PR Review |  |
| 8 | Root Sync |  |
| 9 | Retrospective |  |

상태 표는 `workflow-checklist.json`이 있으면 그 값을 기준으로 표시한다. checklist 생성 전에는 현재 대화에서 실제 완료한 Stage만 `✅`로 표시한다.

`execute.py`는 Stage 6까지만 자동으로 상태를 갱신한다. Stage 7~9는 `execute.py` 바깥에서 일어나며, agent가 진행하면서 수동으로 상태를 갱신한다.

---

## 먼저 읽을 것

항상 먼저 아래를 읽는다.

- `CLAUDE.md`
- `docs/commit-conventions.md`

그 다음 현재 작업 대상 task 문서를 먼저 읽는다.

- `docs/tasks/<task-name>/prd.md`
- `docs/tasks/<task-name>/architecture.md`
- `docs/tasks/<task-name>/adr.md`
- `docs/tasks/<task-name>/api-spec.md`
- `docs/tasks/<task-name>/db-schema.md`

task 문서와 `phases` 문서로 부족한 공통 맥락이 있을 때만 `CLAUDE.md`의 `참고 문서` 섹션을 따라 루트 `docs/` 기준 문서를 추가로 읽는다.
작업 범위에 직접 연결된 코드와 테스트도 함께 읽는다.

---

## Workflow

### 1. Explore

- `CLAUDE.md`를 읽고 현재 Repo 규칙을 파악한다.
- 현재 작업 대상 task 폴더의 문서와 `phases` 문서를 우선 읽고 현재 구조와 변경 범위를 파악한다.
- 공통 아키텍처, 다른 도메인 ERD, 전역 ADR 같은 정보가 더 필요할 때만 루트 `docs/` 기준 문서를 추가로 읽는다.
- 작업 범위에 직접 연결된 코드와 테스트를 함께 읽는다.
- 이미 답할 수 있는 질문은 하지 않는다.
- 병렬 탐색이 가능한 환경이면 관련 영역을 나눠 추가 탐색할 수 있다.

---

### 2. Discuss

아래 경우에는 구현 전에 사용자와 논의한다.

- 요구사항이 둘 이상으로 해석될 수 있을 때
- 설계 선택이 결과에 큰 영향을 줄 때
- 외부 인증, API 키, 수동 설정 등 사용자 개입이 필요할 때
- 기존 구조나 규칙과 충돌 가능성이 있을 때

#### 계획 전 루트 문서 정합성 확인 (필수)

구현 방향을 제안하기 전에, 작업이 기존 결정·원칙과 충돌하는지 **agent가 먼저 확인**한다. 임의로 계획을 세운 뒤 "충돌 지점을 검토해 달라"고 사용자에게 떠넘기지 않는다.

1. **항상**: `docs/adr.md`의 `## Task ADR 색인`을 읽고, 표의 **"주요 결정 키워드" 열**을 훑어 이 작업과 관련된 ADR/task adr을 식별한 뒤 해당 항목을 확인한다.
2. **영역별**: 작업이 건드리는 영역에 해당하는 루트 문서를 추가로 확인한다.

| 작업이 건드리는 것 | 확인할 루트 문서 |
| --- | --- |
| 설계 방식·정책 (어떻게 풀까) | `docs/adr.md` (+ 관련 task adr) |
| 구조·레이어·책임 배치 | `docs/architecture.md` |
| 기능 범위 (할 일/안 할 일) | `docs/prd.md` |
| 데이터 모델·테이블·제약 | `docs/db-schema.md` |
| API 추가·변경 | `docs/api-spec.md` |

3. **제안 형식**: 확인 결과를 아래 형식으로 제시한다. 이 형식은 agent가 문서를 실제로 확인했다는 근거이며, 사용자는 정합 라벨만 보고 판단할 수 있다.

```
## 제안

관련 결정/문서:
- ADR-020 (cross-aggregate ID 참조) — 이 작업이 새 연관을 추가하므로 관련
- architecture.md (payment 도메인 책임) — 결제 상태를 건드리므로 관련

선택지:
A. <방식 A> — ADR-020 일치. 추천.
B. <방식 B> — ADR-020 위반. 채택하려면 ADR 갱신 필요.

→ 어느 방향으로 진행할까요?
```

- 기존 결정과 충돌하는 방향은 반드시 "위반/갱신 필요"로 명시한다.
- ADR이 26개 이상이므로 전문을 모두 읽지 말고, 색인으로 관련 항목을 식별한 뒤 그 항목만 깊이 읽는다.

---

### 3. Step Design

사용자가 계획 작성 또는 step 분해를 요청하면 `phases` 구조를 기준으로 초안을 만든다.

#### Phase 설계

phase는 "그 단위만으로 한 번 통합·검증하고, 회고를 남길 가치가 있는 덩어리"다. step이 커밋 단위라면 phase는 통합 사이클 단위다.

- 기본값은 task당 phase 1개다. 통합 지점이 한 번뿐인 보통 크기의 task는 phase를 나누지 않는다.
- 아래 중 하나가 분명할 때만 phase를 여러 개로 나눈다.
  - 강한 선후 의존: 앞 phase가 끝나야 다음 phase를 안전하게 시작할 수 있다. (예: 공통 도메인/인프라 선행 → 그 위에 기능)
  - 중간 검증 가치: 큰 작업에서 중간에 한 번 끊어 제대로 됐는지 확인하고 가는 게 의미 있다.
- 큰 작업을 phase 없이 step만 길게 늘어놓지 않는다. 중간 통합 지점이 없으면 검증·회고가 끝으로 몰려 되돌림 비용이 커진다.
- phase 이름은 `<순번>-<slug>` 형식을 쓴다. 단일 phase의 기본 이름은 `0-main`으로 한다.

#### Step 설계 원칙

- 한 step은 테스트 가능한 사용자 기능 단위를 기본값으로 삼는다.
- API feature는 domain, repository, service, controller, test가 같은 사용자 기능 완성에 필요하면 한 step에 함께 포함한다.
- 레이어별 step 분리는 공통 도메인 선행 작업, 독립 DB 마이그레이션처럼 분리 검증이 명확히 필요한 경우에만 사용한다.
- command/query는 데이터 흐름과 검증 기준이 다르면 분리하고, 같은 정책과 aggregate를 공유하는 command 동작은 묶을 수 있다.
- 각 step 문서는 독립 실행 가능한 자기완결 문서여야 한다.
- step 설계 시 구현 단위와 커밋 단위가 같은 기능/정책 목적을 가리키도록 나눈다. 파일 단위로 과도하게 쪼개지 않는다.
- 관련 문서 경로와 이전 step 결과를 이해하는 데 필요한 파일 경로를 명시한다.
- 구현 지시는 인터페이스와 핵심 제약 위주로 작성하고, 내부 구현은 과도하게 고정하지 않는다.
- Acceptance Criteria는 실행 가능한 커맨드로만 적는다.
- 주의사항은 `하지 마라. 이유: ...` 형식으로 구체적으로 작성한다.
- step name은 kebab-case slug를 사용한다.

루트 docs 동기화(`sync-root-docs`)와 회고록 작성(`write-retrospective`)은 더 이상 phase의 step으로 두지 않는다. 각각 Stage 8(Root Sync), Stage 9(Retrospective)에서 phase 바깥에서 수행한다.

---

### 4. Worktree 생성 및 이동

Step Design이 완료되면 작업 브랜치 worktree를 생성하고 그 안으로 이동한다.

```bash
cd "$(git rev-parse --git-common-dir)/.."
git worktree add worktrees/<type>-<task-name> -b <type>/<task-name> develop
cd worktrees/<type>-<task-name>
```

`git worktree add` 실행과 `cd` 이동이 모두 완료된 시점에 이 Stage가 ✅ 완료된 것으로 본다.
이후 모든 파일 작성과 `execute.py` 실행은 worktree root를 기준으로 수행한다.

이동 직후 아래 중 하나를 실행해 worktree 이동 여부를 반드시 확인한다.

```bash
pwd
# 또는
git branch --show-current
```

`pwd` 결과가 `worktrees/<type>-<task-name>` 경로여야 하고, `git branch --show-current` 결과가 `<type>/<task-name>` 브랜치여야 한다. 확인 없이 Stage 5로 넘어가지 않는다.

---

### 5. File Drafting

worktree 안에서 아래 파일 초안을 작성한다.

- `docs/tasks/<task-name>/prd.md`
- `docs/tasks/<task-name>/architecture.md`
- `docs/tasks/<task-name>/adr.md`
- `docs/tasks/<task-name>/api-spec.md`
- `docs/tasks/<task-name>/db-schema.md`
- `docs/tasks/<task-name>/phases/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/workflow-checklist.json`
- `docs/tasks/<task-name>/phases/<phase-name>/step{N}.md`

포맷과 상세 규칙은 `references/phase-files.md`를 따른다.

파일 생성 승인 전 금지:
- task 문서 초안, `phases/index.json`, step 문서를 직접 만들지 않는다.
- 계획이 완성됐더라도 승인 없이 repo 파일을 수정하지 않는다.

File Drafting 완료 후 필수 중단:
- 작성 또는 수정한 task 문서, phase index, step 문서, `workflow-checklist.json` 경로를 사용자에게 보고한다.
- checklist의 `File Drafting`까지만 `completed`로 둔다.
- `Execution`은 `execute.py`가 시작할 때 `in_progress`로 갱신하므로, File Drafting 완료 시점에는 `pending`으로 둔다.
- 이 시점의 checklist는 `Explore`, `Discuss`, `Step Design`, `Worktree 생성 및 이동`, `File Drafting`만 `completed`여야 하고, `Execution` 이후는 `pending`이어야 한다.
- 사용자의 단순한 "진행해", "계속해", "Implement the plan"은 문서 검토 완료 또는 실행 승인으로 해석하지 않는다.

---

### 6. Execution (v3 — 셔틀 루프)

> v2와의 차이: v2는 `execute.py`를 한 번 실행하면 끝까지 돌았다. v3는 `execute.py`가 sub-agent를 직접 부를 수 없으므로,
> **메인 에이전트(이 세션)가 `execute.py`와 sub-agent 사이를 오가는 셔틀** 역할을 한다.
> `execute.py`는 게이트·판정·상태전이만 하고, "지금 누구를 부르라"는 지시(JSON)를 STDOUT에 한 줄 내보낸다.
> 메인 에이전트는 그 지시대로 sub-agent를 Task로 호출하고, 끝나면 다시 `execute.py step`을 부른다.

#### 실행 전 (v2와 동일)

1. File Drafting 결과와 실행 계획을 사용자에게 보고하고 진행 의사를 가볍게 확인받는다.
2. 승인되면 File Drafting에서 작성한 task 문서를 한 커밋으로 묶어 `docs:` 타입으로 커밋한다. (대상 목록은 아래 참고)
3. `AskUserQuestion`으로 agent별 실행 모델을 수집한다. (Developer=sonnet / Reviewer=opus / Commit=haiku 권장)

#### 실행 환경 (반드시 확인)

- **sub-agent는 무인으로 실행돼야 한다.** 각 agent 정의(`.claude/agents/harness-v3-*.md`)에 `permissionMode: bypassPermissions`가 있다.
  단 **메인 세션이 `auto` 모드면 frontmatter가 무시**되므로, 메인 세션은 `default` 또는 `bypassPermissions` 모드에서 실행한다.
- **`CLAUDE_CODE_SUBAGENT_MODEL` 환경변수가 설정돼 있으면 per-invocation 모델 선택을 덮어쓴다.** 사용자가 고른 모델이 적용되도록
  이 환경변수는 **비워둔다(unset)**.

#### init

worktree 안에서 `init`을 1회 실행한다. 프리플라이트 게이트(checklist·worktree 검증), 마커·tmux 설정, 모델 기록을 한다.

```bash
python3 .claude/skills/harness-v3/scripts/execute.py init docs/tasks/<task-name>/phases/<phase-name> \
  --developer-model <dev> --reviewer-model <rev> --commit-model <commit>
```

STDOUT으로 `{"action": "initialized", ...}`가 나오면 성공이다. (push 생략은 `--no-push`, finalize에 전달됨)

#### 셔틀 루프 (핵심)

아래를 **반복**한다. 한 번의 `step` 호출은 다음 행동 하나를 JSON으로 반환한다.

```bash
python3 .claude/skills/harness-v3/scripts/execute.py step docs/tasks/<task-name>/phases/<phase-name>
```

STDOUT의 JSON 한 줄을 파싱해 `action`에 따라 처리한다 (사람용 로그는 STDERR로 나오니 무시):

- **`invoke_agent`** — sub-agent를 호출하라는 지시다.
  1. `prompt_file` 경로의 내용을 읽는다.
  2. `role`에 해당하는 sub-agent를 Task(Agent 도구)로 호출한다: `developer`→`harness-v3-developer`, `reviewer`→`harness-v3-reviewer`, `commit`→`harness-v3-committer`.
     - 모델은 JSON의 `model` 값을 사용한다(사용자가 고른 값).
     - prompt는 1에서 읽은 `prompt_file` 내용을 그대로 전달한다.
  3. Task는 블로킹이다. sub-agent가 끝나면(핸드오프를 디스크에 쓰고 종료) 다시 `step`을 호출한다.
  - 메인 에이전트는 **내용을 요약·변형하지 않는다.** prompt_file을 그대로 넘기고, 판정은 `execute.py`가 디스크의 핸드오프로 한다.
- **`done`** — 모든 step이 끝났다. 루프를 종료하고 finalize로 간다.
- **`blocked`** — reviewer가 사람 개입이 필요하다고 판정했다. 루프를 멈추고 사용자에게 `step`·`reason`을 보고한다. finalize하지 않는다.
- **`error`** — step이 재시도 한도(3회)를 넘겨 실패했다. 루프를 멈추고 사용자에게 `step`·`message`를 보고한다. finalize하지 않는다.

> 한 번의 `step` 호출 비용은 거의 0이다(로컬 Python이 index.json을 읽고 JSON 한 줄 반환). 토큰을 쓰는 건 sub-agent 호출뿐이고,
> 그 횟수는 v2와 같다(step당 dev·reviewer·commit). 매 호출이 디스크에 상태를 박으므로, 세션 한도로 중단돼도
> 같은 `step` 명령을 다시 부르면 끊긴 지점부터 이어진다.

#### finalize

`done`이면 finalize를 실행한다. phase 완료 기록, task 문서 잔여분 `docs:` 커밋, phase index `chore:` 커밋, (기본) 원격 push를 한다.

```bash
python3 .claude/skills/harness-v3/scripts/execute.py finalize docs/tasks/<task-name>/phases/<phase-name>
```

`{"action": "finalized", ...}`가 나오면 phase 실행이 끝난 것이다.

#### 중단·재개

- `blocked`/`error`로 멈추면 사용자 승인 없이 자동 복구하지 않는다. 실패 step·사유·관련 핸드오프(`<phase>/handoff/`) 경로를 보고한다.
- 세션 한도 등으로 루프 도중 끊기면, **같은 phase로 셔틀 루프를 다시 시작**한다(init은 재호출해도 마커만 갱신하므로 안전). `execute.py step`이 디스크의 `_stage`/`_attempt`/핸드오프를 보고 중단 지점부터 재개한다.
- 사용자 승인 없이 실패 회피 목적으로 step 요구사항·AC·task 문서·root docs를 수정해 재시도하지 않는다.

#### Stage 6 종료 (v2와 동일)

- 모든 step 완료 + finalize의 push까지 끝나면, agent가 `gh pr create`로 PR을 오픈한다.
- PR 오픈 후 멈추고 사용자의 Stage 7(PR Review) 완료 신호를 기다린다. 리뷰 코멘트 부재 ≠ Stage 7 완료.

#### task 문서 초안 커밋 대상 (실행 전 2번)

- `docs/tasks/<task-name>/prd.md`, `architecture.md`, `adr.md`, `api-spec.md`, `db-schema.md`
- `docs/tasks/<task-name>/phases/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/step{N}.md`

`workflow-checklist.json`, `handoff/`, `logs/`는 `.gitignore`로 제외된다.

---

### 7. PR Review

Stage 6에서 오픈한 PR에 달린 review를 처리한다.

Stage 7은 기본적으로 **사용자가 PR을 검토하는 단계**다. agent는 Stage 6에서 PR을 연 뒤 멈추고 사용자의 검토 완료 신호를 기다린다(요청 시 agent가 검토·반영을 위임받을 수 있으나, 대부분 사용자 검토로 본다).

**Stage 8/9 진입 게이트** — 아래를 분명히 구분한다.

- **리뷰 코멘트 부재 ≠ Stage 7 완료.** PR을 연 직후 코멘트가 아직 없다는 것만으로 Stage 7을 완료로 보지 않는다. 코멘트가 없어도 사용자의 검토는 아직 끝나지 않았을 수 있고, 리뷰가 뒤늦게 코드를 바꾸면 미리 만든 Root Sync/Retrospective 산출물이 stale해져 재작업이 발생한다.
- **Stage 7 완료**는 다음 둘 중 하나다. (1) 사용자가 검토를 종료했다고 알린 경우(코멘트 처리 완료 포함), (2) 사용자가 명시적으로 agent에 검토를 위임했고 그에 따른 반영이 끝난 경우.
- Stage 7 완료가 확인되기 **전에는** Stage 8(Root Sync)·Stage 9(Retrospective)에 착수하지 않는다. 완료가 확인된 뒤에야 Stage 8 → 9를 순서대로 진행하며, 이 두 단계는 agent가 자동으로 처리해도 된다.

review 처리 방식:

- 사람이 review 코멘트(예: Gemini Code Assist)를 보고 항목별 처리 방향(accept / reject / modify)을 **결정**한다. 코드 수정은 사람이 직접 하지 않는다.
- 결정에 따른 코드 수정·답변·resolve는 `/pr-review-resolve <PR번호>` 스킬로 수행한다. 해당 스킬이 review 항목별 커밋·push·thread resolve까지 자체 처리한다.
- 이 Stage의 코드 수정 커밋은 `/pr-review-resolve`가 책임진다. `execute.py`의 commit agent는 Stage 6 전용이며 이 Stage에 관여하지 않는다.
- review 수정이 계약/구조/결정을 바꿨다면 Stage 8에서 그 변경을 루트에 반영한다. 내부 구현만 바뀐 경우 Stage 8 sync가 불필요할 수 있다.

---

### 8. Root Sync

이 Stage는 Stage 7(PR Review) 완료가 확인된 뒤에만 착수한다. PR review까지 코드가 확정된 시점에 루트 문서를 현재 상태로 동기화한다. merge 직전 1회 수행을 기본으로 하며, 코드가 또 바뀌면 다시 실행할 수 있는 멱등 연산으로 본다.

문서 종류별로 동작이 다르다. 한 지시로 뭉치지 않는다.

- **ADR (append)**: 루트 ADR은 수정·삭제하지 않는다. task ADR(staging)에서 새로 채택된 결정만 루트 전역 번호로 이어붙인다. 기존 결정을 대체하면 새 레코드에 `supersedes`를 적고, 옛 레코드 상태를 `superseded`로 바꾼다(상태 한 줄 갱신은 허용). 이미 기록된 결정인지 확인 후 새 결정만 추가한다.
- **architecture / db-schema / api-spec (overwrite)**: 루트 현재 파일과 task 문서를 **둘 다 입력으로 읽고**, 기억으로 재작성하지 말고 현재 루트 기준으로 이번 변경분만 반영한 전체 완성본을 출력한다. 이번에 안 건드린 부분은 보존한다.
- **루트 PRD (목록 갱신)**: 루트 PRD는 제품 비전·전체 기능 목록을 담는 상위 인덱스다. task PRD 본문을 흡수하지 않는다. 신규 기능이면 기능 목록에 요약 한 줄 + task PRD 링크만 추가하고, 기존 기능 변경이면 해당 줄만 갱신한다.

sync 후 agent는 변경 요약(무엇을 갱신했고 무엇을 보존했는지)을 보고하고 사용자 검토를 받는다. 커밋·push는 Stage 6에서 오픈한 같은 PR에 쌓는다.

---

### 9. Retrospective

이번 작업 전체(구현 + PR review 반영 포함)에서 얻은 교훈을 회고록으로 남긴다.

- 각 step의 시행착오는 Stage 6 실행 중 `execute.py`가 남긴 step output 산출물에 기록돼 있다. Stage 9에서는 그 산출물들과 PR review 처리 결과를 종합해 회고록을 작성한다.
- 회고록은 merge 직전, 코드가 모두 확정된 뒤 1회 작성한다. 그래야 PR review에서 지적받아 고친 내용까지 회고에 반영된다.
- 커밋·push는 Stage 6에서 오픈한 같은 PR에 쌓는다. 작성 후 merge로 마무리한다.
