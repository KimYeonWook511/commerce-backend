---
name: harness-v4
description: 이 저장소에서 "harness로 phase 실행", "하네스로 이 phase 돌려줘", "ultracode로 phase 실행", "<task>의 <phase>를 자동 실행" 같은 요청이 오면 반드시 이 skill을 사용한다. docs/tasks/<task>/phases/<phase>/ 레이아웃의 phase를, 직접 손으로 작업하지 말고 dynamic workflow(/harness-v4-execute)를 기동해 자동 완주시킨다. preflight로 phase index.json을 읽어 workflow args(JSON)를 만든 뒤 workflow를 띄우면, step별로 developer→reviewer→committer→recorder 서브에이전트가 돌고 끝에 finalize까지 자동으로 진행된다. phase·step·acceptance criteria·index.json·worktree 기반 자동 구현 작업이라면, 사용자가 "skill"·"harness"·"workflow"라는 말을 정확히 쓰지 않아도 이 skill을 적용해 workflow로 실행한다.
---

# harness-v4 — phase 자동 실행 하네스

이 저장소의 `docs/tasks/<task>/phases/<phase>/` phase 하나를 **Claude Code dynamic workflow**로
끝까지 자동 완주시킨다. 사람은 phase를 지정하고 plan을 승인하기만 하면, step별로
구현→AC검증→리뷰→커밋→상태기록이 자동으로 돌고, 모든 step이 끝나면 phase가 마무리(finalize)된다.

## 언제 / 어떻게 동작하나

사람이 "이 phase를 harness로 실행"이라고 하면, 아래 절차를 따른다. **이 흐름은 한 번 트리거되면
workflow가 백그라운드에서 phase를 완주하므로, 중간에 사람의 추가 입력은 받지 않는다**
(막히면 workflow가 멈추고 메인 세션에 보고한다).

### 1. 전제 확인 (한 번만)

- 현재 Claude Code 버전이 dynamic workflow를 지원하는가(v2.1.154 이상), `/config`에서 Dynamic workflows가 켜져 있는가.
- 실행 대상 phase 경로(`docs/tasks/<task>/phases/<phase>/`)와 그 안에 `index.json`이 있는가.
- 일반적으로 develop에서 세션을 시작한 뒤 worktree를 만들고 그 안으로 이동(cwd)해 실행한다.
  (로그·상태 마커는 cwd의 git 최상위(worktree)를 기준으로 기록된다.)

### 2. preflight — phase 명세를 읽어 실행 인자를 만든다

phase의 `index.json`을 해석해 workflow에 넘길 인자(JSON)를 만든다. 아래를 실행하라
(`<EXECUTE>`는 이 skill의 `scripts/execute.py` 경로, `<PHASE_DIR>`는 대상 phase 디렉터리):

```
python3 <EXECUTE> preflight <PHASE_DIR>
```

이 명령은 다음을 한다:
- `index.json`의 `steps`(목차)와 `execution`(모델·push 설정)을 읽어 workflow 인자 객체를 stdout(JSON)으로 출력.
- hook이 phase별 로그를 찾도록 `.harness/active-phase` 마커를 기록.
- 출력 JSON에는 `execute`(execute.py 절대경로), `phase_dir`, `steps`, `execution` 등이 들어 있다.

### 3. plan 승인 후 workflow 기동

preflight가 준 인자로 저장된 workflow 명령을 호출한다:

```
/harness-v4-execute <preflight가-출력한-JSON>
```

- Claude Code가 실행 전 **계획(plan) 카드**를 보여주면, 사람이 phase·step 구성을 확인하고 승인(Ctrl+G 등)한다.
- 승인되면 workflow가 백그라운드에서 돈다. 진행 상황은 `/workflows` 뷰로 관찰한다
  (각 step의 agent·도구 호출·결과를 드릴인해 볼 수 있다). 사람 친화적 로그는 `<phase>/logs/<role>.log`에도 쌓인다.

**중요 — workflow는 반드시 위처럼 명시적으로 `/harness-v4-execute`를 호출해 띄운다.**
그냥 "ultracode로 알아서 해줘"라고 두면 모델이 native sub-agent(Task)로 빠져 이 하네스 구조를
쓰지 않을 수 있다. 결정적으로 이 workflow를 쓰려면 저장된 명령을 직접 트리거해야 한다.

### 4. 결과 처리

workflow는 끝나면 결과 JSON을 메인 세션에 반환한다:
- `outcome: "completed"` — 모든 step 완주 + finalize 완료. `finalize` 필드에 push 결과 등.
- `outcome: "blocked"` / `"error"` — `stopped_at_step`·`reason`을 사람에게 전한다. **workflow는 `pending`인 step만
  실행한다**: `completed`인 step은 건너뛰고, `blocked`/`error`로 멈춘 step은 자동 재개하지 않는다.
  사람이 원인을 고친 뒤 그 step을 pending으로 되돌려야(`execute.py reset-step <PHASE_DIR> --step N`) 재실행 시 다시 잡힌다.
  (고치지 않은 채 재실행하면 같은 실패를 반복하며 토큰만 낭비하므로, reset이라는 명시적 신호를 요구한다.
  결과에 `needs_reset: true`가 있으면 reset 후 재실행하라는 뜻이다.)

## 내부 구조 (요약)

workflow(`workflows/harness-v4-execute.js`)가 오케스트레이터이고, 실제 작업은 5개 agent가 한다:

- **developer**(sonnet) — step 구현. `build-context`로 컨텍스트 로드, `verify-ac`로 AC 검증, 결과 JSON 반환.
- **reviewer**(opus) — read-only 검토. AC 자기보고와 정본 대조. 판정 JSON 반환.
- **committer**(haiku) — 목적별 git 커밋. 결과 JSON 반환.
- **recorder**(haiku) — `record-step`으로 step status를 phase index 정본에 기록(재실행 안전성).
- **finalizer**(haiku) — phase 끝에 `finalize`(completed_at·task index·chore 커밋·push).

JS는 shell/git/fs를 직접 못 하므로, 그 작업은 전부 agent가 `scripts/execute.py` 서브커맨드를 통해 한다.

## 더 읽을 것

- **phase 파일 구조·AC 스키마·agent 반환 JSON 스키마**: `references/phase-files.md` 를 읽어라.
  (step 문서의 `## Acceptance Criteria`와 `# expect:` 스키마, index.json 구조, 각 agent 반환 계약이 거기 있다.)

## scripts

- `scripts/execute.py` — 서브커맨드 모음: `preflight` / `build-context` / `verify-ac` / `record-step` / `reset-step` / `finalize`.
  (`reset-step`은 사람이 blocked/error step을 고친 뒤 pending으로 되돌리는 수단.)
- `scripts/acceptance_check.py` — AC 명령 실행·expectExit 비교·attempts 누적.
- `scripts/git_ops.py`, `scripts/step_context.py` — git 헬퍼, 컨텍스트 조립.
- `scripts/transcript_formatter.py`, `scripts/format_events.py` — agent transcript를 사람용 로그로 변환(hook이 사용).
- `scripts/hooks/log_progress.sh`(PostToolUse), `scripts/hooks/log_stop.sh`(SubagentStop) — 증분 로깅 hook.
