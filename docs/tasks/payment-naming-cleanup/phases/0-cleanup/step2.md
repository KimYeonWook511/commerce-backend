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
2. `PaymentTest.java` 의 `succeed_whenApproveType_updateStatusAndSetApprovedOrderKey` 는 `succeed()` 후 `getFailCode()`/`getFailDetail()` 가 null 임을 단언한다(현 33-34행). 이 단언은 **검토 완료**됐다: REQUESTED 로 생성된 결제(failCode 미설정)가 성공 후에도 failCode 가 없다는 **불변식**을 검증하는 것이지 리셋 동작을 검증하는 게 아니다. dead code 제거 후에도 그대로 통과하므로 **이 단언은 그대로 유지**한다(수정·삭제 금지). 별도로, failCode 를 미리 set한 뒤 succeed() 가 그것을 리셋함을 단언하는 테스트가 *새로* 발견될 때만 `blocked` 로 보고한다(현재 그런 테스트는 없음).

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
- `PaymentTest` 의 `succeed_whenApproveType_updateStatusAndSetApprovedOrderKey` 의 failCode/failDetail null 단언(33-34행)을 제거·수정하지 마라. 이유: 검토 완료된 유효 불변식이며 dead code 제거 후에도 통과한다.
- 기존 테스트를 깨뜨리지 마라.
