# 태스크 ADR

## 결정 제목

Hibernate 6.x ENUM 매핑은 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한다.

## 배경

Hibernate 6.x부터 MySQL에서 `@Enumerated(EnumType.STRING)`이 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑되도록 dialect가 변경됐다. MySQL ENUM은 INSERT 시 컬럼 생략 시 첫 번째 값이 조용히 삽입되며, `ddl-auto: update`로 컬럼 추가 시 기존 row에도 첫 번째 값이 자동 채워진다. 의도하지 않은 값이 묻히는 위험.

Hibernate 6.5 공식 마이그레이션 가이드는 두 옵션을 제시한다.
1. `@JdbcTypeCode(SqlTypes.VARCHAR)`
2. `@Column(columnDefinition = "varchar(N)")`

본 태스크 시작 시점에는 (2)를 우선 검토했으나, 사용자가 Hibernate 공식 가이드의 (1)을 지적했다.

## 결정 내용

- 모든 entity의 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다.
- 컬럼 길이는 명시하지 않는다 (`length` 속성 제거, Hibernate 기본 VARCHAR(255) 사용).
- 신규로 추가되는 entity의 `@Enumerated(EnumType.STRING)` 필드도 동일 패턴을 따른다.

## 근거

### `@JdbcTypeCode` vs `columnDefinition`

| 기준 | `@JdbcTypeCode` | `columnDefinition` |
|---|---|---|
| dialect 이식성 | dialect-agnostic | raw SQL 박힘 |
| 의도 표현 | 선언적 (JDBC 타입 지정) | 절차적 (DDL fragment) |
| `length` 와의 관계 | `@Column(length=N)` 분리 가능 | length 무시됨 |
| schema 비교 | Hibernate type system 기반 | 문자열 비교 (fragile) |
| 향후 native ENUM 채택 시 | annotation 1개 제거 | columnDefinition 제거 + length 재추가 |

→ `@JdbcTypeCode`가 정공법.

### length 명시 안 하는 이유

- enum 값은 개발자가 정의한 코드 상수만 저장됨 (외부 입력 아님).
- 사용자 입력 길이 제한 같은 보안/검증 의미가 없음.
- VARCHAR는 가변 길이라 저장 공간 차이 실질 0 (InnoDB).
- length 명시하면 enum 추가 시 동기화 부담만 발생, 보호 효과는 없음.
- 운영 DB ALTER 마이그레이션 횟수 증가 원인이 됨.

## 결과

### 즉시 효과
- 신규 환경/test 환경에서 enum 컬럼이 VARCHAR(255)로 생성됨.
- MySQL ENUM의 조용한 default 삽입 위험 제거.
- 신규 entity 작성 시 일관된 패턴 적용.

### 한계와 후속 작업
- **운영 DB의 기존 ENUM 컬럼**: Hibernate `ddl-auto: update`는 컬럼 타입 변경을 보장하지 않는다. 본 코드 변경만으로는 운영 DB의 기존 ENUM 컬럼이 그대로 남을 가능성이 있다. **Flyway 도입 시 일괄 ALTER 마이그레이션 스크립트로 정리한다**.
- **운영 데이터 무결성 점검**: Hibernate가 ENUM 컬럼을 생성한 시점부터 본 fix 전까지 "첫 번째 enum 값이 조용히 삽입된" 의심 row가 존재할 수 있다. 점검은 별도 후속 트랙으로 진행한다.

### Trade-off
- JPA 표준에서 벗어나 Hibernate-specific annotation 도입. 다만 entity 코드는 이미 Hibernate(`@Type` 등 잠재적 사용 영역, BaseTimeEntity 의존)에 결합되어 추가 부담은 미미하다.
- 컬럼 길이 명시를 포기하므로 DB 컬럼 폭 통제권은 약해진다. 다만 enum의 특성상 통제 가치가 낮다.

## 참고

- Hibernate 6.5 Migration Guide
- Hibernate Discourse: "String Enum mapping for MySQL only"
