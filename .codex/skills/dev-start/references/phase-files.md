# Phase Files Reference

이 문서는 `dev-start` skill이 기능별 문서와 `phases` 구조를 설계하거나 초안을 만들 때 따르는 참조 문서다.

## 기능 문서 기본 세트

각 기능은 `docs/features/<feature-name>/` 아래에서 아래 문서를 기본 생성한다.

- `prd.md`
- `architecture.md`
- `adr.md`
- `api-spec.md`
- `db-schema.md`

각 문서는 `docs/features/_templates/` 템플릿을 복사해 시작한다.

## `docs/features/<feature-name>/phases/index.json`

해당 기능 내부의 phase 목록을 관리하는 인덱스다.

```json
{
  "phases": [
    { "dir": "0-bootstrap", "status": "pending" },
    { "dir": "1-domain", "status": "pending" }
  ]
}
```

필드 규칙:

- `dir`: 기능 내부 phase 디렉토리명
- `status`: `pending` | `completed` | `error` | `blocked`
- 타임스탬프 필드는 생성 시 넣지 않는다
- phase 이름은 `<순번>-<slug>` 형식을 사용한다

## `docs/features/<feature-name>/phases/<phase-name>/index.json`

task 상세 상태 파일이다.

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

- `summary`: 완료 산출물의 한 줄 요약
- `error_message`: 실패 원인
- `blocked_reason`: 사용자 개입 또는 외부 제약으로 인해 막힌 사유

## `docs/features/<feature-name>/phases/<phase-name>/step{N}.md`

각 step은 자기완결적인 작업 문서여야 한다.

````md
# Step {N}: {name}

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/<feature-name>/prd.md`
- `/docs/features/<feature-name>/architecture.md`
- `/docs/features/<feature-name>/adr.md`
- `/docs/features/<feature-name>/api-spec.md`
- `/docs/features/<feature-name>/db-schema.md`
- `{이전 step에서 생성/수정된 파일 경로}`

기능 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/architecture.md`
- `/docs/ADR.md`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

{구체적인 구현 지시. 파일 경로, 클래스/함수 시그니처, 핵심 제약을 포함한다.}

## 수정 가능 경로

- `src/main/java/com/commerce/<feature-name>/**`
- `src/test/java/com/commerce/<feature-name>/**`
- `docs/features/<feature-name>/**`

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

- step 하나에 여러 모듈을 한 번에 넣지 않는다.
- “이전 대화에서 논의한 바와 같이” 같은 외부 참조를 쓰지 않는다.
- 필요한 파일 경로와 배경은 문서 안에 직접 적는다.
- `수정 가능 경로` 섹션은 필수이며, 현재 step이 수정해도 되는 경로만 명시한다.
- 구현 코드는 인터페이스와 제약 중심으로 유도하고, 내부 구현을 전부 박아넣지 않는다.
- Acceptance Criteria는 추상 문장이 아니라 실제 실행 커맨드여야 한다.
- 기본 예시는 `./gradlew test`를 사용하고, 실제 step 초안에서는 해당 feature에 맞는 더 구체적인 Gradle 커맨드로 좁힐 수 있다.

## 상태 전이 규칙

- 성공: `status = completed`, `summary` 작성
- 반복 수정 후에도 실패: `status = error`, `error_message` 작성
- 사용자 개입 필요: `status = blocked`, `blocked_reason` 작성

## 에러 복구

- `error` 발생 시: `docs/features/<feature-name>/phases/<phase-name>/index.json`에서 해당 step의 `status`를 `pending`으로 바꾸고 `error_message`를 삭제한 뒤 재실행한다.
- `blocked` 발생 시: `blocked_reason`에 적힌 사유를 해결한 뒤, `status`를 `pending`으로 바꾸고 `blocked_reason`을 삭제한 뒤 재실행한다.

## 실행

기능별 `phases` 구조가 준비되면 아래 실행기로 현재 phase를 순차 실행할 수 있다.

```bash
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name>
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> --push
```

실행 흐름은 아래와 같다.

1. 기능 내부 phase index와 현재 phase index를 읽는다.
2. `pending` step을 순차 실행한다.
3. step 결과에 따라 `completed`, `error`, `blocked` 상태를 기록한다.
4. 완료된 step의 `summary`는 다음 step 컨텍스트로 누적된다.
5. `--push`가 있으면 마지막에 현재 feature 브랜치를 원격으로 push한다.
