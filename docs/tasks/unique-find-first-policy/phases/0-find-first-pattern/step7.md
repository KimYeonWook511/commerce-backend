# Step 8: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/docs/tasks/unique-find-first-policy/api-spec.md`
- `/docs/tasks/unique-find-first-policy/db-schema.md`
- `/docs/architecture.md` (라인 140-181 정책 섹션 — 갱신 대상)
- `/docs/ADR.md` (새 ADR 항목 추가)
- `/CLAUDE.md` (commerce-backend 루트 — 규칙 문구 갱신)
- `/docs/tasks/db-constraint-violation-handling/prd.md`
- `/docs/tasks/db-constraint-violation-handling/architecture.md`
- `/docs/tasks/db-constraint-violation-handling/adr.md`
- `/docs/tasks/db-constraint-violation-handling/api-spec.md`

step 5 가 끝나 있어야 한다.

## 작업

루트 docs 와 이전 태스크 폴더 anchor 를 일괄 갱신한다.

### 1. `docs/architecture.md` 정책 섹션 갱신 (라인 140-181)

기존 섹션을 폐기하고 새 정책으로 교체:

- 기존 "3계층 책임 분리" 표 폐기
- 기존 "Unique 위반의 두 종류" 표 폐기
- 기존 "Unique 처리 모드 (5곳 분류)" 표 폐기
- 기존 "DuplicateKeyException 전용 핸들러는 신설하지 않음" 항목 갱신

새 내용:

- 본질 흐름: `DB find → 없으면 insert → 충돌 시 500`
- **정책 적용 조건과 한계** (분리 섹션):
  - 적용 조건: ① 트랜잭션이 짧다, ② 정상 흐름에서 동시 충돌 확률이 낮다 (사용자 입력 식별자/idempotency key 등)
  - 비적용 상황: 충돌이 잦은 시나리오는 try-save-catch 패턴이 더 적합. 향후 새 unique 제약 도입 시 이 기준으로 패턴 선택
- `DataAccessException` 부모 핸들러 도입 사실 + `COMMON-500-2` ErrorCode
- `JpaConfig` 빈 등록 목적을 "안전망에서 정확한 분류/로깅" 으로 재기술

### 2. `docs/ADR.md` 새 항목 추가

다음 형식으로 추가한다:

- 제목: "ADR-N: DB unique 위반은 안전망 500 으로 위임하고 정상 흐름은 사전 `find` 로 처리한다"
- 배경: PR #106 catch 정책의 인프라 예외 의존 부채를 해소하기 위한 정책 재정의
- 결정: 5곳 모두 find-first 패턴, race 는 안전망 500, `DataAccessException` 부모 핸들러 추가
- **결정 근거** (핵심): 5곳의 unique 는 사용자 입력/idempotency key 기반으로 충돌 확률 낮음. 트랜잭션 짧음 + 충돌 확률 낮음 조건 만족. 충돌 잦은 시나리오는 try-save-catch 더 적합 (향후 새 unique 제약 도입 시 선택 기준)
- 결과: PR #106 정책 폐기. 행위 변경은 race window 한정 (5곳 매핑은 PR 본문 참조)

상세본은 `docs/tasks/unique-find-first-policy/adr.md` 에서 옵션 A/B/C 비교와 함께 기록되어 있으므로 루트 ADR 은 결정과 근거만 명료하게 적는다.

기존 라인 47 의 `DUPLICATE_EMAIL` 단순 언급(Redis 후속 처리 trade-off 맥락) 은 그대로 유지한다.

### 3. `commerce-backend/CLAUDE.md` 갱신

`구현 규칙` 섹션의 다음 문구를 갱신한다:

- 기존: "Infrastructure 예외(`DataIntegrityViolationException` 등)는 Application 계층에서 도메인 예외로 변환하고 Presentation으로 넘기지 않습니다."
- 새 문구: "정상 흐름은 사전 `find` 로 처리하고, DB 무결성 위반(unique 포함) 은 catch 하지 않고 안전망 500 으로 위임합니다. 충돌이 잦은 시나리오에서만 try-save-catch 패턴을 사용하며, 이때도 인프라 예외 타입에 직접 의존하지 않도록 처리한다."
- 한 줄로 무리하게 줄이지 말고, 정책 적용 조건이 드러나도록 풀어 쓴다.

### 4. 이전 태스크 폴더(`docs/tasks/db-constraint-violation-handling/`) 폐기 anchor 추가

다음 4 개 문서 **상단**에 anchor 한 줄을 추가한다. 본문은 역사 기록으로 그대로 유지한다.

대상 파일:
- `docs/tasks/db-constraint-violation-handling/prd.md`
- `docs/tasks/db-constraint-violation-handling/adr.md`
- `docs/tasks/db-constraint-violation-handling/api-spec.md`
- `docs/tasks/db-constraint-violation-handling/architecture.md`

anchor 예시:

```markdown
> [!NOTE]
> 본 문서의 정책은 후속 태스크 `docs/tasks/unique-find-first-policy/` 에서 재정의되었다. 현재 정책은 루트 `docs/architecture.md` 의 예외 처리 섹션과 `docs/tasks/unique-find-first-policy/adr.md` 를 참조한다.
```

anchor 는 문서 제목 바로 아래(첫 헤딩 이후 첫 줄) 에 둔다.

**금지**: `retrospective.md` 와 `phases/**` 는 수정하지 않는다 (immutable 정책).

### 5. PR #106 회고록(`docs/tasks/db-constraint-violation-handling/retrospective.md`) 은 수정 금지

회고 문서 immutable 정책에 따라 손대지 않는다. 본 태스크의 retrospective 는 step 8 에서 별도로 작성한다.

## Acceptance Criteria

```bash
./gradlew test
```

(본 step 은 docs 변경 위주라 코드 빌드/테스트가 영향받지 않지만, 회귀 방어로 기본 테스트는 통과해야 한다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/architecture.md` 라인 140-181 정책 섹션이 새 흐름과 적용 조건 섹션으로 교체되었는가?
   - `docs/ADR.md` 에 새 ADR 항목이 추가되었고 결정 근거(충돌 확률 낮음) 가 명시되었는가?
   - `commerce-backend/CLAUDE.md` 구현 규칙 문구가 갱신되었는가?
   - 이전 태스크 폴더 4 개 문서에 폐기 anchor 가 한 줄씩 추가되었고 본문은 그대로 유지되었는가?
   - `retrospective.md` 와 `phases/**` 는 수정되지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/PRD.md`, `docs/api-spec.md`, `docs/db-schema.md` 를 수정하지 마라. 이유: 본 태스크는 PRD 수준 기능 범위 변경 / 외부 API 명세 변경 / DB 스키마 변경이 없다.
- `commerce-workspace/docs/` 의 어떤 문서도 수정하지 마라. 이유: 본 세션은 backend 서브모듈 컨텍스트이며, 워크스페이스 문서는 Frontend 세션의 "계약 싱크" 책임.
- 이전 태스크 폴더의 `retrospective.md`, `phases/**` 를 수정하지 마라. 이유: 회고/실행 기록 immutable 정책.
- 이전 태스크 폴더의 본문을 수정하거나 삭제하지 마라. 이유: 역사 기록 보존. 정책 폐기 사실은 anchor 한 줄로만 알리며 본문은 그대로 둔다.
- ADR 라인 47 의 `DUPLICATE_EMAIL` 단순 언급을 건드리지 마라. 이유: Redis 후속 처리 trade-off 맥락이라 새 정책과 호환된다.
- 기존 테스트를 깨뜨리지 마라.
