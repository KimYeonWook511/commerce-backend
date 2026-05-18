# 태스크 ADR

## 결정 제목

- ADR-N: DB unique 위반은 안전망 500 으로 위임하고 정상 흐름은 사전 `find` 로 처리한다 (find-first 패턴 통일)

## 배경

PR #106 (db-constraint-violation-handling) 에서 Application 계층 5곳이 `DuplicateKeyException` 을 좁게 catch 하도록 정리했다. 회고록은 "Application 이 인프라 예외 타입에 직접 의존한다"는 부채를 Issue #105 로 분리했다.

후속 처리에 세 가지 옵션을 검토했다.

- **옵션 A** — catch 를 Adapter 로 이동: 5곳의 처리 동작이 다르고(멱등 재조회/도메인 예외 변환/silent skip), 도메인 매핑 지식이 Adapter 로 새는 문제가 발생.
- **옵션 B** — 5곳 모두 `DB find → insert → 500` 본질 흐름으로 통일: OrderCreate 만 Redis 캐시 위에 DB find 를 추가로 두어 TTL 만료 후 정당 재요청을 흡수. 그 외 5곳은 catch 자체를 제거.
- **옵션 C** — `Exception.class` fallback 의 stack trace 로깅만 보강하고 DAO 부모 핸들러는 추가하지 않음: 별도 ErrorCode 분류 가치 포기.

추가로 `GlobalExceptionHandler` 안전망 보강 범위도 논의했다. `DataAccessException` 부모 핸들러를 추가해 DAO 예외 카테고리(`COMMON-500-2`) 를 운영 모니터링에서 일반 fallback(`COMMON-500`) 과 구분할지 결정해야 했다.

## 결정 내용

- 5곳 모두 본질 흐름 `DB find → 없으면 insert → 충돌 시 500` 으로 통일한다 (**옵션 B**).
- `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러를 추가한다 (안전망 보강 옵션 A 채택).
- Application/Adapter 어디서도 `DuplicateKeyException` 을 catch 하지 않는다.
- `OrderCreate` 만 Redis `orderIdempotencyStore` 위에 DB `findByMemberIdAndIdempotencyKey` 사전 체크를 추가해 TTL 만료 후 정당 멱등 재요청을 흡수한다. Redis 는 본질 흐름 앞단의 캐시 레이어로 위치시킨다.
- `JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈은 유지한다 (안전망에서 unique 위반을 `DuplicateKeyException` 으로 정확히 분류해 로깅하기 위함).

## 근거

### 핵심 근거 — 충돌 확률이 낮다

본 태스크 5곳의 unique 는 모두 사용자 입력 식별자 또는 idempotency key 기반이다.

| 위치 | unique 키 |
|---|---|
| MemberRegistration | `email` |
| PaymentApproval | `merchantPayKey` |
| PaymentAttempt × 2 | `(merchantPayKey, type, paymentId)` (멱등 키) |
| OrderCreate | `(member_id, idempotency_key)` (멱등 키) + `orderNumber` (ULID, 시스템 생성) |
| StockRestoreOutbox | `(eventId, consumerType)` (멱등 키) |

위 키들은 **정상 흐름에서 동시 충돌 확률이 매우 낮다**. find-first 패턴은 트랜잭션이 짧고 충돌 확률이 낮은 경우에만 race window 비용이 안전망 500 처리로 충분히 흡수된다. 본 5곳은 이 조건을 만족한다.

향후 새 unique 제약 도입 시 패턴 선택 기준:

- **트랜잭션 짧음 + 충돌 확률 낮음** → find-first (본 정책)
- **충돌 잦음** (예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) → try-save-catch 패턴이 더 적합

### 옵션 B 선택 이유

- 옵션 A 의 Adapter 매핑은 5곳 처리 동작이 다르고 도메인 매핑 지식이 Adapter 로 새는 문제가 있다. 회고록 §2 에서 명시.
- 옵션 B 는 Application 과 Adapter 양쪽 모두에서 인프라 예외 의존을 제거한다. 계층 의존 방향 부채가 함께 해소된다.
- 5곳의 처리 동작 차이는 정책상 흡수된다. 정상 흐름은 모두 사전 `find` 가 담당하고, race 는 모두 안전망 500 으로 통합된다. 분기 자체가 필요 없다.
- `OrderCreate` 의 두 unique 제약(`(member_id, idempotency_key)` 비즈니스 키 + `orderNumber` ULID 기술적 키) 분기도 필요 없다. 멱등키 정상 흐름은 DB find 가 흡수하고, 멱등키 race 와 ULID 충돌은 모두 안전망 500 으로 처리된다.

### OrderCreate 에 DB find 를 추가하는 이유

기존 코드는 `DuplicateKeyException` catch 후 `findByMemberIdAndIdempotencyKey` 로 재조회해 멱등 흡수했다. 새 정책에서 catch 를 제거하면 TTL 만료 후 정당한 재요청도 500 으로 응답하게 된다.

이걸 보존하려면 Redis reserve 성공 후 DB find 사전 체크를 추가해야 한다. 다른 4곳보다 한 단계 더 보수적이지만, **본질 흐름은 동일**하다 (`DB find → insert → 500`). Redis 는 latency 최적화를 위한 캐시 레이어로 그 위에 얹힌 것일 뿐이다.

### DataAccessException 부모 핸들러를 추가하는 이유

`SQLErrorCodeSQLExceptionTranslator` 는 SQL 에러 코드 기반으로 다양한 `DataAccessException` 하위 타입으로 변환한다(`DataIntegrityViolationException`, `BadSqlGrammarException`, `CannotAcquireLockException` 등).

현재 핸들러 구성:

- `DataIntegrityViolationException` → 500, stack trace (`COMMON-500-1`)
- `OptimisticLockingFailureException` → 409
- `Exception.class` fallback → 500, **message만** (no stack trace), `COMMON-500`

그 외 DAO 예외(`BadSqlGrammar`, `CannotAcquireLock`, `DataAccessResourceFailure` 등) 는 `Exception` fallback 으로 떨어져 stack trace 없이 처리됐다. DAO 예외는 DB 관련 시스템 문제로 운영 대응이 즉시 필요한데, stack trace 없으면 디버깅 어렵다.

`DataAccessException` 부모 핸들러는 그 외 DAO 예외만 받아 stack trace + 500 + `COMMON-500-2` 로 처리한다. 운영 모니터링에서 DAO 카테고리를 일반 fallback 과 구분 가능해진다. Spring 다형성 매칭으로 기존 두 구체 핸들러는 우선 매칭되어 그대로 동작한다.

옵션 C(Exception fallback 자체 stack trace 보강)도 검토했으나, 별도 ErrorCode 분류 가치(운영 모니터링 카테고리 분리)를 위해 옵션 A 채택.

### JpaConfig 빈 유지 이유

본 정책에서 코드는 `DuplicateKeyException` 을 catch 하지 않지만, 안전망 핸들러가 정확한 분류와 로깅을 위해 unique 위반이 `DuplicateKeyException` 으로 변환되는 것은 여전히 가치 있다. JPA + MySQL 환경에서 빈 없이는 unique 위반이 `DuplicateKeyException` 으로 변환되지 않아 안전망 로깅 정확도가 떨어진다. PR #106 에서 추가한 빈을 그대로 유지한다.

## 결과

### 기대 효과

- Application 이 `org.springframework.dao.*` 패키지에 의존하지 않게 된다. 계층 의존 방향 부채 해소.
- 5곳의 unique 처리 정책이 단일 본질 흐름(`DB find → insert → 500`) 으로 통일된다.
- 운영 모니터링에서 DAO 카테고리 예외를 일반 fallback 과 구분 가능 (`COMMON-500-2`).
- 향후 새 unique 제약 도입 시 본 ADR 의 "적용 조건"으로 패턴 선택 가능.

### Trade-off

- **행위 변경** (race window 한정):
  - Member: 4xx → 500
  - PaymentApproval: 4xx → 500
  - PaymentAttempt × 2: 200(멱등 흡수) → 500
  - StockRestoreOutbox: 200(silent skip) → 500
  - OrderCreate: 행위 변경 없음 (DB find 사전 체크로 TTL 만료 후 정당 재요청 보존)
- race 발생률이 매우 낮다는 전제 위에 정책이 성립한다. 만약 향후 5곳 중 어느 곳에서 race 가 잦아진다면 본 ADR 의 "적용 조건"이 깨지고 try-save-catch 로의 전환을 재검토해야 한다.
- `Exception.class` fallback 의 stack trace 로깅 누락은 본 태스크에서 다루지 않는다. DAO 한정으로는 부모 핸들러로 해결됐지만 NPE 등 일반 예외는 여전히 message-only 로깅. 별도 개선 과제로 남김.
