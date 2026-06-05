# hibernate-enum-jdbc-type-code 회고

## 1. 작업 요약

본 태스크는 Spring Boot 3.x(Hibernate 6.x) 환경에서 `@Enumerated(EnumType.STRING)`이 MySQL ENUM 타입으로 매핑되며 발생하는 조용한 결함을 회피하기 위해, 8개 entity의 14개 enum 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 부착하고 `@Column(length=N)`의 length 속성을 제거한 작업이다(`Member`, `Order`, `OutboxEvent`, `ProcessedEvent`, `Payment`, `PaymentAttempt`, `Product`, `StockHistory`). 동시에 `docs/adr.md`에 ADR-018을 신설해 신규 entity에도 동일 패턴을 적용하도록 컨벤션을 남겼다.

본 코드 변경은 신규 환경/test 환경에서 enum 컬럼이 MySQL ENUM이 아닌 VARCHAR(255)로 생성되도록 보장한다. 운영 DB의 기존 ENUM 컬럼 ALTER와 ENUM 시절에 조용히 삽입된 의심 row 점검은 본 태스크 범위 밖이며 Flyway 도입 시 후속 트랙으로 분리한다.

---

## 2. 이슈 출발점

이슈 #142 — outbox 계열 작업 중 `tbl_outbox_event`에 새 컬럼이 추가될 때 기존 row가 NOT NULL 제약 위반으로 떨어지지 않고 컬럼의 첫 번째 ENUM 값(예: `OutboxEventStatus.PENDING`, `OutboxAggregateType.ORDER`)이 자동 채워지는 현상을 관찰하면서 시작됐다. VARCHAR였다면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM 타입에서는 묻혀 있었다.

원인을 추적해 보니 Hibernate 6.x부터 MySQL dialect가 `@Enumerated(EnumType.STRING)`을 VARCHAR가 아닌 MySQL native ENUM 타입으로 매핑하도록 바뀐 것이 배경이었다. MySQL ENUM은 다음 두 가지 조용한 결함을 동시에 유발한다.

- INSERT 시 컬럼을 생략하면 첫 번째 ENUM 값이 default로 삽입되며, NOT NULL 제약도 통과한다.
- `ddl-auto: update`로 기존 테이블에 ENUM 컬럼을 추가하면 기존 row가 자동으로 첫 번째 ENUM 값으로 채워진다.

VARCHAR 시절이라면 즉시 드러났을 사고가 ENUM 매핑에서는 묻히는 구조였고, 본 태스크는 이 위험을 entity 전수 점검 + 매핑 패턴 정정으로 차단하는 것을 목표로 했다.

---

## 3. 초기 접근의 한계

PRD/architecture 초안 작성 단계에서 plan은 `@Column(columnDefinition = "VARCHAR(50)")` 방식이었다. Hibernate 공식 마이그레이션 가이드를 직접 확인하지 않고, "MySQL dialect가 ENUM으로 매핑한다면 컬럼 정의를 raw SQL로 강제하면 되겠다"는 일반적인 추론에 기반한 첫 안이었다.

이 안의 잠재적 부채는 다음과 같다.

- `columnDefinition`은 raw SQL fragment가 entity에 박혀 dialect 이식성이 사라진다.
- `@Column(length=N)`이 `columnDefinition` 앞에서 무시되므로 length 속성과 충돌한다.
- 향후 다른 DB(예: PostgreSQL native ENUM, H2 호환 모드)로 dialect를 바꿀 때 `columnDefinition` 자체가 모두 fragile해진다.

가이드를 먼저 확인했다면 첫 plan부터 정공법을 택할 수 있었던 부분이다.

---

## 4. 방향 전환 (`columnDefinition` → `@JdbcTypeCode`)

사용자가 Hibernate 6.5 공식 마이그레이션 가이드를 지적하며 `@JdbcTypeCode(SqlTypes.VARCHAR)` 방식의 존재를 짚었다. 두 방식을 비교해 보니 정공법이 명확했다.

| 기준 | `@JdbcTypeCode` | `columnDefinition` |
|---|---|---|
| dialect 이식성 | dialect-agnostic | raw SQL 박힘 |
| 의도 표현 | 선언적 (JDBC 타입 지정) | 절차적 (DDL fragment) |
| `length`와의 관계 | `@Column(length=N)` 분리 가능 | length 무시됨 |
| schema 비교 | Hibernate type system 기반 | 문자열 비교 (fragile) |
| 향후 native ENUM 채택 시 | annotation 1개 제거 | columnDefinition 제거 + length 재추가 |

`@JdbcTypeCode`는 JDBC 타입을 선언적으로 지정하므로 dialect가 바뀌어도 의미가 보존되고, `@Column`의 `length`/`nullable`/`unique` 속성을 자유롭게 병행할 수 있다. PRD/architecture/ADR을 모두 `@JdbcTypeCode` 기준으로 다시 정리하고 변경 패턴을 다음과 같이 확정했다.

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
@Column(nullable = false)
private OutboxEventStatus status;
```

---

## 5. length 명시 폐기

`columnDefinition` 안이 무산된 다음 단계로 "그렇다면 length는 어떻게 줄 것인가"가 논점이 됐다. 초기 검토안은 두 가지였다.

- (a) 기존에 명시되어 있던 도메인별 length 유지 (`OutboxEvent`의 경우 50/20/30)
- (b) enum 전체 50으로 통일

두 안 모두 폐기했다. 근거는 다음과 같다.

- enum 값은 개발자가 정의한 코드 상수만 저장된다. 외부 사용자 입력이 아니므로 length 제한이 보안/검증 의미를 갖지 않는다.
- VARCHAR는 가변 길이라 InnoDB 기준 저장 공간 차이도 실질 0이다.
- length를 명시하면 enum 값에 긴 이름이 추가될 때마다 entity와 DB 컬럼 폭을 동기화해야 한다. 보호 효과 없는 동기화 부담만 남는다.
- 운영 DB ALTER 마이그레이션 횟수만 늘어난다 (Flyway 도입 후 매번 length 변경 스크립트 발생).

결과적으로 length 명시 자체를 폐기하고 Hibernate 기본값(VARCHAR(255))을 사용하기로 결정했다. 기존 outbox 4개 필드(`OutboxEventType`/`OutboxEventStatus`/`OutboxAggregateType`/`ProcessedEventConsumerType`)에 박혀 있던 `length=20/30/50`이 모두 제거됐다.

---

## 6. 운영 DB 한계 인식

본 코드 변경만으로 운영 DB의 기존 ENUM 컬럼이 자동 ALTER되지 않을 가능성이 있다. Hibernate `ddl-auto: update`는 새 컬럼 추가는 자동 수행하지만, 기존 컬럼의 타입 변경(ENUM → VARCHAR)은 보장하지 않기 때문이다. 따라서 운영 DB의 기존 ENUM 컬럼은 본 PR 머지 후에도 여전히 ENUM 상태로 남아 있을 가능성이 높다.

이 한계는 ADR-018에 명시했고, 해결은 Flyway 도입 시 일괄 ALTER 마이그레이션 스크립트로 정리하기로 했다. 본 태스크에서 즉시 처리하지 않은 이유는 다음과 같다.

- 운영 DB에 직접 ALTER를 거는 작업은 본 PR의 범위(entity 매핑 정합화)와 책임이 다르다.
- Flyway가 아직 도입되지 않은 상태라 ALTER 스크립트만 단독으로 운영하면 추적 가능성과 idempotency가 약하다.
- entity 매핑이 정합화된 이후에 운영 DB ALTER를 일괄 정리하는 순서가 안전하다.

본 태스크는 "신규 환경에서는 더 이상 ENUM 컬럼이 만들어지지 않는다"는 보장까지만 책임진다.

---

## 7. 운영 데이터 무결성 점검 미수행

Hibernate가 ENUM 컬럼을 생성한 시점부터 본 fix 전까지, "첫 번째 ENUM 값이 조용히 삽입된" 의심 row가 운영 DB에 존재할 수 있다. 그러나 본 태스크에서는 이 점검을 수행하지 않았다. 이유는 다음과 같다.

- 의심 row는 컬럼별 비즈니스 맥락(어떤 값이 정상 default였는가, 어떤 row가 누락된 INSERT path를 거쳤는가)을 알아야 식별 가능하다. 자동 식별 로직을 entity 매핑 정합화와 한 트랙에 묶는 것은 책임이 과적된다.
- 점검 자체가 운영 DB 직접 SELECT/로그 분석을 요구하므로 코드 변경 PR로 다룰 성격이 아니다.
- 점검 결과 따라 데이터 보정 정책(어느 값으로 채울 것인가, 보정이 비즈니스적으로 안전한가)이 별도로 필요하다.

별도 후속 트랙으로 분리하며, 본 회고는 "점검이 필요하다"는 사실만 기록한다.

---

## 8. CLAUDE.md 미갱신 결정

본 태스크 진행 중 "신규 entity 작성 시 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다"는 컨벤션을 CLAUDE.md "구현 규칙"에 추가하자는 안이 제안됐다. 그러나 다음 이유로 채택하지 않았다.

- CLAUDE.md는 가볍게 유지하는 원칙(메모리 기록상)에 따라, 새 컨벤션을 함부로 추가하지 않는다.
- 신규 entity 컨벤션은 ADR-018의 "결정 내용"에 명시되어 있으며, ADR이 컨벤션의 1차 출처가 된다.
- CLAUDE.md에 컨벤션을 추가하면 ADR과의 동기화 부담이 생기고, 시간이 지나면 두 문서의 표현이 갈라질 위험이 있다.

결과적으로 신규 entity 컨벤션은 ADR-018에서만 관리한다.

---

## 9. 변경 범위 정리

### Production (entity 매핑만 수정)

| 파일 | 변경 내용 |
|---|---|
| `Member.java` | `role` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |
| `Order.java` | `status` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |
| `OutboxEvent.java` | `eventType`/`status`/`aggregateType` 3개 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 + 기존 length(50/20/30) 제거 |
| `ProcessedEvent.java` | `consumerType` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 + 기존 length 제거 |
| `Payment.java` | `status`/`provider` 2개 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |
| `PaymentAttempt.java` | `provider`/`type`/`status`/`failCode` 4개 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |
| `Product.java` | `status` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |
| `StockHistory.java` | `reason` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)` 부착 |

### Docs

| 파일 | 변경 내용 |
|---|---|
| `docs/adr.md` | ADR-018 (Hibernate 6.x ENUM 매핑 회피 결정) 추가 |
| `docs/tasks/hibernate-enum-jdbc-type-code/` | prd/architecture/adr/api-spec/db-schema/phases 신규 작성 |

---

## 10. 학습 포인트

### 공식 마이그레이션 가이드를 plan 시작 단계에서 먼저 확인했어야 한다

본 태스크의 가장 큰 학습은 "Hibernate dialect 변경이 의심되는 문제는 일반 추론으로 plan을 짜지 말고 공식 마이그레이션 가이드를 먼저 확인해야 한다"는 점이다. `columnDefinition` 안이 사용자 지적 없이 그대로 진행됐다면, 다음 부채가 남았을 것이다.

- entity별 `length=20/30/50` 불일치를 어떤 기준으로 통일할지 결정 비용 발생.
- raw SQL fragment가 14개 필드에 박혀 dialect 변경 시 모두 fragile.
- 향후 native ENUM을 다시 채택하려 할 때 14개 필드의 `columnDefinition`을 모두 다시 손봐야 함.

방향 전환은 다행히 구현 진입 전에 일어났지만, 첫 plan부터 공식 가이드 검토가 들어갔어야 했다.

### "length 명시 = 안전"이라는 직관은 enum에서는 성립하지 않는다

다른 컬럼에서는 length 명시가 사용자 입력 길이 제한이라는 명확한 보안/검증 의미를 갖는다. 그러나 enum 컬럼은 외부 입력이 아닌 코드 상수만 저장되므로 length 제한이 보호 효과를 주지 않고, 오히려 enum 값 추가 시 동기화 부담만 생긴다. "다른 컬럼 패턴을 enum에도 일관 적용"이라는 직관이 잘못된 일반화일 수 있다는 점을 확인했다.

### Hibernate dialect 변경은 "조용한" 결함을 만든다

ENUM 매핑은 NOT NULL 위반을 첫 번째 값 자동 삽입으로 회피하므로, 코드 레벨에서는 정상으로 보이지만 데이터 레벨에서는 의도하지 않은 값이 묻힌다. "테스트 통과 + 운영 무에러 = 안전"이 성립하지 않는 사례. dialect upgrade 시 단순 동작 확인이 아닌 매핑 결과 DDL을 직접 비교해야 한다.

### entity 매핑 변경과 운영 DB 적용은 트랙이 다르다

코드 변경만으로 운영 DB의 기존 컬럼 타입이 자동 ALTER되지 않는다는 한계를 명시적으로 인정하고, Flyway 도입 후속 트랙으로 분리한 결정이 적절했다. "코드를 고쳤으니 끝"이 아니라 "신규 환경 안전 + 운영 DB 후속 정리"가 두 단계로 책임이 나뉜다는 인식을 ADR-018에 남겼다.
