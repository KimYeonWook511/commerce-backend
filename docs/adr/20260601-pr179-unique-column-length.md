# multi-column unique constraint 대상 컬럼은 `@Column(length=...)`을 명시한다

- Status: accepted
- Date: 2026-06-01

## Context

`tbl_payment_attempt`의 4개 컬럼이 `VARCHAR(255)` 기본값으로 생성되어 utf8mb4 환경에서 4080 bytes를 차지, MySQL이 unique key 생성을 거부. Hibernate 기본 핸들러가 silent로 넘어가 schema에 unique가 없는 채 운영됨.

옵션 A(대상 컬럼만 length 명시)가 enum 컬럼 length를 명시하지 않기로 한 기존 결정(→ PR#155)의 합리성을 일반 영역에서 유지하면서 본 사고만 좁게 해결한다. 옵션 B(전 컬럼 length 명시)는 그 결정을 폐기해야 한다. `halt_on_error`를 test에 적용하지 않은 이유는 Testcontainer fresh MySQL 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`가 `IF EXISTS` 없이 실행되어 무해 실패가 발생하기 때문이며, test 환경의 회귀 감지는 `NaverPayServiceConcurrencyTest`의 `countAttempts == 1` 데이터 invariant로 대체한다.

## Decision

multi-column `@UniqueConstraint`에 포함되는 String/Enum 컬럼은 `@Column(length=...)`을 명시한다. 합계 바이트가 InnoDB unique key 한도(3072 bytes)를 넘지 않도록 산정한다. 본 결정은 enum length 미명시 결정(→ PR#155)의 좁은 예외다. 함께 `hibernate.hbm2ddl.halt_on_error: true`를 `application-local.yml`에만 적용해 schema 회귀를 부팅 시점에 노출시킨다 (test/prod는 적용 제외).

## Consequences

enum length 미명시 결정(→ PR#155)과 본 ADR의 좁은 예외가 공존한다. 신규 multi-column unique 도입 시 length를 계산해 명시해야 하는 인지 부담이 있다. `halt_on_error`는 local의 `ddl-auto: update` 전제에 묶이며, local ddl-auto 변경 시 함께 재검토해야 한다 (fragile dependency). 동시성 테스트의 race 발생 자체 가시화 단언(`anyMatch DataIntegrityViolationException`)은 환경 의존성으로 CI flake 위험이 있어 제거하고, `countAttempts == 1` + `assertRaceOrPaymentError` helper 조합으로 안전망을 검증한다. 동시성 테스트는 20 thread + 보상 흐름 수용을 위해 클래스 단위 HikariCP 설정(`maximum-pool-size=30`)을 명시한다.

- **한계**: 본 결정은 multi-column unique 대상 컬럼만 length를 명시한다. 같은 의미 컬럼이 entity별로 다른 length를 갖는 cross-entity 길이 불일치는 본 결정 범위 밖이며, 신규 entity 도입 시 동일 의미 컬럼은 같은 length로 맞추는 것을 가이드로 둔다 (이슈 #178).
- **후속 (2026-06-01)**: Flyway 도입으로 `ddl-auto`가 `validate`로 전환되어(→ PR#184) Hibernate가 DDL을 실행하지 않게 되었다. `halt_on_error`의 발동 조건(Hibernate DDL 실행 실패)이 사라져 `application-local.yml`에서 제거한다. 스키마 변경 실패 차단 책임은 Flyway가 가져간다 (마이그레이션 SQL 실패 시 Flyway 자체가 부팅 차단).

상세는 `docs/tasks/payment-attempt-unique-key-length/adr.md` 참조.
