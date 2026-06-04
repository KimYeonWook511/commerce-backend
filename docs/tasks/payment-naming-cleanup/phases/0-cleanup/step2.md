# Step 2: remove-succeed-dead-reset

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/payment-naming-cleanup/prd.md`
- `/docs/tasks/payment-naming-cleanup/adr.md` (특히 ADR-3)
- `src/main/java/com/commerce/payment/domain/Payment.java`
- Step 1에서 수정된 `src/main/java/com/commerce/payment/domain/PaymentReservation.java`

## 작업

`Payment.succeed(LocalDateTime respondedAt)` 안의 dead code 2줄을 제거한다 (ADR-3).

제거 대상:

```java
this.failCode = null;
this.failDetail = null;
```

근거: `succeed()` 는 시작부의 가드 `if (this.status != PaymentStatus.REQUESTED) throw ...` 때문에 REQUESTED 상태에서만 실행된다. REQUESTED 결제는 `failCode`/`failDetail` 이 설정될 경로가 없다(`fail()`/`markUnknown()` 만 설정하며 둘 다 REQUESTED 를 벗어난다). 따라서 이 null 리셋은 증명 가능한 no-op이고 append-only 모델에서 불필요한 mutation이다.

요구사항:

1. `succeed()` 에서 위 2줄만 제거한다. 나머지 본문(`status = SUCCEEDED`, APPROVE 타입의 `approvedOrderKey = orderId` set, `respondedAt` set)은 그대로 둔다.
2. `succeed()` 호출 후 `failCode`/`failDetail` 가 null 로 "리셋됨" 을 단언하는 테스트가 있는지 확인한다. 있다면 그 단언은 dead behavior를 검증하는 것이므로, 해당 단언을 제거하지 말고 **사용자에게 보고**한다(이 step을 `blocked` 로 두고 사유 기재). 임의로 테스트를 수정하지 않는다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. `./gradlew test` 가 통과하는지 확인한다.
2. `Payment.succeed()` 본문에 `failCode = null` / `failDetail = null` 이 없는지 확인한다.
3. `fail()`/`markUnknown()` 의 `failCode`/`failDetail` set은 그대로 남아 있는지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `fail()`/`markUnknown()` 의 `failCode`/`failDetail` set을 건드리지 마라. 이유: 그쪽은 실제 상태 기록이다.
- `succeed()` 의 상태 전이 가드나 `approvedOrderKey` set을 건드리지 마라. 이유: NULL 트릭 방어선과 무관한 dead code 2줄만 제거 대상이다.
- null 리셋을 검증하는 테스트가 있으면 임의로 고치지 말고 사용자에게 보고하라. 이유: 동작 의미를 사용자와 확인해야 한다.
- 기존 테스트를 깨뜨리지 마라.
