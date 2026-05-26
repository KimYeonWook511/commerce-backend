# 태스크 아키텍처

## 변경 범위

본 태스크는 entity 매핑 layer만 수정한다. application/domain 로직, repository, 외부 인터페이스 변경 없음.

## 변경 패턴

```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
// ...

@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
@Column(nullable = false)
private OutboxEventStatus status;
```

- `@Enumerated(EnumType.STRING)`: 기존 그대로 (enum 이름을 문자열로 직렬화)
- `@JdbcTypeCode(SqlTypes.VARCHAR)`: Hibernate가 MySQL ENUM이 아닌 VARCHAR로 매핑하도록 JDBC 타입을 명시
- `@Column`: `nullable`/`unique` 등 기존 속성 유지. `length` 속성은 제거 (Hibernate 기본 VARCHAR(255))

`@Column` 자체가 없는 nullable 필드(`PaymentAttempt.failCode`)는 추가하지 않고 `@JdbcTypeCode`만 부착한다.

## DDL 효과

| 환경 | `ddl-auto` | 효과 |
|---|---|---|
| test | `create-drop` | 매 테스트 실행 시 신규 생성 → VARCHAR(255) 컬럼 |
| local | `update` | 기존 DB의 컬럼 타입은 그대로일 수 있음. 신규 컬럼만 VARCHAR(255) |
| prod | `update` | local과 동일. 기존 ENUM 컬럼은 Hibernate `update`가 ALTER하지 않을 가능성 → Flyway 도입 시 일괄 처리 |

## 의존성

- Hibernate 6.x annotation 사용 (`org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`)
- JPA 표준에서 벗어나지만 entity 코드가 이미 Hibernate에 의존하고 있으므로 추가 부담 없음.

## 영향 받지 않는 영역

- application 계층 service/유스케이스
- domain repository 인터페이스
- API 응답/요청 DTO
- 외부 통합 (PG, Redis, Kafka)
- 테스트 코드 (entity builder/도메인 로직 미변경)
