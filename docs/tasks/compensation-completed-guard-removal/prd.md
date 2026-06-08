# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `compensation-completed-guard-removal`

## 배경

- #227(PR #228) 작업 중 codex review(P1)로 발견한 이중청구 버그를 고친다.
- ADR-033(#226)에서 이중결제 보상을 cancel-first에서 fail-first 단일 경로(`compensateDuplicatePayment` → `runPgCancel`)로 통합하면서, `PAYMENT_DUPLICATE`가 실제로 이 경로로 유입되기 시작했다.
- `runPgCancel`의 `hasCompletedPayment(merchantPayKey)` 가드(ADR-014)는 **merchantPayKey 단위**라, 같은 merchantPayKey의 형제 결제가 성공하면 `true`가 되어 중복 결제의 PG 취소를 skip하고 FAILED 마킹만 남긴다 → 환불 안 됨 → 이중청구.
- 가드의 원래 의도(ADR-014/#114: 실제 성공한 결제를 보상이 잘못 취소하는 것 방지)는 "merchantPayKey = 결제 1건" 옛 모델 전제였다. `payment-order-redesign(#205)` 이후 한 merchantPayKey에 결제 여러 건(다른 pgPaymentId)이 가능해지며 전제가 깨졌다.

## 목표

- 같은 reservation·다른 pgPaymentId 경합으로 `PAYMENT_DUPLICATE`가 발생했을 때, 중복 pgPaymentId의 PG 취소가 실제로 수행되어 이중청구가 발생하지 않게 한다.

## 범위

- 포함 범위
  - `runPgCancel`에서 `hasCompletedPayment` 완료 가드를 제거해 보상 대상 pgPaymentId를 무조건 PG 취소한다.
  - 공용 `runPgCancel`을 공유하는 amount-mismatch 경로도 동일하게 가드 없이 동작하게 한다(같은 형제-성공 오탐 결함을 공유).
  - 가드 제거로 사용처가 사라지는 `hasCompletedPayment` / `existsApproveSucceeded` 메서드 체인을 정리한다.
  - ADR-014 가드의 현 모델 재정의를 ADR로 기록한다(supersede 표시).
- 제외 범위
  - 진입/예약 단계 중복 예방(별도 이슈).
  - 보상 APPROVE 상태 모델(별도 이슈).

## 주요 시나리오

- 같은 reservation(merchantPayKey)에 서로 다른 pgPaymentId(`pgA`, `pgB`) 승인이 USE 커밋 전 경합한다.
- `pgA` 성공(`approved_order_key=orderId`) → `pgB`는 `uk_payment_approved_order_key` 위반 → `PAYMENT_DUPLICATE` → 보상 진입.
- 보상은 `pgB`의 PG 취소를 무조건 수행한다. 형제 `pgA`의 성공 여부는 보상 대상 `pgB` 취소 판단에 관여하지 않는다.

## 요구사항

- duplicate 보상(`compensateDuplicatePayment`)은 완료 가드를 타지 않고 해당 pgPaymentId를 무조건 PG 취소한다.
- amount-mismatch 보상(`compensateAmountMismatch`)도 완료 가드 없이 동작한다.
- `cancelPayment.getStatus() != REQUESTED` skip은 멱등 안전망으로 유지한다.
- 같은 reservation·다른 pgPaymentId 경합으로 `PAYMENT_DUPLICATE`가 발생했을 때 중복 pgPaymentId의 CANCEL이 실제 수행됨을 통합 테스트로 검증한다.
- #118에서 추가된 보상 동시성 테스트가 회귀 없이 통과한다.

## 제약사항

- 돈 정합성에 직결되는 변경이므로 보상 대상 pgPaymentId가 실패한 결제임을 전제로 한 무조건 취소가 안전함을 ADR 근거로 명시한다.
- `./gradlew test integrationTest` 통과.
