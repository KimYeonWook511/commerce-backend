# 태스크 PRD

## 태스크명

- `flyway-introduction`

## 배경

현재 commerce-backend는 모든 환경에서 `spring.jpa.hibernate.ddl-auto: update`로 스키마를 관리한다. `application-prod.yml`에는 "추후 DB 마이그레이션 학습 후 validate로 변경할 것" 주석이 남아있어 도입 의도는 이미 명시돼 있었지만, 단일 MySQL 운영 + 다중 DB 계획 없음이라는 단순함을 이유로 도입을 미뤄 왔다.

이 입장을 뒤집은 두 사고가 최근에 있었다.

1. **ENUM silent drift** (이슈 #142, ADR-018, 2026-05-26) — Hibernate 6.x dialect 변경으로 `@Enumerated(STRING)`이 MySQL native ENUM으로 매핑되면서, ddl-auto: update가 NOT NULL ENUM 컬럼을 추가할 때 기존 row가 의도하지 않은 첫 번째 값으로 묻혔다. 코드 변경으로 신규 컬럼은 막을 수 있지만 운영 DB의 기존 ENUM → VARCHAR 정정은 `ddl-auto: update`가 보장하지 않는다.
2. **unique constraint silent 미적용** (이슈 #176, PR #179, ADR-023, 2026-05-31) — `tbl_payment_attempt`의 4-column unique key가 `@Column(length=...)` 미지정으로 InnoDB 한도 초과 → MySQL이 unique 생성을 거부했으나 Hibernate 기본 핸들러가 WARN으로만 로그하고 부팅 계속. 운영 schema에 unique가 빠진 채 흘러간 사실을 동시성 테스트의 우연한 타이밍에서야 발견.

두 사고는 *코드는 정상이지만 DB schema가 silent하게 어긋난 상태*라는 같은 패턴이고, 단일 DB 운영이라도 schema drift가 발생함을 보여준다. drift 원인이 외부 시스템 분기가 아니라 Hibernate dialect 변경 / silent fail이라는 코드 내부 요인이라는 점이 결정적이다.

운영 DB 미가동이라는 시간적 우위가 있어 baseline-on-migrate 같은 복잡한 절차 없이 V1 단일 스크립트로 출발할 수 있다.

## 목표

- 엔티티 변경이 마이그레이션 스크립트와 명시적으로 짝지어지도록 한다.
- 운영 부팅 시 스키마 불일치를 즉시 가시화한다 (`ddl-auto: validate`).
- 단위/슬라이스 테스트(H2)는 추가 마찰 없이 기존 흐름을 유지한다.

## 범위

**포함 범위**
- Flyway 의존성 도입 (`flyway-core` + `flyway-mysql`)
- 현 도메인 엔티티 11개 기준 `V1__init.sql` 생성
- local/prod/dockerTest 환경의 `ddl-auto: validate` 전환 및 Flyway 활성화
- `test` 프로파일은 H2 + Flyway 비활성 유지
- `TestcontainersSupport`의 dockerTest용 override 변경
- `docs/adr.md`에 ADR-024 추가
- `docs/db-schema.md`에 마이그레이션 위치/네이밍 안내 추가

**제외 범위**
- baseline-on-migrate. 운영 DB가 없어 불필요.
- Spring Batch 메타테이블의 Flyway 편입. Batch 자체 `initialize-schema: always` 유지.
- PR 컨벤션(`docs/pr-conventions.md`)에 마이그레이션 섹션 추가. 별도 chore PR.
- `docs/db-schema.md`와 `V*__*.sql`의 역할 분담 가이드 상세화. 별도 docs PR.
- CI(`ciTest`) 구성 변경 검토. 후속.

## 주요 시나리오

- **신규 환경 부팅**: 빈 MySQL → 부팅 시 Flyway가 V1 적용 → Hibernate validate 통과 → 정상 시작.
- **재부팅**: 이미 V1 적용된 DB → "Schema is up to date" 로그 → 정상 시작.
- **엔티티 변경**: 개발자가 엔티티 수정 후 마이그레이션 스크립트(V2, V3 ...)를 같은 PR에서 작성하지 않으면 부팅 실패.
- **dockerTest 실행**: 첫 컨텍스트가 컨테이너 시작 시 Flyway V1 적용 → 이후 컨텍스트는 V1 skip → 기존 `deleteAllInBatch()` 격리 그대로.

## 요구사항

- `flyway-core`, `flyway-mysql`이 Spring Boot 3.5.9 BOM에서 한 버전으로 해석되어야 한다.
- `V1__init.sql`은 현 도메인 엔티티 11개의 스키마를 모두 포함해야 한다.
- ENUM 필드는 모두 VARCHAR로 매핑되어야 한다 (ADR-018 준수).
- `tbl_payment_attempt`의 `uk_payment_attempt_merchant_pay_key_provider_payment_id_type` unique constraint는 컬럼 길이가 명시되어 InnoDB 한도 내여야 한다 (ADR-023 준수).
- Spring Batch 메타테이블은 V1__init.sql에 포함되지 않아야 한다.
- `spring.flyway.clean-disabled: true`를 명시한다 (운영 안전망).
- `application-test.yml`은 Flyway 비활성, `TestcontainersSupport`는 dockerTest에서 Flyway 활성을 명시적으로 override해야 한다 (test profile 충돌 해소).
- `./gradlew test`와 `./gradlew dockerTest` 모두 통과해야 한다.

## 제약사항

- 운영 DB 미가동 상태에서만 가능한 단순 도입(V1 단일 베이스라인). 운영 가동 후 도입 시 절차 복잡도가 증가하므로 시점 의존적 결정.
- Flyway 10.x 시리즈 (Spring Boot 3.5 BOM 기준). 메이저 업그레이드 시 release note 추적 책임 발생.
- validate는 컬럼/타입 누락은 잡지만 unique constraint / index 누락은 잡지 않는다. 사고 2 유형은 Flyway 도입 후에도 코드 규칙(ADR-023)이 1차 방어선.
- 사용자 환경의 docker-compose.local.yml MySQL을 V1 생성 단계에서 사용한다. `mysql-data-local/` 디렉토리는 destructive 삭제 대상.
