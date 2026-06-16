# harness-v4

> 이 repo의 phase 자동 실행 하네스. 이 문서 하나로 harness-v4가 무엇이고 어떻게 도는지 전체를 파악할 수 있다.
> 사용법 요약은 `.claude/skills/harness-v4/SKILL.md`, 파일/데이터 계약은 그 `references/phase-files.md`를 본다.

## 1. 한 줄 정의

`docs/tasks/<task>/phases/<phase>/` 레이아웃의 **phase 하나**를 Claude Code **dynamic workflow**로
자동 완주시킨다. step마다 구현→검증→검토→커밋→기록이 돌고, 모든 step이 끝나면 phase를 마무리(finalize)한다.
**한 번 기동하면 사람 개입 없이 phase 끝까지** 간다(막히면 멈추고 보고).

## 2. 구성 요소

```
.claude/
├── skills/harness-v4/
│   ├── SKILL.md                    # 진입점: "harness로 phase 실행" → preflight → workflow 기동
│   ├── references/phase-files.md   # 파일 구조·AC 스키마·반환 JSON 계약
│   └── scripts/
│       ├── execute.py              # 서브커맨드: preflight/build-context/verify-ac/record-step/reset-step/finalize
│       ├── acceptance_check.py     # AC 실행·expectExit 비교·attempts 누적
│       ├── git_ops.py, step_context.py
│       ├── transcript_formatter.py, format_events.py   # transcript → 사람용 로그
│       └── hooks/{log_progress.sh, log_stop.sh}        # 증분 로깅(PostToolUse/SubagentStop)
├── workflows/harness-v4-execute.js # 오케스트레이터 (저장 명령 /harness-v4-execute)
├── agents/harness-v4-{developer,reviewer,committer,recorder,finalizer}.md
├── hooks/pre_tool_use_policy.py    # agent별 권한 정책
└── settings.json                   # hook 등록
```

**핵심 제약 — workflow 스크립트(JS)는 shell·git·파일시스템을 직접 못 만진다.** 이 한 줄이 구조 대부분을
설명한다. JS는 "조율"만 하고(어떤 agent를 어떤 순서로 부르고, 반환값으로 분기), 실제 파일·git·AC 실행은
전부 **agent가 `execute.py` 서브커맨드를 통해** 한다.

## 3. 역할 분담 (5개 agent)

| agent | 모델 | 하는 일 | 결과 |
|---|---|---|---|
| developer | sonnet | step 구현. `build-context`로 컨텍스트 로드 → 구현 → `verify-ac`로 AC 검증 | JSON 반환 `{status, summary, ac, ...}` |
| reviewer | opus | read-only 검토. ac-output.json을 읽어 developer 자기보고와 대조 | JSON 반환 `{decision, message}` |
| committer | haiku | 목적별 git 커밋(코드/문서 분리). phase index는 안 건드림 | JSON 반환 `{committed, commits}` |
| recorder | haiku | `record-step`으로 step status를 phase index 정본에 기록 | stdout JSON |
| finalizer | haiku | phase 끝에 `finalize`(completed_at·task index·chore 커밋·push) | stdout JSON |

오케스트레이터는 `harness-v4-execute.js`(workflow). 위 agent들을 순서대로 호출하고 반환값으로 분기한다.

## 4. 전체 실행 흐름

```
사람: "이 phase를 harness로 실행"
  → SKILL.md 자동 invoke
  → execute.py preflight <phase>   (index.json 읽어 workflow args(JSON) 생성 + active-phase 마커 기록)
  → /harness-v4-execute <args>     (plan 승인 후 백그라운드 기동)
        │
        │  phase('Steps')
        │  for step in args.steps:
        │     ├─ status == completed   → skip (이미 끝남)
        │     ├─ status != pending     → 멈춤 (blocked/error: reset 대기. 아래 6장)
        │     └─ status == pending     → runStep(step) 실행
        │            developer → (AC확인) → reviewer → committer → recorder
        │            성공: 다음 step / 실패: 재시도 또는 중단 (아래 5장)
        │
        │  모든 step 완료 시:
        │  phase('Finalize')
        │  → finalizer (completed_at·task index·chore 커밋·push)
        │
  → outcome 반환: completed | blocked | error
```

진행 관찰은 `/workflows` 런타임 뷰. 사람용 포맷 로그는 `<phase>/logs/<role>.log`에 별도로 쌓인다.

## 5. step 하나의 처리 (runStep) — status별 분기와 재시도

한 step은 **최대 MAX_RETRIES(=3)번** 시도한다. 한 번의 시도(attempt)는
**developer → AC확인 → reviewer → committer → recorder** 전체다. 각 단계의 판정에 따라:

### developer 단계
developer가 반환한 `status`로 분기:
- `completed` → AC 확인으로 진행
- `blocked` / `error` → **즉시 중단**. 그 step에서 멈추고 phase 종료, 메인에 보고(재시도 안 함).
- (JSON 파싱 실패) → 같은 attempt 사유를 담아 **재시도**(developer부터 다시).

### AC 확인 (developer 반환의 ac 필드)
developer가 verify-ac로 실행한 AC 결과(`ac.passed`)를 본다:
- `passed: true` → reviewer로 진행
- `passed: false` → 실패한 명령을 사유로 담아 **재시도**(developer가 다시 구현). 3회 소진 시 `error` 종료.

### reviewer 단계
reviewer가 반환한 `decision`으로 분기:
- `approved` → committer로 진행
- `retryable_error` → reviewer 메시지를 사유로 담아 **재시도(developer부터 다시)**. 즉 reviewer가
  "이거 고쳐"라고 하면 developer가 재구현한다. 3회 소진 시 `error` 종료.
- `blocked` → **즉시 중단**(재시도 안 함). 사람 개입 필요. phase 종료, 메인 보고.

### committer → recorder
reviewer가 approve하면 committer가 커밋(결과는 신뢰, 엄격 검증 없음 — 안전망은 finalizer+git status),
recorder가 그 step을 `completed`로 phase index에 기록. → 다음 step으로.

### 재시도 소진
3번 시도해도 completed에 못 닿으면(AC 계속 실패, reviewer가 계속 retryable 등) → 그 step을 `error`로 종료.

**요약**: 재시도하는 것 = AC 실패 / reviewer retryable / 파싱 실패 (전부 developer부터 다시). 즉시 멈추는 것 =
developer blocked·error / reviewer blocked. 어느 경우든 **completed로 끝나지 않으면 결국 멈추고 사람에게 보고**한다.

## 6. 재개 정책 (v2 방식: pending-only)

phase를 재실행하면 workflow는 **`pending`인 step만 실행**한다:
- `completed` step → 건너뜀 (이미 끝남. 재실행 안전성)
- `blocked` / `error` step → **자동 재개하지 않고 그 자리에서 멈춘다** (`needs_reset: true` 반환)

**왜 자동 재개하지 않나**: 예를 들어 "docker가 안 떠서 blocked" 됐는데, 사람이 docker를 안 띄운 채 그냥
재실행하면 — 같은 step이 또 끝까지 가서 또 실패한다. developer(sonnet) 토큰만 낭비된다. 그래서 **"고쳤다"는
명시적 신호를 요구한다**: 사람이 원인을 고친 뒤

```
python3 <execute.py> reset-step <PHASE_DIR> --step N
```

로 그 step을 `pending`으로 되돌려야(blocked/error 잔여 필드 제거) 재실행 시 그 step부터 재개된다.
안 되돌리면 실행 자체를 안 하므로 토큰 낭비가 없다.

이 "재개의 영속성"을 위해 step status를 **매 step recorder가 디스크 정본(index.json)에 기록**한다.
JS 변수·런타임 저널은 실행 경계를 못 넘으므로, 어디까지 했는지는 디스크에만 남는다.

## 7. 상태·산출 파일

| 파일 | 누가 쓰나 | 누가 읽나 | 커밋 |
|---|---|---|---|
| `phases/<phase>/index.json` | recorder(step status)·finalizer(completed_at) | preflight·재실행 시 skip 판단 | finalizer가 chore 커밋 |
| `phases/index.json` (task 레벨) | finalizer(phase status 동기화) | — | finalizer가 chore 커밋 |
| `step{N}-ac-output.json` | verify-ac(attempt마다 append) | **reviewer**(자기보고 대조) | 커밋 안 함(감사용) |
| `logs/<role>.log` | 로깅 hook | 사람(사후·회고) | 커밋 안 함 |
| 코드·task 문서 | developer | reviewer | committer가 목적별 커밋 |

- **output.json은 만들지 않는다.** v2/v3는 step{N}-output.json을 남겼으나(그땐 검증 로직이 읽었음),
  v4는 결과를 반환값으로 받고 검증은 ac-output.json·git·log·index가 대신하므로 읽는 주체가 없다.
- **ac-output.json은 유지.** reviewer가 developer의 AC 자기보고가 정본과 맞는지 대조하는 데 읽는다(자기보고 가드).
- 타임스탬프는 KST(+09:00).

## 8. 안전장치 (권한 정책)

`pre_tool_use_policy.py`가 agent_type별로 제약(PreToolUse hook):
- committer → git 화이트리스트(status/diff/log/add/commit만)
- reviewer → 핸드오프 외 Write 차단(사실상 read-only)
- recorder → phase index.json 외 Write 차단
- finalizer → 블랙리스트(일반 push 허용, force push·rm-rf 차단)
- developer 등 → 블랙리스트(위험 명령만 차단)

> 단, workflow agent에서 PreToolUse가 실제 발동하는지는 미확인. 미발동이면 정책은 탐지만 되고 실제 차단은
> 각 agent `.md`의 `tools` 제약이 baseline으로 담당한다.

## 9. 버전사 (왜 v4인가)

- **v2**: 파이썬 오케스트레이터가 `claude -p`를 subprocess로 띄워 phase 완주. 한 프로세스라 단순했으나
  2026-06-15부터 `claude -p`가 별도 과금되어 폐기.
- **v3**: native sub-agent(Task)로 전환(구독 풀 유지). 파이썬이 Task를 못 띄워 "셔틀"(step마다 메인이 대신
  spawn) 강제 + 핸드오프 파일로 결과 주고받음. 완료 이벤트 오배달(P3)로 멈춤 잔존.
- **v4**: 오케스트레이터를 JS dynamic workflow로 이전. workflow가 `agent()`를 동기 await하므로
  "한 번 기동 = 자동 완주"(v2 장점 회복) + 구독 풀 유지 + 비동기 완료 알림을 안 써 **P3 소멸**.
  핸드오프는 반환값으로 대체해 폐지. tmux pane은 `/workflows` 뷰가 가시성을 주므로 제외(v3 P4도 소멸).

**핸드오프란**: agent끼리 결과를 주고받는 중간 파일. agent는 각자 독립 컨텍스트라 developer가 한 일을
reviewer가 직접 모르므로, v3는 developer가 결과를 파일(stepN-dev.json)에 쓰고 다음이 읽는 방식을 썼다.
v4는 workflow가 `agent()` 반환값을 직접 받으므로 이 중간 파일이 불필요해 폐지했다.

## 10. dynamic workflow 환경 메모 (실측)

- **명령 등록**: `export const meta = {...}`가 스크립트 첫 문장(순수 리터럴)이어야 `.claude/workflows/`의
  파일이 `/harness-v4-execute` 명령으로 등록된다. meta 없으면 "must be the FIRST statement"로 거부.
- **args 주입**: `args`는 JSON **문자열**로 들어온다(객체 아님). JS가 `typeof args === 'string'`이면
  `JSON.parse`로 정규화한다.
- **phase()**: 본문에서 `phase('Steps')`/`phase('Finalize')`를 호출하면 `/workflows` 뷰에 단계가 표시된다.
- **schema**: `agent(prompt, {schema})`로 반환 구조 강제가 가능하다고 알려져 있으나 미검증. 현재는
  프롬프트 계약(이 JSON만 반환) + `parseAgentJson`으로 처리하고, `SUPPORTS_SCHEMA` 토글로 후일 얹는다.

## 11. trial로 확인된 것 / 남은 것

확인됨(시나리오 테스트): 정상 완주, blocked 중단(finalize 안 함, P3 부재), 완료 phase 재실행 전부 skip,
blocked→고침→재개. verify-ac·index 기록·git 커밋 분리·push 스킵·로그 5종·`/workflows` 단계표시.

남은 확인:
- **pending-only 재개 재검증**: 루프 조건을 "completed skip"→"pending만 실행"으로 바꾸고 reset-step을
  추가한 뒤의 실환경 trial은 mock만 했다. blocked→reset-step→재실행을 실제로 한 번 더 보면 좋다.
- **AC 자기보고 불일치 가드**: reviewer가 ac-output 대조로 거짓 보고를 잡는 경로는 happy path라 미실측.
- **재시도(AC 실패)**: developer가 AC를 직접 만족시켜 실환경 트리거가 어렵다(런타임 의존 실패 필요).
- **PreToolUse 발동 / schema 강제**: 위 8·10장 참고.

> 수정 이력(참고): preflight `execute` 필드 누락 추가 · 마커 삽입으로 깨진 SyntaxError 복원 ·
> task index `dir`→`phase` 키 수정 · 타임스탬프 UTC→KST.
