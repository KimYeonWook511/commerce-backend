# phase 파일 구조 · AC 스키마 · agent 반환 계약 (harness-v4)

harness-v4가 다루는 phase 디렉터리의 파일 구조와, 각 단계가 주고받는 데이터 계약을 정의한다.
SKILL.md의 흐름을 이해한 뒤, 구체 스펙이 필요할 때 이 문서를 참조하라.

## 목차
1. 디렉터리 레이아웃
2. index.json 구조 (phase / task)
3. step 문서와 Acceptance Criteria 스키마 (`# expect:`)
4. AC 검증 결과(ac-output.json) 구조
5. agent 반환 JSON 계약 (5종)
6. 실행 산출물과 정본의 구분

---

## 1. 디렉터리 레이아웃

```
docs/tasks/<task>/
├── phases/
│   ├── index.json                  # task 레벨: phase 목록과 각 phase status
│   └── <phase>/                    # 예: 1-domain, 2-api
│       ├── index.json              # phase 레벨: steps(목차) + execution(설정) + step status
│       ├── workflow-checklist.json  # 9-Stage 진행 추적 + 실행 전 게이트(preflight가 검사)
│       ├── step1.md                # step 문서 (구현 지시 + ## Acceptance Criteria)
│       ├── step2.md
│       ├── step1-ac-output.json    # (실행 산출) AC 검증 결과 attempts 누적 — reviewer가 읽음
│       └── logs/                   # (실행 산출) <role>.log 사람용 로그
├── adr.md                          # task ADR (이번 작업의 결정사항)
└── ...                             # architecture.md, api-spec.md 등 task 문서
```

루트 문서(`docs/adr.md`, `docs/logging-conventions.md`, `docs/commit-conventions.md` 등)는 전역 베이스다.
task 문서가 이번 작업의 구체 결정, 루트 문서가 전역 원칙이다(충돌 시 task 우선).

---

## 2. index.json 구조

### phase 레벨 (`phases/<phase>/index.json`)

```json
{
  "steps": [
    { "step": 1, "name": "domain-model", "status": "pending", "summary": null },
    { "step": 2, "name": "repository",   "status": "pending", "summary": null }
  ],
  "execution": {
    "developer_model": "sonnet",
    "reviewer_model": "opus",
    "committer_model": "haiku",
    "push": false
  },
  "created_at": "2026-06-16T...",
  "completed_at": null
}
```

- `steps`: 이 phase의 step 목차. 각 step의 `status`(`pending`|`completed`|`blocked`|`error`)와 `summary`는
  recorder/finalizer가 갱신한다. **workflow는 `pending`인 step만 실행한다** — `completed`는 건너뛰고,
  `blocked`/`error`로 멈춘 step은 자동 재개하지 않는다. 사람이 원인을 고친 뒤
  `execute.py reset-step <PHASE_DIR> --step N`으로 그 step을 pending으로 되돌려야 재실행 시 다시 잡힌다.
  (안 고친 채 재실행해 같은 실패를 반복하며 토큰을 낭비하지 않도록, reset을 명시적 신호로 요구한다.)
- `execution`: agent별 모델과 push 여부. preflight가 이 값을 workflow 인자로 옮긴다.
- `completed_at`: finalize가 채운다.

### task 레벨 (`phases/index.json`)

```json
{
  "phases": [
    { "phase": "1-domain", "status": "completed" },
    { "phase": "2-api",    "status": "pending" }
  ]
}
```

finalizer가 phase 완료 시 해당 phase status를 `completed`로 동기화한다.

> index.json은 **phase 입력 명세서**다(steps 목차 + execution 설정). status만 finalize로 미루지 않고
> 매 step recorder가 기록하는 이유는, 중단 후 재실행에서 완료 step을 건너뛰려면 디스크 정본이 필요하기 때문이다.

### workflow-checklist.json (9-Stage 진행 + 실행 전 게이트)

`harness` workflow의 9-Stage 진행을 기록한다. phase를 만들 때(Stage 5 File Drafting) 반드시 생성하며,
항목 제목은 `SKILL.md`의 Workflow 제목과 정확히 일치해야 한다.

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

**실행 전 게이트**: `preflight`가 이 파일을 검사한다. **Stage 1~5가 모두 `completed`이고 Stage 6가
`pending`/`in_progress`가 아니면 preflight가 거부**하여(`{"ok": false, ...}`) workflow가 기동되지 않는다.
즉 탐색·논의·설계·문서작성을 건너뛰고 곧바로 구현에 돌입하는 것을 기계적으로 막는다.

필드 규칙:
- `workflow`: 항상 `harness`
- `items`: `SKILL.md`의 1~9번 Stage 순서·제목을 그대로 사용(order/title 일치 필수).
- `Execution`(6)은 workflow 기동 시 `in_progress`로, phase 완주 시 `completed`로 갱신한다.
- `PR Review`(7)·`Root Sync`(8)·`Retrospective`(9)는 workflow 바깥에서 일어나며 agent가 수동으로 갱신한다.
  단 8·9는 7이 `completed`된 뒤에만 갱신한다(리뷰 코멘트 부재를 7 완료로 보지 않는다).
- 이 파일은 로컬 추적용이며 커밋하지 않는다(`.gitignore` 대상).

---

## 3. step 문서와 Acceptance Criteria 스키마

step 문서(`stepN.md`)는 자유 형식의 구현 지시 + `## Acceptance Criteria` 섹션으로 구성된다.

### Acceptance Criteria 파싱 규칙 (verify-ac가 따르는 실제 규칙)

- `## Acceptance Criteria` 헤더부터 다음 `## ` 헤더(또는 문서 끝)까지가 AC 본문.
- 그 안의 ` ```bash ` 또는 ` ```sh ` 코드블록의 **각 줄**이 명령 후보.
- 빈 줄은 무시.
- **`# expect: N`** 형태의 주석은 **바로 다음 명령**의 기대 exit code를 지정한다.
- 그 외 `#` 주석은 무시.
- 기대 exit를 지정하지 않은 명령의 기본 기대값은 **0**.

### 예시

```markdown
## Acceptance Criteria

​```bash
./gradlew :domain:test --tests "*MoneyTest"
# expect: 1
test -f src/main/java/legacy/OldMoney.java
​```
```

- 첫 명령 `./gradlew ...`: 기대 exit 0 (지정 없음) → 테스트 통과해야 ok.
- 둘째 명령 `test -f ...OldMoney.java` + `# expect: 1`: **파일이 없어야** ok
  (있으면 `test -f`가 0을 반환하는데 기대는 1이므로 실패). "이 파일이 삭제됐어야 한다"를 표현.

> `# expect:`로 기대 exit를 명시하면 위 둘째처럼 "없어야 한다"(exit 1 기대) 류 AC도 표현된다.

---

## 4. AC 검증 결과 (`stepN-ac-output.json`)

verify-ac가 실행할 때마다 그 attempt 결과를 누적 기록한다(감사용). 구조:

```json
{
  "step": 1,
  "attempts": [
    {
      "attempt": 1,
      "passed": false,
      "results": [
        { "command": "./gradlew test", "expectExit": 0, "actualExit": 1, "ok": false }
      ]
    },
    { "attempt": 2, "passed": true, "results": [ ... ] }
  ]
}
```

- `passed`: 그 attempt의 모든 명령이 기대 exit와 일치했는가.
- `results[].ok`: 명령별 `actualExit == expectExit` 여부.
- verify-ac는 첫 실패에서 멈추지 않고 **모든 명령을 끝까지 실행**해 전체 실패 상황을 한 번에 보여준다.
- AC가 없는 step이면 `{ "passed": true, "no_ac": true }` 형태를 반환한다.

reviewer는 이 파일의 최신 attempt를 읽어 developer의 자기보고와 대조한다(불일치 시 retryable_error).

---

## 5. agent 반환 JSON 계약

workflow(JS)는 각 agent의 반환 JSON으로 분기한다. agent는 **마지막 행동으로 해당 JSON만** 출력한다.

### developer
```json
{
  "step": 1, "attempt": 1,
  "status": "completed | blocked | error",
  "summary": "<완료한 변경 한 줄. 빈 문자열 금지>",
  "blocked_reason": "<blocked/error일 때 사람이 판단할 것. 아니면 null>",
  "struggles": "<버린 접근·막힌 점·ADR 충돌 처리. 없으면 null>",
  "ac": { "passed": true, "results": [ ... verify-ac 출력 ... ] }
}
```

### reviewer
```json
{ "step": 1, "decision": "approved | retryable_error | blocked", "message": "<사유>" }
```
- `approved`가 기본. `retryable_error`는 한 문장으로 짚을 수 있는 구체적 결함(또는 AC 정합 불일치).
  `blocked`는 사람 개입이 반드시 필요할 때만(드물게).

### committer
```json
{ "committed": true, "commits": ["feat: ...", "docs: ..."] }
```
- workflow는 이 보고를 신뢰하고 엄격 검증하지 않는다. 안전망은 finalizer + `git status`.

### recorder (stdout JSON)
```json
{ "ok": true, "step": 1, "status": "completed" }
```

### finalizer (stdout JSON)
```json
{ "ok": true, "chore_committed": true, "pushed": false, "completed_at": "..." }
```

> JSON 형식 보장: 기본은 **프롬프트 계약**(agent가 "이 JSON만 출력")이고, workflow의 `parseAgentJson`이
> 코드펜스·앞뒤 설명을 견고하게 벗겨 파싱한다. 런타임이 `schema` 강제를 지원하면(trial 확인 후)
> 그 위에 스키마 검증을 얹는다(`harness-v4-execute.js`의 `SUPPORTS_SCHEMA`).

---

## 6. 실행 산출물 vs 정본

| 구분 | 파일 | 누가 쓰나 | 커밋 여부 |
|---|---|---|---|
| **정본** | `phases/<phase>/index.json`, `phases/index.json` | recorder(step status) / finalizer(completed_at·task index) | finalizer가 chore 커밋 |
| **정본** | 코드·task 문서 | developer | committer가 목적별 커밋 |
| **실행 산출** | `stepN-ac-output.json` | verify-ac(attempt마다 append) | **커밋 안 함** (reviewer가 자기보고 대조에 읽음) |
| **실행 산출** | `logs/<role>.log` | 로깅 hook | **커밋 안 함** |

- developer/recorder/committer/finalizer는 자기 영역 밖의 정본을 건드리지 않는다(PreToolUse 정책으로도 강제).
- 특히 phase index는 committer가 staging하지 않는다 — finalizer가 phase 끝에 chore 커밋으로 일괄 처리한다.
- **`stepN-output.json`은 만들지 않는다.** 결과를 agent
  반환값으로 받고 검증은 ac-output.json·git·log·index가 대신하므로 읽는 주체가 없다(dead artifact 회피).
