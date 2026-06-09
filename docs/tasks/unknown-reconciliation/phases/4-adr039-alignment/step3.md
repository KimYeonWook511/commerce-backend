# Step 3: terminal-order-not-completable

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L4, ADR-L9 — order==null/PAID 종착)
- 루트 `/docs/adr.md`의 **ADR-039**

변경 대상:

- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (`handleOrderNotCompletable`)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`existsApprovedByOrderId`)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval` 재사용), `PaymentApprovalCompensationService` (`compensateCanceledOrderApproval`, step 2에서 FAILED+failCode로 정리됨)
- `/src/main/java/com/commerce/payment/domain/PaymentFailCode.java` (필요 시 신규 failCode)
- 영향받는 테스트: `PaymentReconciliationServiceTest`, `PaymentReconciliationIntegrationTest`

## 작업

대사 중 `succeedApproval`이 `Order.completePayment()` 거부(`ORDER_PAID_NOT_ALLOWED`)로 실패할 때, 현재 `handleOrderNotCompletable`이 `order == null`·`PAID` 등을 `SKIPPED`로 흘려 **무한 재시도**가 되는 문제를 종착 처리한다(PR #237 review [3]).

`handleOrderNotCompletable`을 다음과 같이 분기한다:

- **`order == null`** (주문 자체가 없음 — 정합성 깨짐): ERROR 로그 + payment를 종착시킨다(`FAILED` + 적절한 failCode). 무한 재시도하지 않는다. 드문 케이스다.
- **`order.status == CANCELED`**: 기존대로 `compensateCanceledOrderApproval`(step 2에서 FAILED+failCode로 정리된 보상)을 호출한다.
- **`order.status == PAID`**: `existsApprovedByOrderId(orderId)`로 판별한다.
  - 다른 `SUCCEEDED` APPROVE가 이미 있으면 → 이 건은 **중복 결제**다. 보상(환불) + `FAILED` + failCode(중복)로 종착시킨다.
  - 없으면 → 이 건이 그 주문을 PAID로 만든 **성공 주체**인데 우리 기록만 미확정인 상황이다. payment를 `SUCCEEDED`로 맞춰 종착시킨다(`succeedApproval`의 멱등 흡수 경로 활용 가능).
- **그 외 비-INIT 상태**(RECEIVED/COMPLETED 등): 안전하게 종착(로그 + 미처리) — 단 무한 재시도는 막는다.

핵심: 어떤 경로든 **다음 대사 주기에 같은 건이 다시 자동 대사 대상이 되지 않도록** 종착 상태(`SUCCEEDED`/`FAILED`)로 전이시킨다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `order == null` → 종착(FAILED) + ERROR 로그, 무한 재시도 없음.
   - `PAID` + 다른 SUCCEEDED 존재 → 중복으로 보상(FAILED+failCode), 없으면 SUCCEEDED 맞춤.
   - `CANCELED` → step 2의 FAILED+failCode 보상.
   - 모든 경로가 종착 상태로 끝나 재스캔되지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 비-INIT 주문 상태를 `SKIPPED`로만 두지 마라. 이유: 상태가 안 바뀌면 매 주기 재스캔되어 무한 재시도·PG 조회 낭비가 된다(review [3]).
- `PAID` 건을 무조건 환불하지 마라. 이유: 그 주문을 성공시킨 정당한 결제(성공 주체)를 오환불하면 새 돈 사고가 된다. `existsApprovedByOrderId`로 중복 여부를 먼저 판별한다.
- 새 status를 도입하지 마라(ADR-039). 종착은 기존 `SUCCEEDED`/`FAILED`+failCode로 표현한다.
- 기존 테스트를 깨뜨리지 마라.
