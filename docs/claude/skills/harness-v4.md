# harness-v4 (회상용)

> 이 repo의 phase 자동 실행 하네스가 무엇이고, 왜 지금 형태인지 빠르게 떠올리기 위한 문서.
> 사용법은 `.claude/skills/harness-v4/SKILL.md`, 데이터 계약은 그 `references/phase-files.md`를 본다.
> 설계 결정의 상세 근거는 `harness-v4-decision-record.md`에 있다.

## 한 줄 요약

`docs/tasks/<task>/phases/<phase>/` phase 하나를 **Claude Code dynamic workflow**로 자동 완주시킨다.
step마다 developer→reviewer→committer→recorder가 돌고, 모든 step이 끝나면 finalizer가 phase를 닫는다.

## 버전사 — 왜 v4인가

- **v2**: 파이썬 오케스트레이터(`execute.py`)가 `claude -p`를 subprocess로 띄워 phase를 완주했다.
  한 프로세스가 끝까지 도니 단순했지만, 2026-06-15부터 `claude -p`가 **구독 풀과 별개로 과금**되어 폐기.
- **v3**: 과금을 피하려 native sub-agent(Task)로 전환(구독 풀 유지). 그러나 파이썬이 Task를 직접 못 띄워
  "셔틀"(step마다 메인이 대신 spawn) 구조가 강제됐고, 완료 이벤트 오배달(**P3**)로 인한 멈춤이 잔존했다.
- **v4**: 오케스트레이터를 **JavaScript dynamic workflow**로 옮겼다.
  - v2의 장점 회복: workflow가 `agent()`를 **동기 await** 하므로 "한 번 호출 = phase 자동 완주"가 된다.
  - 구독 풀 유지: 대화형 세션에서 실행하므로 `claude -p` 과금이 없다.
  - **P3 소멸**: workflow는 비동기 완료 알림을 쓰지 않고 반환값으로 직접 분기한다(v2 `proc.wait()`와 동일 원리).
    → v3의 멈춤 원인이 구조적으로 사라진다.

즉 v4 = **v2의 자동 완주 + v3의 transcript 기반 hook 로깅 + 구독 풀 과금 + P3 소멸**.

## 핵심 제약 한 가지

**workflow 스크립트(JS)는 shell·git·파일시스템을 직접 못 만진다.** 이 한 줄이 구조 대부분을 설명한다:
- AC 실행·정본 기록·컨텍스트 조립·finalize·커밋은 전부 **agent**가 `scripts/execute.py` 서브커맨드를 통해 한다.
- JS는 "조율"만 한다 — 어떤 agent를 어떤 순서로 부르고, 반환 JSON으로 분기하고, 재시도/중단을 결정.

## 구성 요소

```
.claude/
├── skills/harness-v4/
│   ├── SKILL.md                    # 진입점: "harness로 phase 실행" → preflight → workflow 기동
│   ├── references/phase-files.md   # 데이터 계약(phase 파일·AC·반환 JSON)
│   └── scripts/
│       ├── execute.py              # 서브커맨드: preflight/build-context/verify-ac/record-step/finalize
│       ├── acceptance_check.py     # AC 실행·expectExit 비교·attempts 누적
│       ├── git_ops.py, step_context.py
│       ├── transcript_formatter.py, format_events.py   # transcript → 사람용 로그
│       └── hooks/{log_progress.sh, log_stop.sh}        # 증분 로깅(PostToolUse/SubagentStop)
├── workflows/harness-v4-execute.js # 오케스트레이터(저장 명령: /harness-v4-execute)
├── agents/harness-v4-{developer,reviewer,committer,recorder,finalizer}.md
├── hooks/pre_tool_use_policy.py    # agent별 권한 정책(v3/v4 공존)
└── settings.json                   # hook 등록(v3 보존 + v4)
```

## 흐름

```
사람: "이 phase를 harness로 실행"
  → SKILL.md 자동 invoke
  → execute.py preflight <phase>      (index.json → workflow 인자, active-phase 마커 기록)
  → /harness-v4-execute <args>        (plan 승인 후 백그라운드 기동)
       for step in steps (completed면 skip):
         developer → (AC통과·status확인) → reviewer → committer → recorder
         실패 시 MAX_RETRIES(3)까지 재시도, blocked/error면 즉시 종료·메인 보고
       finalizer                       (completed_at·task index·chore 커밋·push)
  → outcome(completed|blocked|error) 반환
```

진행 관찰은 `/workflows` 뷰(런타임 제공). 사람용 포맷 로그는 `<phase>/logs/<role>.log`에 별도로 쌓인다.

## v3 대비 바뀐 것 (요점)

- 핸드오프 파일(stepN-dev.json 등) **폐지** → agent가 결과 **JSON 반환**, JS가 반환값으로 분기.
- AC 검증: developer가 `verify-ac` **1회** 실행하고 결과를 반환(중복 실행 금지). 판정은 결정적 코드가 함.
- AC 스키마에 `# expect: N` 추가 → "없어야 한다(exit 1)" 류 표현 가능(v3의 exit-0 하드코딩 함정 해소).
- status 흐름은 **v2식**: developer가 completed/blocked/error를 자기선언, JS가 분기.
- recorder/finalizer **신설**: JS가 못 하는 정본 기록·git 마무리를 전담.
- **tmux pane 제외**: v2 tmux의 존재 이유는 `claude -p`가 안 보여서였는데, v4는 `/workflows` 뷰가 가시성을
  제공하므로 불필요. v3의 tmux pane 좀비 문제(P4)도 통째로 사라진다. (로그 파일 자체는 유지.)

## trial 결과 (smoke test로 확정된 것)

dynamic workflow는 research preview다. 더미 phase(_trial, step 2개)로 happy path를 한 번 완주시켜
아래가 **실측 확정**됐다:
- **workflow 명령화**: `export const meta = {...}`가 스크립트 첫 문장(순수 리터럴)이면 `.claude/workflows/`의
  수동 배치 파일도 `/harness-v4-execute` 명령으로 등록된다. meta 없으면 "must be the FIRST statement"로 거부.
- **`args` 주입**: 이 런타임에서 `args`는 **JSON 문자열**로 들어온다(객체가 아님). 그래서 JS 본문이
  `typeof args === 'string'`이면 `JSON.parse`로 정규화한다. (문서엔 "구조적 데이터"라 돼 있으나 실측은 문자열.)
- **`agent()`·5종 순서**: developer→reviewer→committer→recorder(step마다) + 끝에 finalizer가 의도대로 실행.
  `parseAgentJson`이 반환을 안전하게 파싱(반환형 차이에 무관).
- **verify-ac / index 기록 / git 커밋 분리 / push 스킵 / 로그 5종 / `/workflows` 단계표시** 모두 정상.

### 아직 안 본 것 (남은 확인)
- **blocked/error 경로**: smoke test는 happy path만 탔다. 일부러 막히는 step으로 "멈춤·메인 보고·재실행 시
  완료 step 건너뛰기(P3 부재)"를 따로 확인해야 한다.
- **`schema` 강제**: `SUPPORTS_SCHEMA=false`로 프롬프트 계약만 쓰는 중. opts.schema 강제가 되는지는 미확인.
- **workflow agent에서 PreToolUse hook 발동 여부**: 미발동이면 권한 정책은 탐지만 되고, 실제 차단은
  각 agent `.md`의 `tools` 제약이 baseline.

> 발견·수정된 버그(참고): preflight emit에 `execute`(execute.py 절대경로) 필드 누락 → 추가됨.
> 마커 삽입 시 `steps=[` 할당이 깨진 SyntaxError → 복원됨. 둘 다 execute.py에 반영 완료.
