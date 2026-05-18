# Step 3: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 현재 내용과 업데이트 범위를 파악하라:

- `/docs/tasks/db-constraint-violation-handling/architecture.md` (정책 내용 원본)
- `/docs/architecture.md` (L137 부근 "예외 처리 전략" 섹션 현재 상태)

## 작업

### `docs/architecture.md` "예외 처리 전략" 섹션 수정/확장

현재 `docs/architecture.md:137`의 "예외 처리 전략" 섹션은 아래와 같이 짧게 기술되어 있다:

```
## 예외 처리 전략

- `DataIntegrityViolationException` 같은 인프라 예외는 Service에서 도메인 예외로 변환한다
(다이어그램)
```

이 섹션을 `docs/tasks/db-constraint-violation-handling/architecture.md`의 정책 내용으로 수정/확장한다.

포함할 내용:
1. **3계층 책임 분리 표** (Unique / NOT NULL·FK·CHECK 위반별 처리)
2. **Unique 위반의 두 종류** (비즈니스 unique / 기술적 unique)
3. **두 처리 모드** (A: 도메인 예외 변환 / B: 멱등 흡수)
4. **Unique 종류를 코드에서 분리하는 방법 3케이스**
   - 케이스 1: unique 하나 — 분기 불필요
   - 케이스 2: unique 여러 개지만 의미 통일 — 분기 불필요
   - 케이스 3: unique 여러 개고 의미 다름 — fallback 재조회 결과로 분리
5. **GlobalExceptionHandler 안전망 의미와 도달 조건**
6. **"Application은 `DuplicateKeyException`만 좁게 catch한다"** 규칙 명문화

기존 한 줄 다이어그램은 위 내용으로 대체하거나 확장한다.

현재 섹션에서 "`DataIntegrityViolationException` 같은 인프라 예외는 Service에서 도메인 예외로 변환한다"는 표현은 더 구체적으로 갱신한다: **"`DuplicateKeyException`(unique 위반)만 catch하여 도메인 의미에 맞게 처리하고, 나머지 무결성 위반은 `GlobalExceptionHandler` 안전망에 위임한다."**

## Acceptance Criteria

```bash
# docs/architecture.md 파일이 수정됐는지 확인
grep -n "DuplicateKeyException" docs/architecture.md
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/architecture.md`의 예외 처리 전략 섹션에 3계층 책임 분리 표가 있는지 확인한다.
   - `DuplicateKeyException`이 명시적으로 언급되는지 확인한다.
   - 기존 `DataIntegrityViolationException` 표현이 정확하게 갱신됐는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 커밋 단위

1. `docs: 예외 처리 계층 책임 정책을 architecture.md에 정리한다`

## 금지사항

- `docs/tasks/` 하위의 과거 task 문서(payment-attempt-idempotency, order-idempotency 등)는 수정하지 마라. 이유: 해당 문서는 그 시점의 결정 기록이며 소급 수정하지 않는다.
- `CLAUDE.md`의 한 줄 규칙은 수정하지 마라. 이유: `docs/architecture.md`가 풀어서 설명하므로 `CLAUDE.md`는 그대로 유지한다.
- 기존 테스트를 깨뜨리지 마라.
