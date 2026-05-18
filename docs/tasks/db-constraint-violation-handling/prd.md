# 태스크 PRD

## 태스크명

- `db-constraint-violation-handling`

## 배경

application 계층 5곳에서 `DataIntegrityViolationException`을 catch해 멱등/중복 처리 fallback을 수행하고 있다.
이 예외는 unique 위반뿐 아니라 NOT NULL / FK / CHECK 위반까지 포함하는 부모 타입이라 catch 범위가 의도보다 넓다.
NOT NULL/FK 위반(= 코드 버그)이 의도치 않은 fallback 경로(예: `DUPLICATE_EMAIL` 응답)를 탈 잠재 위험이 있다.

추가로 `GlobalExceptionHandler`의 `DataIntegrityViolationException` 핸들러가 409 CONFLICT로 응답한다.
안전망(서버 버그 가시화) 의미에 맞지 않아 코드 버그가 4xx로 포장된다.

Issue #104 — Issue #100 확장 (PaymentAttempt 한 곳 → application 계층 5곳 일괄 처리).

## 목표

- Application 계층에서 unique 위반만 좁게 catch하도록 수정한다.
- NOT NULL / FK / CHECK 위반은 안전망까지 전파되어 500으로 가시화되도록 한다.
- GlobalExceptionHandler의 안전망 의미를 500 응답으로 명확히 재정의한다.
- 정책을 문서화하고 회귀 방어 테스트로 정착시킨다.

## 범위

### 포함

- Application 계층 5곳 catch 타입 교체 (`DataIntegrityViolationException` → `DuplicateKeyException`)
- `OrderCreateService` fallback 실패 시 원래 예외 rethrow
- `GlobalExceptionHandler` + `CommonErrorCode` 수정
- 단위 테스트 5개 파일 mock 타입 교체
- Repository 슬라이스 테스트 어서션 `DuplicateKeyException`으로 좁히기 (2개 파일)
- Testcontainers 회귀 방어 통합 테스트 추가
- `docs/architecture.md` "예외 처리 전략" 섹션 수정/확장

### 제외

- Issue #99 (PaymentAttempt 상태 전이 검증 정책) — 독립 논의 필요, 별도 이슈
- 과거 task 문서 수정 — 시점 기록이므로 소급 수정 안 함
- `CLAUDE.md` 수정 — 한 줄 규칙은 그대로 유지

## 주요 시나리오

1. **unique 위반 (정상 시나리오)**: 동시 요청으로 unique 충돌 → `DuplicateKeyException` catch → 도메인 의미에 맞게 처리 (멱등 흡수 or 도메인 예외 변환)
2. **NOT NULL 위반 (코드 버그)**: 사전 검증 누락으로 NOT NULL 위반 → application에서 catch 안 함 → `GlobalExceptionHandler` 안전망 → 500 + 알람

## 요구사항

1. Application 5곳의 catch 타입을 `DuplicateKeyException`으로 교체한다.
2. `OrderCreateService`의 fallback 재조회 실패 시 `ORDER_NOT_FOUND` throw를 rethrow로 교체한다.
3. `DATA_INTEGRITY_VIOLATION` 응답 상태를 500으로 변경한다.
4. `GlobalExceptionHandler`에 안전망 의미 주석을 추가하고 stack trace 로그를 보강한다.
5. `./gradlew test`가 통과해야 한다.
6. `./gradlew dockerTest`가 통과해야 한다.

## 제약사항

- `DuplicateKeyException`은 DB 종류/드라이버에 따라 매핑이 달라질 수 있다. Testcontainers로 실제 MySQL 환경에서 검증한다.
- 기존 단위 테스트의 fallback 동작은 그대로 유지한다. mock 타입만 교체한다.
- `OptimisticLockingFailureException` 핸들러는 변경하지 않는다 (낙관적 락 충돌은 정상 시나리오, 409 유지).
