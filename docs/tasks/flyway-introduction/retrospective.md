# 회고: flyway-introduction

## 결정 요약

`ddl-auto: update`를 `validate`로 전환하고 Flyway를 도입해 DB 스키마 변경을 명시적 마이그레이션 스크립트(`V*__*.sql`)로 관리한다. 핵심 이유는 "단일 DB라도 Hibernate dialect 변경이나 silent DDL 실패로 스키마가 코드와 조용히 어긋날 수 있다"는 것을 두 사고가 같은 패턴으로 보여줬기 때문이다. 운영 DB 미가동이라는 시간적 우위를 활용해 V1 단일 베이스라인으로 출발한다.

## 도입 배경 — 두 사고

**사고 1 — ENUM silent drift (이슈 #142, ADR-018).** Hibernate 6.x가 `@Enumerated(STRING)`을 MySQL native ENUM 타입으로 매핑하도록 dialect가 바뀌면서, `ddl-auto: update`로 NOT NULL ENUM 컬럼을 추가할 때 기존 row에 의도하지 않은 첫 번째 값이 자동으로 채워졌다. ADR-018(`@JdbcTypeCode(SqlTypes.VARCHAR)` 적용)으로 신규 컬럼은 막았지만, `ddl-auto: update`는 기존 컬럼의 타입 변경(ENUM → VARCHAR)을 보장하지 않아 운영 DB 정리가 Flyway 도입 후속 트랙으로 남겨졌다.

**사고 2 — unique constraint silent 미적용 (이슈 #176, ADR-023).** `tbl_payment_attempt`의 4-column unique key가 컬럼 길이 미지정으로 InnoDB 한도(3072 bytes)를 초과해 MySQL이 unique 생성을 거부했지만, Hibernate 기본 핸들러가 WARN으로만 로그하고 부팅을 계속했다. unique가 빠진 채 운영됐고, 동시성 테스트의 우연한 타이밍에서야 발견됐다.

두 사고를 나란히 놓고 보면 패턴이 같다 — "코드는 정상인데 DB 스키마가 조용히 어긋난 상태". 이 패턴을 두 번 본 시점에서 "단일 DB라서 Flyway가 필요 없다"는 입장이 뒤집혔다. 단일 DB라도 external 시스템 분기가 아닌 코드 내부 요인(dialect 변경, DDL silent fail)에 의해 drift가 발생한다는 것이 결정적 인식 전환이었다.

## 진행 과정에서 검토한 대안

**`test` 프로파일 전체를 Testcontainers MySQL로 전환하는 안.** dockerTest와 unit/slice test를 같은 프로파일로 통일하면 Flyway 활성/비활성 분기 자체가 없어진다. 그러나 단위/슬라이스 테스트의 부팅 속도 자산(H2 인메모리)과 Docker 의존도 증가 비용이 맞지 않아 탈락했다.

**dockerTest에서도 Flyway 비활성으로 유지하는 안.** `@ActiveProfiles("test")`가 적용되는 dockerTest에서 `ddl-auto: create-drop`을 그대로 쓰는 것을 고려했다. 그러나 `create-drop`은 컨텍스트 시작 시에만 drop이 일어나고, 매 테스트 후 drop이 아니다. 컨테이너 싱글톤 재사용 + 컨텍스트 캐싱 모델에서는 `@AfterEach deleteAllInBatch()` 격리가 `create-drop`이 아닌 `validate` 위에서 자연스럽게 작동한다. 결국 dockerTest에서 Flyway를 활성화하는 것이 기존 격리 모델과 더 잘 맞물렸다.

**ADR-024 초안의 일반론 구조.** 초안에서 일반적 Flyway 장점 나열로 시작했으나, 두 사고가 결정의 실질 동기임을 명확히 하는 방향으로 재구성됐다. ADR-018·ADR-023의 회고 원문을 그대로 인용해 두 사고가 공통 패턴임을 드러내는 구조가 됐다. 결정 근거 추적 관점에서 회고 인용을 ADR에 박는 것이 "어떤 사고에서 비롯된 결정인가"를 후속 독자에게 더 잘 전달한다.

## 진행 중 발견한 이슈와 처리

**테스트 전용 엔티티 누락.** Step 3(Flyway 활성 + `validate` 전환) 이후 `./gradlew dockerTest`가 실패했다. 원인은 `async_test`와 `lock_member` 테이블이 V1__init.sql에 없는 것이었다. 두 테이블은 테스트 지원용(`AsyncTestEntity`, `LockMemberEntity`)이라 `ddl-auto: create-drop` 시절에는 H2가 자동 생성했지만, Testcontainers MySQL + `validate`로 전환하자 스키마 불일치로 부팅 실패가 발생했다. `src/test/resources/db/migration/V900__test_support.sql`을 추가해 해소했다. V900이라는 높은 번호는 운영 마이그레이션과의 충돌을 피하기 위한 네임스페이스 선택이다.

**PRD의 "엔티티 12개" 기재와 실제 덤프의 11개 불일치.** PRD 작성 시점의 카운트 오류였다. 실제 V1__init.sql은 11개 테이블(member/product/stock/stock_history/cart_item/order/order_item/payment/payment_attempt/outbox_event/processed_event)이다. Spring Batch 메타테이블은 설계대로 제외됐고, 덤프 시 `SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=never` 환경변수로 Batch 테이블이 섞이지 않도록 했다.

**ENUM 컬럼 VARCHAR 매핑 확인 (ADR-018 검증).** V1__init.sql의 ENUM 컬럼이 MySQL native ENUM이 아닌 `varchar(255)`로 떠 있음을 확인했다. `@JdbcTypeCode(SqlTypes.VARCHAR)` 적용 결과가 덤프에 올바르게 반영됐다.

**payment_attempt unique key 컬럼 길이 확인 (ADR-023 검증).** `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`의 4개 컬럼이 각각 varchar(64)/varchar(32)/varchar(64)/varchar(32)로 덤프됐다. utf8mb4 기준 합계 768 bytes로 InnoDB 한도 3072 bytes 이내임을 확인했다.

## validate 도입 후 개발 흐름 변화

Step 3 완료 후 `validate`가 실제로 작동하는지 의도적 불일치 시나리오를 돌려 확인했다. 임시로 엔티티에 더미 필드를 추가하고 `./gradlew bootRun`을 시도하면 `SchemaManagementException`으로 즉시 부팅 실패가 발생했다. 마이그레이션 스크립트 없이 엔티티만 수정하면 로컬에서 부팅이 안 된다는 것이 validate 도입의 핵심 효과이며, 이로 인해 PR에 엔티티 변경과 V 스크립트를 함께 올려야 한다는 강제가 생겼다.

dockerTest의 경우, 첫 컨텍스트 로드 시 Flyway V1 적용 로그(`Successfully applied 1 migration`)와 이후 컨텍스트 캐시 재사용 시 "Schema is up to date" 로그로 구분됨을 확인했다. `deleteAllInBatch()` 기반 격리도 이전과 동일하게 동작했다.

## 남은 과제 / 후속 task

- PR 컨벤션(`docs/pr-conventions.md`)에 "마이그레이션 스크립트" 섹션 추가. 엔티티 변경 PR에서 V 스크립트 동반을 체크리스트화. 별도 chore PR.
- `docs/db-schema.md`와 `V*__*.sql`의 역할 분담 상세 가이드. 현재 db-schema.md는 의도 설명 reference이고 DDL은 V 스크립트가 단일 출처라는 원칙을 더 명확히 정리. 별도 docs PR.
- CI(`ciTest`)가 dockerTest를 포함하는지 점검. 미포함 시 마이그레이션 스크립트 회귀가 dockerTest에서만 잡히는 구조의 자동 검증이 미흡하다.
- 운영 DB의 기존 ENUM 컬럼(ENUM → VARCHAR 미 ALTER 가능성, ADR-018 한계)은 운영 가동 전 V2 등 별도 마이그레이션으로 정리가 필요하다.
- `halt_on_error`(ADR-023, `application-local.yml`)는 본 PR review 단계에서 재검토하여 제거했다. `validate` 전환으로 Hibernate가 DDL을 실행하지 않게 되어 `halt_on_error`의 발동 조건 자체가 사라졌다. 스키마 변경 실패 차단 책임은 Flyway가 가져간다. ADR-023에 후속 메모를 추가했다.

## 다음에 비슷한 결정을 할 때 참고할 것

이 결정은 시점 의존적이다. 운영 DB 미가동이라는 조건이 "V1 단일 베이스라인"을 가능하게 했다. 운영 가동 후에는 baseline-on-migrate, 운영 dump, checksum 검증 등 절차가 복잡해진다. 도입 결정이 지연될수록 절차 비용이 기하급수적으로 늘어나므로 "Flyway는 나중에 도입하면 된다"는 판단은 가동 여부를 함께 고려해야 한다.

"단일 DB라도 silent drift는 일어난다"는 일반화는 이번 두 사고에서 얻은 핵심 교훈이다. 이전에는 drift가 다중 DB 환경이나 팀 간 스키마 분기에서만 문제라고 봤지만, dialect 업그레이드나 DDL silent fail처럼 단일 코드베이스 내부 요인이 더 위험할 수 있다. ADR에 회고 원문을 그대로 인용하는 방식은, 추상적인 "drift 위험"보다 실제 발생한 사고의 구체성이 결정 근거 추적에 훨씬 효과적이었다.
