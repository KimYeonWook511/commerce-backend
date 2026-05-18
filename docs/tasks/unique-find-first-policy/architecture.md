# 태스크 아키텍처

> [!NOTE]
> 본 문서는 PR #106 (`docs/tasks/db-constraint-violation-handling/`) 의 정책을 재정의한다. 이전 정책 표(3계층 책임 분리 / Unique 처리 모드 5곳 분류)는 본 태스크에서 폐기된다. 루트 정책은 `docs/architecture.md` 의 예외 처리 섹션을 참조한다.

## 개요

본 태스크는 unique 위반 처리 정책을 단일 본질 흐름으로 통일한다.

```
DB find → 없으면 DB insert → 충돌 시 500
```

- 사전 `find` 가 정상 멱등/중복 시나리오를 흡수한다 (200 흐름).
- `insert` 시점의 unique 위반은 race window 뿐이며, 코드 안전망 500 으로 가시화한다.
- Application 과 Adapter 어디서도 Spring DAO 예외를 catch 하지 않는다.

## 변경 대상

### Application 계층 (5곳)

- `member/application/MemberRegistrationService.java`
- `payment/application/PaymentApprovalService.java`
- `payment/application/PaymentAttemptService.java` — `getOrCreateApproveAttempt`, `getOrCreateCancelAttempt`
- `order/application/OrderCreateService.java` — `attemptCreateOrder`
- `outbox/stock/application/StockRestoreOutboxConsumeService.java` — `markProcessed`

### Outbox Repository (existsBy 메서드)

- `outbox/domain/repository/ProcessedEventRepository.java` (도메인 인터페이스)
- `outbox/infrastructure/ProcessedEventRepositoryAdapter.java`
- `outbox/infrastructure/JpaProcessedEventRepository.java`

### Common 안전망

- `common/exception/GlobalExceptionHandler.java` — `DataAccessException` 부모 핸들러 추가
- `common/exception/CommonErrorCode.java` — `DATA_ACCESS_ERROR(COMMON-500-2)` 신설

## 설계 방향

### 본질 흐름 (5곳 모두 동일)

| 위치 | 사전 find | 정책 적용 |
|---|---|---|
| MemberRegistration | `existsByEmail` | catch 제거 |
| PaymentApproval | `findByMerchantPayKey` | catch 제거 |
| PaymentAttempt × 2 | `findApproveAttempt` / `findCancelAttempt` | try-save-catch → find-first 리팩토링 |
| OrderCreate | Redis `reserve` + DB `findByMemberIdAndIdempotencyKey` | catch 제거 + DB find 사전 체크 추가 |
| StockRestoreOutbox | `existsByEventIdAndConsumerType` | try-save-catch → find-first 리팩토링 |

OrderCreate 의 Redis `orderIdempotencyStore` 는 본질 흐름 앞단에 얹힌 **캐시 레이어**다. Redis hit 시 DB find 를 생략하고, miss/만료 시 동일하게 DB find 로 fallback 한다. 본질 흐름 자체를 바꾸지 않는다.

### 정책 적용 조건과 한계

본 정책은 다음 조건이 모두 만족될 때 유효하다.

1. **트랜잭션이 짧다** — race window 가 좁아 안전망 500 의 발생률이 무시 가능한 수준.
2. **정상 흐름에서 동시 충돌 확률이 낮다** — 사용자 입력 식별자(email)나 idempotency key 기반 unique.

본 태스크 5곳은 모두 위 조건을 만족한다. 향후 새 unique 제약 도입 시:

- 위 조건이 만족되면 본 정책(find-first)을 적용한다.
- 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race)에는 본 정책을 적용하지 않고 **try-save-catch** 패턴이 더 적합하다. 이때 catch 위치(Application / Adapter)와 처리 동작은 별도 ADR 로 결정한다.

### GlobalExceptionHandler 안전망 계층

```
DataAccessException (신규 부모 핸들러, COMMON-500-2)
├─ DataIntegrityViolationException (기존, COMMON-500-1)     ← unique/NOT NULL/FK/CHECK
│  └─ DuplicateKeyException                                  ← 자동 흡수
└─ OptimisticLockingFailureException (기존, COMMON-409-1)    ← 409
```

- Spring `@ExceptionHandler` 는 가장 구체적인 타입을 먼저 매칭한다. 기존 두 구체 핸들러는 그대로 우선 매칭되고, `DataAccessException` 부모 핸들러는 그 외 DAO 예외(BadSqlGrammar, CannotAcquireLock, DataAccessResourceFailure 등) 만 받는다.
- `DuplicateKeyException` 은 `DataIntegrityViolationException` 의 하위라 별도 등록 없이 부모 핸들러가 자동 흡수한다.

### Application 의 인프라 예외 의존 제거

- 5곳 모두에서 `import org.springframework.dao.DuplicateKeyException;` 제거.
- Application 은 `org.springframework.dao.*` 패키지에 의존하지 않는다.
- Adapter 도 본 정책 적용 범위에서는 추가 catch 를 두지 않는다 (단순 위임).

## 데이터 흐름

### Member 가입 (정상 / race)

```
정상: req → existsByEmail(false) → save → 200
중복: req → existsByEmail(true) → 4xx DUPLICATE_EMAIL
race: req1 & req2 동시 → 둘 다 existsByEmail(false) → 한쪽 save 성공, 다른 쪽 unique 위반
      → DataIntegrityViolationException 안전망 500 (COMMON-500-1, stack trace)
```

### PaymentAttempt (find-first)

```
정상 멱등: req → findApproveAttempt(present) → amount 검증 → 기존 attempt 반환 (200)
정상 신규: req → findApproveAttempt(empty) → save → 200
race:      req1 & req2 → 둘 다 findApproveAttempt(empty) → 한쪽 save, 다른 쪽 unique 위반
           → 안전망 500
amount mismatch: findApproveAttempt(present) but amount 다름
                 → PaymentException(PAYMENT_ATTEMPT_AMOUNT_MISMATCH) 4xx
```

### OrderCreate (Redis + DB find)

```
정상 멱등 (Redis hit): req → reserve(false) → getCompletedOrderId → 기존 order 반환 (200)
정상 신규: req → reserve(true) → DB find(empty) → processor.execute → 200
TTL 만료 후 정당 재요청: req → reserve(true) → DB find(present)
                        → Redis complete 갱신 후 반환 (200) [기존 행위 보존]
race: req → reserve(true) → DB find(empty) → processor.execute → save 시 unique 위반
      → RuntimeException catch → Redis 정리 → rethrow → 안전망 500
ULID 충돌: 같은 흐름 → 안전망 500 (확률 1조 1)
```

### StockRestoreOutbox (find-first)

```
정상 신규: msg → existsBy(false) → save → restoreStock → 정상 처리
중복 이벤트: msg → existsBy(true) → skip 로깅 → return false → consumer 정상 처리
race: msg1 & msg2 동시 → 둘 다 existsBy(false) → 한쪽 save, 다른 쪽 unique 위반
      → 안전망 500 → consumer 재시도로 복구 가능
```

## 예외 및 실패 처리

| 발생 위치 | 예외 타입 | 처리 |
|---|---|---|
| 사전 find 분기 | 도메인 예외 (e.g. `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`, `DUPLICATE_EMAIL`) | 도메인 4xx |
| insert race window | `DuplicateKeyException` / `DataIntegrityViolationException` | 안전망 `COMMON-500-1` + stack trace |
| OrderCreate race | `RuntimeException` catch → Redis 정리 → rethrow | `RuntimeException` 그대로 → 안전망 |
| 다른 DAO 예외 (BadSqlGrammar, CannotAcquireLock 등) | `DataAccessException` (그 외 하위) | 신규 `COMMON-500-2` + stack trace |

## 테스트 포인트

- 5개 서비스 단위 테스트:
  - 사전 find 분기 케이스 (정상 멱등/중복 흡수)
  - `DuplicateKeyException` mock 케이스는 제거 또는 race 시나리오 테스트로 교체
  - `OrderCreate`: TTL 만료 후 DB find 흡수 케이스, race 시 rethrow 케이스
- `UniqueConstraintViolationIntegrationTest` (Testcontainers): unique race 시 안전망 500 도달 검증
- `NaverPayServiceIntegrationTest`: H2 환경에서 `paymentAttemptService` spy 스텁 제거 가능 여부 검증. find-first 패턴은 H2 에서도 정상 흐름이 통과되므로 spy 제거 가능성 높음.
- 안전망 핸들러:
  - `DataAccessException` 부모 핸들러로 BadSqlGrammar/CannotAcquireLock 등이 500 + stack trace + COMMON-500-2 로 처리되는지 검증
  - `DataIntegrityViolationException` 핸들러가 여전히 unique race 를 잡는지 회귀 검증
