# Step 3: reconciliation-service

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L1, L7)

step 1에서 main으로 승격된 정책:

- `src/main/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java` / `PaymentPostProcessTarget.java`
- `src/main/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlowPolicy.java` / `PaymentPostProcessFlow.java` / `PaymentVerificationStatus.java`

step 2에서 확장된 도메인 계약(이 step이 사용):

- `Payment.succeed`/`fail`(REQUESTED 또는 UNKNOWN 전제), `MANUAL_REVIEW` 종착 전이, `PaymentStatus.MANUAL_REVIEW`

재사용·확인 대상:

- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval`: payment.succeed + order.completePayment, order 행 락, SUCCEEDED 멱등 흡수)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` / `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `/src/main/java/com/commerce/payment/naverpay/application/port/NaverPayGateway.java` 와 `getApprovalHistory` 반환 타입 `NaverPayHistoryResult`

## 작업

stale UNKNOWN/REQUESTED **APPROVE** 결제를 PG 조회로 확정하는 대사 서비스를 구현한다. 정책(step 1)과 도메인 전이(step 2)를 사용해 수집·PG조회·상태확정·Order 반영을 wiring한다.

### 1. 스캔 쿼리 (`PaymentRepository`)

- stale APPROVE 대사 후보를 조회하는 메서드를 추가한다. status가 `UNKNOWN` 또는 `REQUESTED`인 APPROVE 결제 중, 정책의 최단 진입 지연(`UNKNOWN_RECONCILE_DELAY` = 1분)을 넘긴 건을 넓게 긁는다. 한 주기 처리량을 제한하도록 limit(또는 페이징)을 받는다.
- 정밀 분기(escalation/stale 판정)는 정책이 내리므로, 쿼리는 후보를 넓게 가져오고 정책이 거른다.

### 2. 대사 서비스 (`payment.application.PaymentReconciliationService`)

- `reconcile()` 진입점: stale 후보를 조회하고 **건별로 처리**한다. PG 조회(외부 호출)는 트랜잭션 경계 밖에서 하고, 상태 확정은 건별 단건 트랜잭션으로 한다 (ADR-L1).
- 건별 처리:
  1. `targetPolicy.resolvePostProcessTarget(approvePayment, null, now)`로 target 결정. (이 step은 APPROVE 축만 다루므로 cancelPayment는 null)
  2. target별:
     - `APPROVE_RECONCILE`: `naverPayGateway.getApprovalHistory(pgPaymentId)` → 결과를 `PaymentVerificationStatus`로 매핑 → `flowPolicy.resolveFlow(APPROVE_RECONCILE, verificationStatus)` → flow 실행.
     - `MANUAL_REVIEW`: step 2의 종착 전이로 결제를 `MANUAL_REVIEW` 승급 + ERROR 로그. (운영자 통지는 phase 2 step 2에서 `NotificationPort`로 연결 — 이 step은 ERROR 로그까지)
     - `NONE`: 처리하지 않는다.
     - `CANCEL_RECONCILE` / `APPROVED_CANCEL_COMPENSATION`: **이번 범위 밖**. 처리하지 않고 디버그 로그만(후속 Epic #208 batch #3).
- flow 실행:
  - `APPROVED_PAYMENT_PROCESS`: 기존 `PaymentApprovalService.succeedApproval(approvePayment, now)`를 재사용해 payment SUCCEEDED 확정 + `Order.completePayment()` + 차단 해제. `succeedApproval`은 SUCCEEDED 멱등 흡수와 order 행 락을 이미 갖는다.
    - **주의(phase 경계)**: 주문이 이미 `CANCELED`라 `Order.completePayment()`가 `OrderException(ORDER_PAID_NOT_ALLOWED)`로 거부되는 경우, 이 step에서는 해당 건을 **건너뛰고 WARN 로그만** 남긴다. 보상 취소(환불)는 phase 2 step 2(C)에서 이 자리에 추가한다. 임의 보상 로직을 넣지 않는다.
  - `ALREADY_CANCELED_PAYMENT_PROCESS`: approve 결제를 `FAILED`(failCode `ALREADY_CANCELED`)로 확정해 차단을 해제한다.
  - `KEEP_WAITING`: 아무 것도 하지 않는다(다음 주기 재시도).
- `getApprovalHistory` 결과 → `PaymentVerificationStatus` 매핑: PG 승인 확인 → `PG_APPROVED`, PG 취소 확인 → `PG_CANCELED`, 결과 불명/처리중 → `PENDING`, 이력 없음 → `HISTORY_NOT_FOUND`. 결과 불명을 성공/실패로 단정하지 않는다.
- 멱등: 이미 `SUCCEEDED`/`FAILED`/`MANUAL_REVIEW`로 확정된 건이 후보에 섞여도 안전하게 흡수한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - UNKNOWN/stale REQUESTED → PG 조회 → SUCCEEDED 확정 + Order PAID + 차단 해제가 동작하는가?
   - PG 취소 확인 시 FAILED(ALREADY_CANCELED) 확정으로 차단이 풀리는가?
   - PENDING/HISTORY_NOT_FOUND가 KEEP_WAITING(미확정 유지)으로 처리되는가?
   - escalation(6시간) 초과가 MANUAL_REVIEW로 승급되는가?
   - 같은 건 2회 대사 시 이중 SUCCEEDED/이중 처리가 없는가?
   - 주문 CANCELED로 completePayment가 거부되는 건은 WARN 로그 후 건너뛰는가? (보상은 phase 2)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- PG 조회(외부 호출)를 DB 트랜잭션 안에서 호출하지 마라. 이유: 외부 호출 지연이 DB 커넥션·락을 점유한다 (ADR-L1).
- 결과 불명(PENDING)을 SUCCEEDED나 FAILED로 단정하지 마라. 이유: 시간이 아니라 PG 조회가 결론을 낸다(돈 박제·오확정 위험).
- 주문 CANCELED 케이스에 이 step에서 보상/환불 로직을 넣지 마라. 이유: C는 phase 2 step 2의 책임이며, 여기서 넣으면 phase 경계와 검증 단위가 무너진다.
- CANCEL_RECONCILE/APPROVED_CANCEL_COMPENSATION을 구현하지 마라. 이유: 보상 취소 실패 재처리는 후속 Epic #208 batch #3 범위다.
- 분산 락을 추가하지 마라. 이유: 멱등성으로 방어하며 분산 락은 후속이다 (ADR-L2).
- 기존 테스트를 깨뜨리지 마라.
