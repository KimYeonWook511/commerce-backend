# Phase Files Reference

이 문서는 `dev-start` skill이 태스크별 문서와 `phases` 구조를 설계하거나 초안을 만들 때 따르는 참조 문서다.

## 태스크 문서 기본 세트

각 태스크는 `docs/tasks/<task-name>/` 아래에서 아래 문서를 기본 생성한다.

- `prd.md`
- `architecture.md`
- `adr.md`
- `api-spec.md`
- `db-schema.md`

각 문서는 `docs/tasks/_templates/` 템플릿을 복사해 시작한다.

phase 구조를 만들 때는 아래 파일도 반드시 생성한다.

- `docs/tasks/<task-name>/phases/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/index.json`
- `docs/tasks/<task-name>/phases/<phase-name>/workflow-checklist.json`
- `docs/tasks/<task-name>/phases/<phase-name>/step{N}.md`

`workflow-checklist.json`은 `docs/tasks/_templates/phases/workflow-checklist.json`을 복사해 시작한다.

## `docs/tasks/<task-name>/phases/index.json`

해당 태스크 내부의 phase 목록을 관리하는 인덱스다.

```json
{
  "phases": [
    { "dir": "0-bootstrap", "status": "pending" },
    { "dir": "1-domain", "status": "pending" }
  ]
}
```

필드 규칙:

- `dir`: 태스크 내부 phase 디렉토리명
- `status`: `pending` | `completed` | `error` | `blocked`
- 타임스탬프 필드는 생성 시 넣지 않는다
- phase 이름은 `<순번>-<slug>` 형식을 사용한다

## `docs/tasks/<task-name>/phases/<phase-name>/index.json`

step 실행 상태 파일이다.

```json
{
  "project": "<project-name>",
  "phase": "<phase-name>",
  "steps": [
    { "step": 0, "name": "project-setup", "status": "pending" },
    { "step": 1, "name": "core-types", "status": "pending" },
    { "step": 2, "name": "api-layer", "status": "pending" }
  ]
}
```

필드 규칙:

- `project`: 프로젝트명
- `phase`: phase 이름이며 디렉토리명과 일치해야 한다
- `steps[].step`: 0부터 시작하는 순번
- `steps[].name`: kebab-case slug
- `steps[].status`: 초기값은 모두 `pending`

자동 기록 필드는 초안 생성 시 넣지 않는다.

- `created_at`
- `started_at`
- `completed_at`
- `failed_at`
- `blocked_at`

상태별 추가 필드 의미:

- `summary`: 완료한 변경의 한 줄 요약
- `error_message`: 실패 원인
- `blocked_reason`: 사용자 개입 또는 외부 제약으로 인해 막힌 사유

## `docs/tasks/<task-name>/phases/<phase-name>/workflow-checklist.json`

`dev-start` workflow 진행 상태를 기록하는 checklist다. phase를 만들 때 반드시 생성하며, 항목 제목은 `SKILL.md`의 Workflow 제목과 일치해야 한다.

```json
{
  "workflow": "dev-start",
  "status": "drafting",
  "items": [
    { "order": 1, "title": "Explore", "status": "completed" },
    { "order": 2, "title": "Discuss", "status": "completed" },
    { "order": 3, "title": "Step Design", "status": "completed" },
    { "order": 4, "title": "File Drafting", "status": "completed" },
    { "order": 5, "title": "Execution Authorization", "status": "pending" },
    { "order": 6, "title": "Execution", "status": "pending" }
  ]
}
```

필드 규칙:

- `workflow`: 항상 `dev-start`
- `status`: `drafting` | `authorized` | `in_progress` | `completed`
- `items`: `SKILL.md`의 1~6번 Workflow 순서와 제목을 그대로 사용한다.
- `Execution Authorization`이 `completed`이면 `authorization` 객체를 포함한다.

`authorization` 예시:

```json
{
  "order": 5,
  "title": "Execution Authorization",
  "status": "completed",
  "authorization": {
    "escalation_approved": true,
    "approval_prompt_mode": "per_run",
    "prefix_rule": null,
    "approved_by": "user",
    "approved_at": "2026-04-30T15:30:00+0900"
  }
}
```

`approval_prompt_mode`는 `per_run` 또는 `saved_prefix_rule`을 사용한다. `saved_prefix_rule`이면 `prefix_rule`은 `["python3", ".codex/skills/dev-start/scripts/execute.py"]`다.

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
- `/docs/commit-conventions.md`
- `{이전 step에서 생성/수정된 파일 경로}`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/architecture.md`
- `/docs/ADR.md`

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

{구체적인 구현 지시. 파일 경로, 클래스/함수 시그니처, 핵심 제약을 포함한다.}

## 수정 가능 경로

- `src/main/java/com/commerce/<task-name>/**`
- `src/test/java/com/commerce/<task-name>/**`
- `docs/tasks/<task-name>/**`

## Acceptance Criteria

```bash
./gradlew test
```

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
- root docs sync는 최종 구현과 전체 테스트가 끝난 뒤 마지막 step에서 한 번 수행한다.
- 신규 파일이 많거나 reviewer가 한 번에 판단하기 어렵다면 레이어가 아니라 사용자 기능/정책 경계를 기준으로 더 작게 나눈다.
- “이전 대화에서 논의한 바와 같이” 같은 외부 참조를 쓰지 않는다.
- 필요한 파일 경로와 배경은 문서 안에 직접 적는다.
- `수정 가능 경로` 섹션은 필수이며, 현재 step이 수정해도 되는 경로만 명시한다.
- 모든 step의 `수정 가능 경로`에는 `docs/tasks/<task-name>/**`를 포함한다. task 문서와 phase index는 실행 중 함께 갱신될 수 있기 때문이다.
- 구현 단위와 커밋 단위가 같은 기능/정책 목적을 가리키도록 step을 나눈다.
- 여러 파일을 변경해도 하나의 기능 동작을 완성하기 위한 변경이면 하나의 커밋 단위로 묶는다.
- 파일 단위로 과도하게 쪼개지 않는다.
- 목적이 다른 변경은 step을 나누거나 별도 커밋 단위로 분리한다.
- 커밋 메시지는 `docs/commit-conventions.md`를 따른다.
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
- shared domain 계약을 바꾸는 step은 사용처 탐색 커맨드를 `검증 절차`에 포함한다.
  - 예: `rg "Product.builder" src/main/java src/test/java`

## 상태 전이 규칙

- 성공: `status = completed`, `summary` 작성
- 반복 수정 후에도 실패: `status = error`, `error_message` 작성
- 사용자 개입 필요: `status = blocked`, `blocked_reason` 작성

## 에러 복구

- `error` 또는 `blocked` 발생 시 agent는 즉시 중단하고 사용자에게 실패 step, 실패 사유, 관련 output 파일을 보고한다.
- 실행 중 재시도를 위해 `execute.py`가 현재 step을 `pending`으로 되돌리는 것은 정상 실행 상태 갱신이다.
- 사용자 승인 전에는 해당 step의 상태, 실패 필드, step 요구사항, Acceptance Criteria, 문서, `수정 가능 경로`를 수정하지 않는다.
- 사용자가 복구를 승인한 뒤에만 상태와 실패 필드를 정리하고 재실행할 수 있다.
- 복구 시에도 변경한 문서와 상태 파일을 보고하고, `execute.py` 재실행 승인을 별도로 받는다.

## 실행

태스크별 `phases` 구조가 준비되면 아래 실행기로 현재 phase를 순차 실행할 수 있다.

```bash
python3 .codex/skills/dev-start/scripts/execute.py docs/tasks/<task-name>/phases/<phase-name>
python3 .codex/skills/dev-start/scripts/execute.py docs/tasks/<task-name>/phases/<phase-name> --push
```

실행 개요:

- 실행기는 checklist 승인 상태를 확인한 뒤 가장 앞의 `pending` step부터 순차 실행한다.
- 각 step은 developer 실행, Acceptance Criteria 재검증, reviewer 검토를 모두 통과해야 `completed`로 인정된다.
- 성공한 step은 phase index에 `completed`로 남긴다. 실행 output, AC output, review output, workflow checklist는 로컬 실행 산출물로만 둔다.
- 완료된 step의 기능 변경은 review 통과 후 실행기가 커밋한다. phase index는 phase 종료 시 별도 `chore` 커밋으로 기록한다.
- 실행 중 retryable failure는 실행기가 같은 step을 재시도할 수 있다.
- 최종 `blocked` 또는 `error` 발생 시 자동 복구하지 않고 사용자 검토와 승인을 기다린다.

## 실행 산출물

각 step은 실행기로 완료되어야 하며, 수동으로 `status = completed`만 기록하면 안 된다.

- `stepN-output.json`: developer worker 실행 결과
- `stepN-ac-output.json`: Acceptance Criteria 재실행 결과
- `stepN-review-output.json`: reviewer worker 검토 결과

위 output 파일과 `workflow-checklist.json`은 로컬 실행 추적용이며 커밋하지 않는다. phase index는 step 진행 기준으로 사용하고 phase 종료 시 커밋한다.

이미 `completed`인 step은 phase index의 `summary`와 `completed_at`으로 확인한다. output 파일 존재 여부는 이전 step 재개 조건으로 사용하지 않는다.
