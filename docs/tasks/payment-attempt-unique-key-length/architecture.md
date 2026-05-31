# 태스크 아키텍처

## 개요

- 본 task는 신규 기능을 추가하지 않는다. `tbl_payment_attempt`의 unique constraint이 DB schema에 누락된 상태를 복원하는 schema/설정 fix다.
- 변경 영향은 entity column 정의, 테스트/로컬 yml, build script, 테스트 코드, 루트 docs에 한정된다.

## 변경 대상

- **Domain**: `com.commerce.payment.domain.PaymentAttempt` — 4개 컬럼에 `@Column(length=...)` 명시.
- **Application 설정**: `src/main/resources/application-test.yml`, `application-local.yml` — `hibernate.hbm2ddl.halt_on_error: true` 추가.
- **Build script**: `build.gradle` — `dockerTest` task에 `excludeTags "concurrency"` 추가.
- **Test 코드**: `src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java` — `countAttempts == 1` 데이터 invariant 추가 + 클래스 단위 HikariCP 설정(`maximum-pool-size=30`, `minimum-idle=10`, `connection-timeout=30000`) 명시.
- **Test support 재사용**: `PaymentPersistenceTestSupport.countAttempts(...)`, `countCancelAttempts(...)` (이미 존재).
- **Root docs**: `docs/ADR.md`, `docs/db-schema.md`, `docs/testing-conventions.md` 일부 보강.

## 설계 방향

- entity의 `@UniqueConstraint` 정의는 그대로 유지하고, 컬럼 length만 정확히 명시해 DDL 생성이 성공하도록 한다.
- ADR-011 (find-first 패턴)의 안전망 모델은 유지한다. 본 task는 그 모델이 의존하는 DB constraint를 복원하는 것이지, 모델 자체의 변경이 아니다.
- `halt_on_error`는 test/local 환경에 한정 — 부팅 시 schema 정합성 회귀를 즉시 노출. prod는 추후 Flyway 도입 흐름에서 처리한다.
- 테스트 단언은 결과 invariant(count==1)와 흐름 invariant(특정 예외 발생) 두 축으로 분리하여, 둘 중 하나만 깨져도 회귀가 잡힌다.

## 데이터 흐름

- 본 task는 데이터 흐름 자체를 바꾸지 않는다. 단, schema에 unique constraint이 적용되어 다음 행동이 변한다:
  - **현재(버그)**: 동일 키 동시 INSERT N건이 모두 commit → find 시 `IncorrectResultSizeDataAccessException`.
  - **수정 후**: 동일 키 동시 INSERT N건 중 한 건만 commit, 나머지는 `DataIntegrityViolationException` → `GlobalExceptionHandler`가 안전망 500 처리 (ADR-011 흐름).

## 예외 및 실패 처리

- race window 진입 스레드:
  - `DataIntegrityViolationException` → `DataAccessException` 부모 핸들러 → `COMMON-500-2` (ADR-011).
  - 도메인 `PaymentException` (case에 따라) → 케이스별 코드.
- schema 생성 실패 (예: 향후 또 다른 entity에서 같은 류 사고): test/local에서는 `halt_on_error`로 부팅 실패. prod에서는 여전히 silent (Flyway 도입 후 해소).

## 테스트 포인트

- `NaverPayServiceConcurrencyTest` 8개 케이스가 모두 통과한다.
- 각 케이스에서 attempt count가 정확히 1 (race가 일어나도 unique constraint이 잡아주는지 가시화).
- race가 실제로 발생한 케이스에서는 errors 컬렉션에 `DataIntegrityViolationException`이 적어도 한 건 포함된다 (안전망 흐름이 실제로 발동했는지 가시화).
- `./gradlew test`, `./gradlew dockerTest`, `./gradlew concurrencyTest` 모두 통과하며 동일 클래스 중복 실행 없음.
