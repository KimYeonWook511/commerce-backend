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
- step 하나는 하나의 핵심 관심사만 다룬다. domain model, repository/service behavior, controller endpoint, web test, root docs sync는 기본적으로 분리한다.
- API feature는 아래 단위로 나누는 것을 기본값으로 삼는다.
  - domain/model contract
  - repository/service behavior
  - create endpoint
  - update/delete endpoint
  - controller/web test
  - root docs sync
- controller, request DTO, service, result DTO, test를 모두 새로 만드는 작업은 한 step에 넣지 않는다.
- 신규 파일이 많거나 여러 레이어를 동시에 건드려 reviewer가 한 번에 판단하기 어렵다면 step을 더 작게 나눈다.
- “이전 대화에서 논의한 바와 같이” 같은 외부 참조를 쓰지 않는다.
- 필요한 파일 경로와 배경은 문서 안에 직접 적는다.
- `수정 가능 경로` 섹션은 필수이며, 현재 step이 수정해도 되는 경로만 명시한다.
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
3. developer worker가 `codex exec --ephemeral -c approval_policy="never" -s workspace-write`로 step을 수행하고 `stepN-output.json`을 기록한다.
4. verifier가 step 상태와 output을 자동 검증한다.
5. step이 `completed`면 실행기가 Acceptance Criteria를 다시 실행하고 `stepN-ac-output.json`을 기록한다.
6. reviewer worker가 `codex exec --ephemeral -c approval_policy="never" -s read-only`로 변경 경로, output, Acceptance Criteria 결과를 바탕으로 실제 repo 파일을 read-only 재검토한다.
7. verifier, Acceptance Criteria 재검증, reviewer를 모두 통과한 경우에만 `completed`를 인정한다.
8. 완료된 step의 `summary`는 다음 step 컨텍스트로 누적된다.
9. `--push`가 있으면 마지막에 현재 feature 브랜치를 원격으로 push한다.

## Git 권한 운영

- 실행기의 Git preflight는 부모 `execute.py` 프로세스가 `.git` 메타데이터 디렉터리에 쓸 수 있는지만 조기에 확인한다. preflight는 권한을 부여하지 않는다.
- developer/reviewer worker의 내부 `codex exec` 권한 설정은 worker 프로세스에만 적용되며, `execute.py`가 직접 수행하는 `git checkout/add/commit` 권한을 대신 부여하지 않는다.
- 사용자가 로컬 터미널에서 직접 실행하면 일반적으로 sandbox 권한 문제가 발생하지 않는다.
- Codex가 실행기를 대신 실행하는 경우에는 내부 `git checkout/add/commit`까지 같은 프로세스 권한을 사용하므로, 아래 명령 자체를 권한 상승으로 실행해야 한다.
- 반복 승인은 Codex permission UI에서 `prefix_rule=["python3", ".codex/skills/dev-start/scripts/execute.py"]`를 저장해 처리한다. 이 rule은 실행기 호출만 자동 승인하고, 일반 `git commit`이나 다른 `python3` 명령까지 허용하지 않는다.

```bash
python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name>
```

- 개별 `git add` 또는 `git commit` prefix만 승인해도 `execute.py` 내부 Git subprocess 권한이 해결되는 것은 아니다.
- preflight가 실패하면 Git 작업으로 들어가기 전에 중단하고, `execute.py` 명령 자체를 권한 상승으로 다시 실행한다.

## 실행 산출물

각 step은 실행기로 완료되어야 하며, 수동으로 `status = completed`만 기록하면 안 된다.

- `stepN-output.json`: writer worker 실행 결과
- `stepN-ac-output.json`: Acceptance Criteria 재실행 결과. Acceptance Criteria가 있는 step에서 필수다.
- `stepN-review-output.json`: reviewer worker 검토 결과

실행 시작 시 이미 `completed`인 step은 위 산출물을 검사한다. 산출물이 누락되면 실행기는 중단하며, 해당 step의 `status`를 `pending`으로 되돌리고 `completed_at`, `summary`를 정리한 뒤 다시 실행해야 한다.
