# 태스크 PRD

## 태스크명

- `payment-attempt-unique-key-length`

## 배경

- 이슈 #176: `NaverPayServiceConcurrencyTest` 8개 중 7개가 `IncorrectResultSizeDataAccessException: 2 results were returned`로 실패.
- 진단 정정 (2026-05-31): 원인은 race window가 아니라 `tbl_payment_attempt`의 unique constraint `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`이 DB schema에 적용되지 않은 상태였음.
- 4개 컬럼(`merchant_pay_key`, `provider`, `payment_id`, `type`)이 모두 `@Column(length=...)` 미지정으로 `VARCHAR(255)` 기본값 → utf8mb4 환경에서 unique index 한 개의 바이트 합이 4080 bytes로 InnoDB 한도 3072 bytes 초과 → MySQL이 `Specified key was too long`로 거부.
- Hibernate 기본 핸들러 `ExceptionHandlerLoggedImpl`이 schema 생성 실패를 WARN으로만 로그하고 부팅을 계속하면서 schema에 unique가 빠진 채 운영되어 왔다.

## 목표

- `tbl_payment_attempt`의 unique constraint가 DB에 정상 적용되어 ADR-011 (find-first 패턴)의 안전망이 의도대로 작동하는 상태로 복원한다.
- 같은 류의 schema 생성 silent 실패가 재발하지 않도록 `halt_on_error` 안전망을 둔다 (local). test 환경은 Testcontainer fresh MySQL의 ALTER FK DROP 무해 실패와 충돌하므로 제외하고, `NaverPayServiceConcurrencyTest`의 `countAttempts == 1` 데이터 invariant로 회귀 감지를 대체한다.
- `NaverPayServiceConcurrencyTest`가 unique 누락을 직접 잡는 단언 구조를 갖춘다.

## 범위

### 포함 범위

- `PaymentAttempt` entity의 4개 컬럼(`merchantPayKey`, `paymentId`, `provider`, `type`)에 `@Column(length=...)` 명시.
- `application-local.yml`에 `hibernate.hbm2ddl.halt_on_error: true` 추가 (test와 prod는 제외).
- `build.gradle` `dockerTest` task에 `excludeTags "concurrency"` 한 줄 추가.
- `NaverPayServiceConcurrencyTest`에 `countAttempts == 1` 데이터 invariant 추가 + 클래스 단위 HikariCP 설정 명시.
- 루트 `docs/ADR.md`, `docs/db-schema.md`, `docs/testing-conventions.md` 동기화.

### 제외 범위

- prod DB 수정 사항 (운영 미가동, 추후 Flyway 도입 흐름에서 처리).
- `@Tag` 차원 정리 (이슈 #177로 분리).
- ADR-011 (find-first 패턴) 본문 수정 — 본 작업은 그 정책이 의존하는 전제(unique constraint 존재)의 복원이며, 정책 자체는 유효.

## 주요 시나리오

- 동일 `(merchantPayKey, provider, paymentId, type)`로 동시에 N건의 INSERT 요청이 와도 DB unique constraint이 한 건만 commit하고 나머지는 `DataIntegrityViolationException`으로 떨어진다.
- `./gradlew dockerTest` 또는 `./gradlew concurrencyTest`를 실행하면 `NaverPayServiceConcurrencyTest` 8개 케이스가 모두 통과한다.
- 향후 어떤 entity가 ddl-auto에서 schema 생성에 실패하면 부팅이 silent하게 계속되지 않고 곧장 실패하여 회귀가 노출된다 (test, local).

## 요구사항

- `PaymentAttempt` 4개 컬럼 length 명시 (unique key columnNames 순서로 표기): `merchantPayKey=64`, `provider=32`, `paymentId=64`, `type=32`. 합 768 bytes (utf8mb4 기준) < 3072 한도.
- `application-local.yml`에 `spring.jpa.properties.hibernate.hbm2ddl.halt_on_error: true`. test와 prod yml에는 추가하지 않는다.
- `build.gradle`의 `dockerTest`에 `excludeTags "concurrency"` 추가.
- 테스트 단언:
  - 데이터 invariant: `countAttempts(merchantPayKey, paymentId, type) == 1` (race 종료 후).
  - errors 안의 예외 분류는 기존 `assertRaceOrPaymentError` helper(`DataIntegrityViolationException` 또는 케이스별 도메인 `PaymentException` 허용)가 검증. race 발생 자체의 가시화 단언(`anyMatch DataIntegrityViolationException`)은 환경 의존적 CI flake 위험으로 제거.
- `NaverPayServiceConcurrencyTest`에 클래스 단위 HikariCP 설정 명시(`maximum-pool-size=30`, `minimum-idle=10`, `connection-timeout=30000`). 다른 concurrency 테스트(`OrderConcurrencyServiceTest` 등) 컨벤션과 동일 방식.
- 루트 `docs/ADR.md`에 새 ADR 항목 추가.

## 제약사항

- `halt_on_error`는 local에만 적용한다. test는 Testcontainer fresh MySQL이 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`를 IF EXISTS 없이 실행해 무해 실패가 발생, halt_on_error와 충돌하므로 제외한다. prod는 운영 미가동 + 추후 Flyway 도입 시 ddl-auto: validate로 가면서 자연스럽게 의미 소실되므로 제외한다.
- local의 `halt_on_error` 적용은 `ddl-auto: update`라는 전제에 의존한다 (drop을 수행하지 않음). 미래에 local ddl-auto가 `create-drop`/`create`로 변경되면 같은 ALTER FK DROP 충돌이 재발하므로 `halt_on_error` 적용 여부를 함께 재검토해야 한다.
- ADR-018 (Hibernate ENUM `@JdbcTypeCode(SqlTypes.VARCHAR)`)은 "컬럼 길이는 명시하지 않는다"라고 결정되어 있다. 본 task의 ADR은 그 정책의 **좁은 예외**(multi-column unique constraint 대상 컬럼은 명시)임을 명시.
- 로컬 MySQL 볼륨(`./mysql-data-local`)은 wipe 후 ddl-auto로 재생성한다 (사용자 환경에서 직접 처리).
