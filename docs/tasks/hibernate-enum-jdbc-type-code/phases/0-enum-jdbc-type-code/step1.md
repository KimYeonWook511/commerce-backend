# Step 1: apply-jdbc-type-code

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/hibernate-enum-jdbc-type-code/prd.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/architecture.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/adr.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/api-spec.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/db-schema.md`

추가로 변경 대상 파일들의 현재 상태를 읽어 기존 `@Column` 속성을 정확히 파악한다:

- `/src/main/java/com/commerce/outbox/domain/ProcessedEvent.java`
- `/src/main/java/com/commerce/outbox/domain/OutboxEvent.java`
- `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/main/java/com/commerce/payment/domain/Payment.java`
- `/src/main/java/com/commerce/member/domain/Member.java`
- `/src/main/java/com/commerce/product/domain/Product.java`
- `/src/main/java/com/commerce/stock/domain/StockHistory.java`

## 작업

8개 entity의 14개 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 부착하고, 기존 `@Column`의 `length` 속성을 제거한다.

### 적용 패턴

기본 (`@Column`이 있는 필드):
```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
@Column(nullable = false)   // 기존 nullable/unique 등 유지, length만 제거
private OutboxEventStatus status;
```

`@Column`이 원래 없던 필드 (`PaymentAttempt.failCode`):
```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
private PaymentAttemptFailCode failCode;
```

### 변경 대상 (14개 필드)

| 파일 | 필드 | 기존 length | 비고 |
|---|---|---|---|
| `outbox/domain/ProcessedEvent.java` | `consumerType` | 50 | length 제거 |
| `outbox/domain/OutboxEvent.java` | `eventType` | 50 | length 제거 |
| `outbox/domain/OutboxEvent.java` | `status` | 20 | length 제거 |
| `outbox/domain/OutboxEvent.java` | `aggregateType` | 30 | length 제거 |
| `order/domain/Order.java` | `status` | - | length 없음, 그대로 |
| `payment/domain/PaymentAttempt.java` | `provider` | - | - |
| `payment/domain/PaymentAttempt.java` | `type` | - | - |
| `payment/domain/PaymentAttempt.java` | `status` | - | - |
| `payment/domain/PaymentAttempt.java` | `failCode` | - | `@Column` 없음, `@JdbcTypeCode`만 부착 |
| `payment/domain/Payment.java` | `status` | - | - |
| `payment/domain/Payment.java` | `provider` | - | - |
| `member/domain/Member.java` | `role` | - | - |
| `product/domain/Product.java` | `status` | - | - |
| `stock/domain/StockHistory.java` | `reason` | - | - |

### import 추가

각 변경 파일에 다음 import 추가:
```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
```

### 손대지 않을 것

- `nullable`, `unique` 등 다른 `@Column` 속성
- `@Enumerated(EnumType.STRING)` 자체
- entity의 builder, factory method, domain 로직
- enum 클래스 자체
- repository, service, controller, test 코드

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `rg "@Enumerated\(EnumType\.STRING\)" src/main/java -A 3`로 모든 매칭 직후에 `@JdbcTypeCode(SqlTypes.VARCHAR)`가 있는지 확인.
   - 변경한 entity 파일들에서 `length =` 잔재가 없는지 확인.
   - import에 `JdbcTypeCode`, `SqlTypes`가 추가됐는지 확인.
   - architecture.md 디렉토리 구조를 따르는가? (entity 위치 변경 없음)
   - ADR 기술 스택을 벗어나지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 본 작업 범위 외의 entity 필드를 수정하지 마라. 이유: 작업 의도와 무관한 변경이 PR에 섞이면 review 비용 증가.
- `length` 외의 다른 `@Column` 속성을 변경하지 마라. 이유: nullable/unique 변경은 schema 의미 변경이라 별도 검토가 필요.
- 운영 DB ALTER용 SQL 마이그레이션 스크립트를 만들지 마라. 이유: Flyway 도입 시점에 일괄 처리하기로 결정됨 (ADR-018).
- 기존 테스트를 깨뜨리지 마라.
