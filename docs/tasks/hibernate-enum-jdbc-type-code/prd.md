# 태스크 PRD

## 태스크명

- `hibernate-enum-jdbc-type-code`

## 배경

Spring Boot 3.x(Hibernate 6.x)부터 `@Enumerated(EnumType.STRING)`이 MySQL에서 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑된다. MySQL ENUM은 다음과 같은 조용한 결함을 유발한다.

- INSERT 시 컬럼을 생략하면 첫 번째 ENUM 값이 자동 삽입되며, NOT NULL 제약에서도 오류가 나지 않는다.
- `ddl-auto: update`로 기존 테이블에 ENUM 컬럼을 추가할 때 기존 row에 첫 번째 ENUM 값이 자동 채워진다.
- VARCHAR 시절이라면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM 타입에서는 묻힌다.

본 태스크는 모든 entity의 `@Enumerated(EnumType.STRING)` 필드를 Hibernate 6.5 공식 가이드 권장 방식(`@JdbcTypeCode(SqlTypes.VARCHAR)`)으로 정리해 MySQL ENUM 매핑을 회피한다.

## 목표

- 모든 entity의 `@Enumerated(EnumType.STRING)` 필드가 MySQL VARCHAR 컬럼으로 매핑된다.
- 신규 entity 작성 시 동일 패턴을 따르도록 ADR로 컨벤션을 남긴다.
- 운영 DB의 기존 ENUM 컬럼 실제 ALTER 작업은 Flyway 도입 시 일괄 처리하도록 한계를 명시한다.

## 범위

### 포함
- 14개 entity 필드(8개 entity)에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착
- 기존 `@Column(length=N)`의 `length` 속성 제거 (Hibernate 기본 VARCHAR(255) 사용)
- `docs/adr.md`에 ADR-018 추가

### 제외
- `OrderIdempotencyStatus` (Redis 직렬화용, `@Entity` 매핑 아님)
- NaverPay 응답용 enum (비-entity)
- 운영 DB의 기존 ENUM 컬럼 ALTER
- 운영 DB에 이미 잘못 들어간 row 점검 및 보정

## 주요 시나리오

- 신규 환경에서 entity 매핑 기반 DDL 생성 시 `VARCHAR` 컬럼이 생성된다.
- 신규 entity 작성 시 `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)` 패턴을 따른다.
- 운영 DB의 기존 ENUM 컬럼은 본 코드 변경만으로는 ALTER되지 않을 수 있다(Hibernate `update` 한계). 이는 Flyway 도입 시점에 일괄 정리한다.

## 요구사항

- 모든 매핑된 enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착.
- 기존 `length` 속성 제거.
- import: `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`.
- ADR-018에 결정 배경, 채택 근거, 한계, 신규 entity 컨벤션 명시.

## 제약사항

- 운영 DB(prod) `ddl-auto: update` 환경에서 컬럼 타입 변경이 자동 처리되지 않을 가능성. Flyway 도입 후 일괄 처리.
- 본 작업으로 운영 DB의 기존 row 중 첫 번째 ENUM 값으로 채워진 의심 row를 자동 식별하지는 않는다.
