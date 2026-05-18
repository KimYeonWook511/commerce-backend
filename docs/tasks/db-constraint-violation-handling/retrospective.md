# 회고록: db-constraint-violation-handling

## 1. 작업 요약

Application 계층 5곳에서 `DataIntegrityViolationException`(unique 외 위반 포함 부모 타입)을 catch하던 코드를 `DuplicateKeyException`(unique 위반만)으로 교체했다. `OrderCreateService`의 fallback 재조회 실패 시 `ORDER_NOT_FOUND` throw를 원래 예외 rethrow로 교체했다. `GlobalExceptionHandler`의 `DataIntegrityViolationException` 핸들러 응답 코드를 409에서 500으로 재정의하고 stack trace 로깅을 추가했다. 실제 MySQL 환경에서 unique 위반 시 `DuplicateKeyException`이 발생하는지를 Testcontainers 통합 테스트로 검증하는 회귀 방어 테스트를 추가했다. `docs/architecture.md`에 3계층 책임 분리 정책을 명문화했다.

목적: NOT NULL / FK / CHECK 위반(= 코드 버그)이 의도치 않은 fallback 경로(예: `DUPLICATE_EMAIL` 응답)를 타는 위험을 제거하고, 코드 버그는 안전망(500)으로 가시화되도록 한다.

---

## 2. 결정한 정책

### 3계층 책임 분리

| 위반 종류 | Spring 예외 타입 | Application 처리 | 최종 응답 |
|---|---|---|---|
| **Unique** | `DuplicateKeyException` | 좁게 catch → 도메인 의미에 맞게 처리 | 도메인 4xx 또는 정상 흐름 |
| **NOT NULL / FK / CHECK** | `DataIntegrityViolationException` (unique 제외) | **catch 안 함** → 그대로 전파 | 안전망 **500** + ERROR 로그 |

### Unique 위반의 두 종류

| 종류 | 예시 | 대응 |
|---|---|---|
| **비즈니스 unique** | email, idempotency_key, merchantPayKey, eventId | catch → 도메인 의미에 맞게 처리 |
| **기술적 unique** (시스템 생성 ID) | orderNumber(ULID) | catch 안 함 → 안전망 (충돌 = 코드 버그) |

### Unique 처리 모드 (5곳 분류)

| 위치 | 모드 | 처리 |
|---|---|---|
| `MemberRegistrationService` | A (도메인 예외 변환) | `MemberException(DUPLICATE_EMAIL)` throw |
| `PaymentApprovalService` | A (도메인 예외 변환) | `PaymentException(PAYMENT_DUPLICATE)` throw |
| `PaymentAttemptService` (2곳) | B (멱등 흡수) | 기존 attempt 재조회 후 반환 |
| `OrderCreateService` | B (멱등 흡수) | 멱등키로 재조회 후 반환. 실패 시 rethrow. |
| `StockRestoreOutboxConsumeService` | B (멱등 흡수) | `return false` (silent skip) |

### Adapter 변환 레이어 미도입 근거

5곳의 처리 동작이 다르고(멱등 재조회, 도메인 예외 변환, silent skip), 도메인 매핑 지식(`DuplicateKeyException` → `DUPLICATE_EMAIL`)이 Adapter로 새어드는 문제를 피하기 위해 Application에서 직접 catch하는 방식을 채택했다. Spring의 `DuplicateKeyException`이 이미 vendor 중립적 unique 위반 추상을 제공하므로 한 번 더 감싸는 것은 YAGNI다.

---

## 3. 주요 발견 및 논의

### `Order` 엔티티의 unique 제약이 두 개 (비즈니스 + 기술적)

`tbl_order`에는 `(member_id, idempotency_key)` (비즈니스 키)와 `orderNumber` (시스템 생성 ULID) 두 개의 unique 제약이 있다. `DuplicateKeyException`만으로는 어느 제약이 터졌는지 구분할 수 없다. 이를 fallback 재조회 결과로 분기했다:

- 비즈니스 키로 재조회 성공 → 멱등 흡수 (같은 idempotency_key 중복 요청)
- 비즈니스 키로 재조회 실패 → `orderNumber` ULID 충돌 또는 race condition 데이터 소멸 = 코드 버그 → rethrow → 안전망 500

기존 코드는 재조회 실패 시 `ORDER_NOT_FOUND`를 throw했는데, 이는 ULID 충돌(코드 버그)을 404로 포장하는 문제가 있었다. rethrow로 교체함으로써 안전망에서 500으로 처리되어 가시화된다.

### `DataIntegrityViolationException`과 `DuplicateKeyException`의 계층 관계

`DuplicateKeyException`은 `DataIntegrityViolationException`의 하위 타입이다. 따라서 기존 `DataIntegrityViolationException` catch는 `DuplicateKeyException`도 포함했고, 반대로 새 `DuplicateKeyException` catch는 NOT NULL / FK / CHECK 위반은 잡지 않는다. Catch 타입 교체 한 줄로 의도한 범위 좁히기가 가능했다.

### H2+JPA 환경에서 `DuplicateKeyException` 미발생 문제

H2 인메모리 DB를 사용하는 슬라이스 테스트 환경(`@DataJpaTest`)에서는 unique 위반 시 `DataIntegrityViolationException`이 발생하고 `DuplicateKeyException`이 발생하지 않는다. Spring의 `HibernateJpaDialect`가 `jdbcExceptionTranslator`를 통해 변환하는데, H2는 MySQL과 SQL Error Code 매핑이 달라 `DuplicateKeyException`으로 변환되지 않는다. 이로 인해:

1. 슬라이스 테스트 어서션을 `DuplicateKeyException`으로 교체하지 못하고 보류했다.
2. `NaverPayServiceIntegrationTest`의 H2+JPA 환경에서도 동일 문제가 발생하여 `paymentAttemptService` spy 스텁으로 우회했다.
3. 실제 MySQL 환경 검증을 위해 Testcontainers 통합 테스트를 별도로 추가했다.

### `JpaConfig`의 `SQLErrorCodeSQLExceptionTranslator` 빈 등록

Testcontainers 통합 테스트에서 `@DataJpaTest` + MySQL 조합으로도 `DuplicateKeyException`이 발생하지 않는 문제가 있었다. `HibernateJpaDialect`의 `jdbcExceptionTranslator`가 기본적으로 Spring 컨텍스트의 `SQLErrorCodeSQLExceptionTranslator` 빈을 주입받아야 정상 변환이 동작하는데, 해당 빈이 등록되지 않아 변환이 누락됐다. `JpaConfig`에 `SQLErrorCodeSQLExceptionTranslator` 빈을 명시적으로 등록하고 `@Import(JpaConfig.class)`로 테스트에 포함시켜 해결했다. 이 빈이 없으면 운영 환경에서도 `DuplicateKeyException` 변환이 불완전할 수 있어 빈 등록이 회귀 방어 역할도 한다.

### `GlobalExceptionHandler`의 응답 코드 불일치 발견 (409 → 500)

`DataIntegrityViolationException` 핸들러가 409 CONFLICT를 반환하고 있었다. 이 핸들러는 정상 흐름에서 도달하지 않고 application catch 누락(= 코드 버그) 시에만 동작하는 안전망이다. 코드 버그가 4xx로 포장되면 모니터링과 알람에서 서버 버그로 분류되지 않는다. 500으로 재정의해 코드 버그 가시화와 알람 분류를 일치시켰다.

---

## 4. 변경 범위 정리

| 파일 | 변경 내용 |
|---|---|
| `PaymentAttemptService.java` | catch 타입 교체 (`DataIntegrityViolationException` → `DuplicateKeyException`) × 2 |
| `OrderCreateService.java` | catch 타입 교체 + fallback 재조회 실패 시 rethrow |
| `PaymentApprovalService.java` | catch 타입 교체 |
| `MemberRegistrationService.java` | catch 타입 교체 |
| `StockRestoreOutboxConsumeService.java` | catch 타입 교체 |
| `GlobalExceptionHandler.java` | 500 재정의 + 안전망 주석 + stack trace 로깅 |
| `CommonErrorCode.java` | `DATA_INTEGRITY_VIOLATION` 상태 코드 500, 코드 `COMMON-500-1` |
| `JpaConfig.java` | `SQLErrorCodeSQLExceptionTranslator` 빈 등록 |
| 단위 테스트 5개 파일 | mock 타입 교체 (`DataIntegrityViolationException` → `DuplicateKeyException`) |
| `OrderCreateServiceTest.java` | rethrow 동작 검증 테스트 추가 |
| `NaverPayServiceIntegrationTest.java` | H2+JPA 우회용 `paymentAttemptService` spy 스텁 추가 |
| `UniqueConstraintViolationIntegrationTest.java` | Testcontainers 회귀 방어 통합 테스트 신규 추가 |
| `docs/architecture.md` | 예외 처리 전략 섹션 확장 (3계층 분리, Unique 두 종류, 케이스 1-3 분기) |

---

## 5. 미결 과제

### Issue #99: `PaymentAttempt` 상태 전이 검증

`PaymentAttempt`의 상태(REQUESTED / FAILED / SUCCEEDED)에 따른 재요청 허용 여부 검증 로직은 이번 범위에서 제외됐다. 멱등 재요청 시 현재 구현은 amount 검사만 수행하며, 상태 전이 유효성은 별도 설계가 필요하다. 이번 catch 범위 좁히기와 독립적인 논의가 필요해 분리했다.

---

## 6. 회고

### 잘된 점

- 기능 문서(PRD, architecture, ADR)를 step 시작 전에 작성하고 구현에 들어갔다. 특히 5곳의 처리 모드(A/B)와 케이스 1-3 분기 전략을 architecture.md에 사전 정의해 두어 구현 중 설계 질문이 거의 없었다.
- H2+JPA 환경의 `DuplicateKeyException` 미발생 문제를 조기에 발견하고, 슬라이스 테스트 보류 + Testcontainers 통합 테스트 추가로 대응 방향을 명확히 분리했다.
- `JpaConfig`의 `SQLErrorCodeSQLExceptionTranslator` 빈 등록이 테스트를 통과시키기 위한 조치가 아니라 운영 환경 회귀 방어 역할도 한다는 점을 발견하여 빈 등록을 프로덕션 코드에 포함시켰다.
- catch 타입 교체라는 최소한의 변경으로 NOT NULL / FK / CHECK 위반 가시화 목표를 달성했다. 새 클래스나 레이어 없이 import 한 줄 교체만으로 처리했다.

### 개선할 점

- H2+JPA 환경의 SQL Error Code 매핑 차이가 처음부터 알려진 제약이었다면 슬라이스 테스트 어서션 교체 시도 자체를 생략하고 Testcontainers 단계로 바로 진행할 수 있었다. 테스트 환경 제약을 PRD 단계에서 명시하면 Step 재설계를 줄일 수 있다.
- `JpaConfig`의 빈 등록이 `SQLErrorCodeSQLExceptionTranslator` 매핑에 필수적이라는 사실이 문서화되지 않아 디버깅 시간이 소요됐다. `docs/architecture.md` 또는 `JpaConfig` 클래스 주석에 이 관계를 명시해 두는 것이 좋다.
- `NaverPayServiceIntegrationTest`의 spy 스텁 우회는 H2 환경 한계로 인한 임시 해결책이다. 해당 통합 테스트를 Testcontainers 기반으로 전환하면 spy 스텁 없이도 실제 동작을 검증할 수 있다. 별도 개선 과제로 남긴다.
