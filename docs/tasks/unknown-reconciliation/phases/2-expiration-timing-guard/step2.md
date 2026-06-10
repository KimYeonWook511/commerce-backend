# Step 2: reconciliation-canceled-compensation (C)

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L4, L6)

phase 1에서 만든 대사 흐름과 재사용 대상:

- `src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (phase 1 step 3: `APPROVED_PAYMENT_PROCESS`에서 주문 CANCELED 시 WARN 후 skip해 둔 자리)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval`: completePayment 거부 시 `OrderException(ORDER_PAID_NOT_ALLOWED)`로 롤백)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (`runPgCancel`: `PgCanceller`로 PG 취소 + cancel 기록)
- `/src/main/java/com/commerce/payment/application/port/PgCanceller.java`
- `/src/main/java/com/commerce/order/domain/Order.java` (`completePayment` = INIT만 허용), `/src/main/java/com/commerce/order/exception/OrderErrorCode.java`
- 외부 연동 port 컨벤션: `/docs/architecture.md` (`application/port/` 인터페이스로만 의존)

## 작업

A(원천 차단)가 뚫리는 극단 경합 — **이미 CANCELED된 주문의 UNKNOWN 결제가 대사에서 SUCCEEDED로 확정되는 경우** — 의 최후 안전망(C)을 구현한다 (ADR-L4). 또한 통지 추상화(ADR-L6)를 도입한다.

### 1. NotificationPort (ADR-L6)

- 외부 연동 port 컨벤션에 따라 `NotificationPort`(알림 추상화) 인터페이스를 정의한다. "운영자 확인이 필요한 사건"을 통지하는 메서드를 둔다(메시지/컨텍스트 전달).
- 구현은 **no-op(로그) adapter** 하나만 둔다(ERROR/WARN 로그로 남김). 디스코드/슬랙/메일 등 실제 채널 adapter는 이번 범위가 아니다(후속 이슈).
- 통지는 **commit 이후 best-effort**다. 통지 실패가 대사/보상 트랜잭션을 막지 않도록 한다(트랜잭션 커밋 후 호출, 예외는 삼켜 로그).

### 2. C — 대사 확정 시 주문 CANCELED 보상

- phase 1 step 3에서 `APPROVED_PAYMENT_PROCESS`가 `succeedApproval`을 호출하다 주문 CANCELED로 거부(`OrderException(ORDER_PAID_NOT_ALLOWED)`)되면 WARN 후 skip하던 자리를 보상 경로로 교체한다.
- 보상 경로:
  1. `succeedApproval` 호출은 트랜잭션 롤백되므로 approve 결제는 확정되지 않았고 PG에는 승인(돈 빠짐)만 남은 상태다. 주문 상태를 다시 확인해 `CANCELED`인 경우에만 보상으로 분기한다. (`CANCELED` 외 다른 비-INIT 상태, 예: 이미 `PAID`는 멱등 흡수/로그로 둔다)
  2. PG 보상 취소(환불)를 실행한다 — `PaymentApprovalCompensationService`에 이 케이스용 보상 메서드를 추가하고 기존 `runPgCancel`(PgCanceller로 PG 취소 + cancel 기록 생성)을 재사용한다. 대사 경로에서 쓸 `PgCanceller` 구현은 승인 경로(`NaverPayApprovalService`의 PG cancel)와 동일한 gateway 호출을 사용한다.
  3. approve 결제를 `MANUAL_REVIEW`로 승급한다(자동 환불했더라도 사람이 확인할 사건).
  4. `NotificationPort`로 통지한다(주문/결제 식별자, 사유 포함).
- 보상 취소 자체가 실패(PG cancel FAILED/UNKNOWN)해도 `MANUAL_REVIEW` + 통지로 운영 개입에 위임한다. cancel 기록은 UNKNOWN 보존되어 후속 재처리(Epic #208 #3) 대상으로 남는다.
- 멱등: 같은 건을 두 번 대사해도 이중 환불이 일어나지 않도록 cancel 기록 존재/상태를 확인한다(`runPgCancel`의 `getOrCreate` + REQUESTED 가드 재사용).

### 3. MANUAL_REVIEW 승급 통지 연결

- phase 1 step 3에서 ERROR 로그만 남기던 MANUAL_REVIEW 승급(escalation) 지점에 `NotificationPort` 통지를 연결한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 만료-취소-후-지연-승인 시나리오 검증 테스트를 확인한다.
   ```bash
   rg "Reconciliation|completePayment|ORDER_PAID_NOT_ALLOWED" src/test/java
   ```
3. 아래를 확인한다.
   - 이미 CANCELED된 주문의 UNKNOWN 결제가 SUCCEEDED로 확정될 때, succeed로 종결하지 않고 보상 취소(환불) + MANUAL_REVIEW + 통지가 수행되는가?
   - 돈/주문/재고 정합성이 보장되는가(돈은 환불, 주문은 취소 유지)?
   - 같은 건 2회 대사 시 이중 환불이 없는가?
   - 보상 취소 실패 시 MANUAL_REVIEW + 통지로 위임되고 cancel 기록이 UNKNOWN 보존되는가?
   - 통지 실패가 대사/보상 트랜잭션을 막지 않는가(commit 이후 best-effort)?
   - escalation MANUAL_REVIEW 승급에도 통지가 연결됐는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 통지를 트랜잭션 안에서 동기 필수 단계로 만들지 마라. 이유: 진실 원천은 MANUAL_REVIEW 상태이고 통지는 best-effort push다. 전송 실패가 정합성 처리를 막으면 안 된다 (ADR-L6).
- 디스코드/슬랙/메일 등 실제 채널 adapter를 구현하지 마라. 이유: 이번은 `NotificationPort` + no-op까지이며 채널 연동은 후속 이슈다 (ADR-L6).
- 보상 취소를 새 PG 호출 로직으로 재작성하지 마라. 이유: 검증된 기존 `runPgCancel`/`PgCanceller`를 재사용해 신규 위험을 줄인다 (ADR-L4).
- CANCELED 외 상태(이미 PAID 등)를 환불하지 마라. 이유: 정상 완료 건을 오환불하면 새로운 돈 사고가 된다.
- 기존 테스트를 깨뜨리지 마라.
