# Step 1: restore-payment-attempt-unique-constraint

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-attempt-unique-key-length/prd.md`
- `/docs/tasks/payment-attempt-unique-key-length/architecture.md`
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/docs/tasks/payment-attempt-unique-key-length/db-schema.md`
- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/adr.md` (특히 ADR-011, ADR-018)
- `/docs/db-schema.md`

이전 step에서 만들어진 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`PaymentAttempt` entity의 4개 컬럼에 `@Column(length=...)`를 명시한다.

| 컬럼 | 적용할 length |
|---|---|
| `merchantPayKey` | 64 |
| `paymentId` | 64 |
| `provider` (enum) | 32 |
| `type` (enum) | 32 |

세부 지시:

- `merchantPayKey`, `paymentId`는 기존 `@Column(nullable = false)`에 `length = 64`를 추가한다.
- `provider`, `type`은 기존 `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(nullable = false)` 조합 위에 `length = 32`를 추가한다.
- 기존 어노테이션 순서와 import 구성은 유지한다.
- `@UniqueConstraint`의 `columnNames` 정의는 변경하지 않는다.

ADR-018의 "enum length 미명시" 정책은 일반 enum 컬럼에 대해 유지되고, 본 변경은 multi-column unique constraint 대상 컬럼에 한정된 좁은 예외다.

## Acceptance Criteria

```bash
./gradlew concurrencyTest --tests "*NaverPayServiceConcurrencyTest*"
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `PaymentAttempt`의 4개 컬럼에 `length` 속성이 의도한 값(64/64/32/32)으로 들어갔는가?
   - 기존 unique constraint name(`uk_payment_attempt_merchant_pay_key_provider_payment_id_type`)이 유지되는가?
   - ADR-018 적용 대상의 다른 enum 컬럼들의 length 속성이 변경되지 않았는가?
   - `NaverPayServiceConcurrencyTest` 8개 케이스가 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 entity의 컬럼 length를 함께 수정하지 마라. 이유: 본 ADR은 multi-column unique 대상에 한정된 좁은 예외이고, ADR-018의 기본 정책 영향 범위 확장은 별도 결정이 필요하다.
- `@UniqueConstraint`의 `columnNames`를 변경하지 마라. 이유: 기존 unique 의미가 그대로여야 한다.
- 컬럼 type 자체(예: VARCHAR → CHAR)를 변경하지 마라. 이유: 본 task는 length만 좁히는 것이고, 타입 변경은 별도 결정이다.
- 기존 테스트 단언을 변경하지 마라. 이유: 본 step은 schema 복원만 담당하고, 테스트 단언 이중화는 step 3에서 처리한다.

## 비고

- 로컬 MySQL 볼륨(`./mysql-data-local`)에 기존 schema가 남아 있다면 wipe 후 재기동해야 새 schema가 적용된다. `dockerTest`/`concurrencyTest`는 매번 Testcontainer를 새로 띄워 자동으로 새 schema가 적용된다.
