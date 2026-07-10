# Hibernate 6.x ENUM 매핑은 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한다

- Status: accepted
- Date: 2026-05-26

## Context

Hibernate 6.x부터 MySQL dialect가 `@Enumerated(EnumType.STRING)`을 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑한다. MySQL ENUM은 INSERT 시 컬럼 생략 시 첫 번째 ENUM 값이 조용히 삽입되며, `ddl-auto: update`로 컬럼 추가 시 기존 row에 첫 번째 값이 자동 채워진다. VARCHAR였다면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM에서는 묻힌다. Hibernate 6.5 공식 마이그레이션 가이드는 `@JdbcTypeCode(SqlTypes.VARCHAR)` 또는 `@Column(columnDefinition = "varchar(N)")` 두 방식을 제시한다.

`@JdbcTypeCode`가 dialect-agnostic하고 선언적이며 `@Column(length=N)`과의 분리가 가능하다. `columnDefinition`은 raw SQL fragment를 박아 dialect 변경에 fragile하고 length 속성과 충돌한다. 또한 향후 native ENUM을 채택할 때 annotation 하나만 제거하면 되는 전환 비용이 낮다. 컬럼 길이를 명시하지 않는 이유는, enum 값은 개발자가 정의한 코드 상수만 저장되어 외부 입력 길이 제한 같은 보안/검증 의미가 없고, length를 명시하면 enum 추가 시 동기화 부담만 발생하기 때문이다.

참고: Hibernate 6.5 Migration Guide, Hibernate Discourse "String Enum mapping for MySQL only". 상세는 `docs/tasks/hibernate-enum-jdbc-type-code/adr.md` 참조.

## Decision

모든 entity의 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다. 컬럼 길이는 명시하지 않고 Hibernate 기본값(VARCHAR(255))을 사용한다. 신규로 추가되는 entity의 `@Enumerated(EnumType.STRING)` 필드도 동일 패턴을 따른다.

## Consequences

JPA 표준에서 벗어나 Hibernate-specific annotation을 도입한다. 다만 entity 코드는 이미 Hibernate에 결합되어 추가 부담은 미미하다. 컬럼 길이 통제권은 약해지나 enum 특성상 통제 가치가 낮다.

- **한계**: Hibernate `ddl-auto: update`는 컬럼 타입 변경(ENUM → VARCHAR)을 보장하지 않는다. 본 코드 변경만으로는 운영 DB의 기존 ENUM 컬럼이 그대로 남을 가능성이 있다. 운영 DB ALTER는 Flyway 도입 시 일괄 마이그레이션 스크립트로 정리한다. ENUM 컬럼 생성 시점부터 본 fix 전까지 "첫 번째 enum 값이 조용히 삽입된" 의심 row 점검은 별도 후속 트랙이다.
- **후속 (2026-06-02)**: Flyway 도입으로 `ddl-auto`가 `validate`로 전환되어(→ PR#184) *기존 row에 첫 번째 enum 값이 묻히는* 사고 경로(MySQL ddl-auto: update가 NOT NULL ENUM 컬럼 추가 시 발생)는 닫혔다. 그러나 본 결정은 (a) test 프로파일(H2 pure mode + ddl-auto: create-drop)과 prod/local(MySQL + Flyway varchar) 사이의 *INSERT 시 NOT NULL silent fill 행위 parity* 보장, (b) Hibernate dialect 변경 안전망의 두 역할로 코드 규칙으로 유지한다. integrationTest로 Hibernate SchemaValidator가 enum vs varchar의 sql type 차이를 strict 비교하지 않음(silent zone)을 확인했다 — 본 매핑이 빠지면 validate도 못 잡는 silent drift가 잠재한다. 테스트 지원 entity(`AsyncTestEntity` 등)도 동일 규칙을 따른다.
