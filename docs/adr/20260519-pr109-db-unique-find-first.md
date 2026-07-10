# DB unique 위반은 안전망 500으로 위임하고 정상 흐름은 사전 `find`로 처리한다 (find-first 패턴 통일)

- Status: accepted
- Date: 2026-05-19

## Context

- **배경**: PR #106 (`docs/tasks/db-constraint-violation-handling/`) 에서 5곳을 `DuplicateKeyException` 좁은 catch 로 정리했으나 회고에서 "Application 이 인프라 예외 타입에 직접 의존한다" 는 부채가 분리되었다 (Issue #105). 후속 처리 옵션으로 (A) catch 를 Adapter 로 이동, (B) 5곳 모두 find-first 통일, (C) `Exception.class` fallback stack trace 보강만 검토했다. 옵션 A 는 5곳 처리 동작(멱등 흡수 / 도메인 예외 변환 / silent skip) 이 모두 달라 공통 변환 레이어가 의미 없고 도메인 매핑 지식이 Adapter 로 새는 문제가 있었다.
- **결정 근거**: 5곳의 unique 키는 모두 사용자 입력 식별자(email, merchantPayKey) 또는 idempotency key 기반이라 정상 흐름에서 동시 충돌 확률이 매우 낮다. 트랜잭션도 짧아 race window 가 좁다. find-first 패턴은 "트랜잭션 짧음 + 충돌 확률 낮음" 두 조건이 만족될 때 race window 비용이 안전망 500 처리로 충분히 흡수된다. 본 5곳은 이 조건을 만족한다. 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) 에는 본 정책을 적용하지 않고 try-save-catch 패턴이 더 적합하며, 향후 새 unique 제약 도입 시 위 두 조건으로 패턴을 선택한다. `DataAccessException` 부모 핸들러 추가는 운영 모니터링에서 DAO 카테고리 예외를 일반 `Exception` fallback 과 구분 가능하게 한다.

## Decision

Application 계층 6곳(`MemberRegistrationService`, `PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `OrderCreateService`, `StockRestoreOutboxConsumeService`) 모두 `DB find → 없으면 insert → 충돌 시 500` 본질 흐름으로 통일한다. Application 과 Adapter 어디서도 `DuplicateKeyException` 을 catch 하지 않는다. `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러(`COMMON-500-2`) 를 추가해 DAO 카테고리 fallback 을 stack trace 와 함께 500 으로 처리한다. 주문 생성에 멱등 키를 적용한 기존 결정(→ PR#59)의 `(member_id, idempotency_key)` unique 위반 fallback 재조회 로직은 본 정책으로 대체되어, 정당한 멱등 재요청은 Redis reserve 성공 후 DB find 사전 체크로 흡수하고 race window 충돌은 안전망 500 으로 위임한다.

## Consequences

- **결과**: PR #106 정책(`DuplicateKeyException` 좁은 catch + 5곳 도메인 매핑) 은 폐기된다. 행위 변경은 race window 한정이다 — Member 가입 race 와 PaymentApproval race 는 4xx → 500, PaymentAttempt 2곳 race 는 200(멱등 흡수) → 500, StockRestoreOutbox race 는 200(silent skip) → 500, OrderCreate 는 `order-idempotency-cache-simplification`(→ PR#180) 에서 race window 응답이 500 → 409 `IN_PROGRESS` 로 변경됨 (Redis fallback 후 도달하는 진짜 race 는 여전히 안전망 500). 정상 멱등/중복 흐름은 모두 사전 `find` 분기로 보존된다. Application 이 `org.springframework.dao.*` 패키지에 의존하지 않게 되어 계층 의존 방향 부채가 함께 해소된다. 상세 옵션 비교와 5곳 매핑은 `docs/tasks/unique-find-first-policy/adr.md` 와 `docs/architecture.md` 의 예외 처리 섹션을 참조한다.
- **트레이드오프**: race 발생률이 매우 낮다는 전제 위에 정책이 성립한다. 만약 향후 어느 곳에서 race 가 잦아지면 본 ADR 의 "적용 조건" 이 깨지고 try-save-catch 로의 전환을 재검토해야 한다. `Exception.class` fallback 의 stack trace 로깅 누락은 본 ADR 에서 다루지 않는다 (DAO 카테고리는 부모 핸들러로 해결됐지만 NPE 등 일반 예외는 여전히 message-only 로깅, 별도 개선 과제).
