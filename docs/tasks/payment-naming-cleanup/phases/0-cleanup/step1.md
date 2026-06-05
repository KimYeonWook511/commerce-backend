# Step 1: verbize-reservation-methods

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/payment-naming-cleanup/prd.md`
- `/docs/tasks/payment-naming-cleanup/architecture.md`
- `/docs/tasks/payment-naming-cleanup/adr.md` (특히 ADR-1)
- `/docs/tasks/payment-naming-cleanup/api-spec.md`
- `/docs/tasks/payment-naming-cleanup/db-schema.md`
- `src/main/java/com/commerce/payment/domain/PaymentReservation.java`

## 작업

`PaymentReservation` 의 두 상태 전이 메서드를 동사형으로 rename한다 (ADR-1).

- `markUsed()` → `use()`
- `markExpired()` → `expire()`

요구사항:

1. `src/main/java/com/commerce/payment/domain/PaymentReservation.java` 에서 메서드 선언 2개를 rename한다.
   - **메서드 본문은 그대로 둔다.** `use()` 는 `status = USED` 와 `reservedKey = null` 를 함께 set하고, `expire()` 는 `status = EXPIRED` 와 `reservedKey = null` 를 함께 set한다. 이 NULL 트릭 캡슐화(상태+트릭 컬럼 동시 set)는 task의 핵심 보호 수단이므로 절대 분리하거나 제거하지 않는다.
   - 기존 주석(만료/무효 예약 회수 설명 등)은 삭제하지 않고 그대로 유지한다.
2. main·test 전 범위에서 호출부 `.markUsed(` → `.use(`, `.markExpired(` → `.expire(` 로 바꾼다.
   - 호출 예: `reservation.markUsed()` (예: `PaymentApprovalAttemptService.create`), Mockito stub/verify(`verify(...).markUsed()`, `doNothing()...` 등), 테스트 단언.
3. 식별자에 `markUsed`/`markExpired` 토큰이 포함된 테스트 메서드명·변수명도 해당 토큰만 함께 바꾼다 (예: `markUsedAndCreateAttempt` → `useAndCreateAttempt`). `Attempt` 부분은 Step 3에서 다루므로 이 step에서는 건드리지 않는다.

범위 밖(이 step에서 건드리지 않음): `attempt` 식별자, `markUnknown`, `succeed`/`fail`, 서비스 클래스명, 에러코드.

## Acceptance Criteria

```bash
./gradlew test
```

```bash
! rg -q "markUsed|markExpired" src/main/java src/test/java
```

## 검증 절차

1. `./gradlew test` 가 통과하는지 확인한다.
2. `rg -n "markUsed|markExpired" src/main/java src/test/java` 결과가 0건인지 확인한다.
3. `PaymentReservation.use()`/`expire()` 본문이 각각 상태 컬럼과 `reservedKey` 를 함께 set하는지 확인한다 (NULL 트릭 캡슐화 보존).
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `use()`/`expire()` 본문에서 상태 set과 `reservedKey = null` set을 분리하거나 한쪽을 제거하지 마라. 이유: MySQL NULL 트릭 unique 정합성이 한 메서드 안의 동시 set에 의존한다.
- `markUnknown`/`succeed`/`fail` 을 건드리지 마라. 이유: ADR-1에서 유지하기로 결정했다.
- `attempt` 식별자나 서비스 클래스명을 건드리지 마라. 이유: Step 3 범위다.
- 기존 주석을 삭제하지 마라.
- 기존 테스트를 깨뜨리지 마라.
