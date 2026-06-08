# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.
> 결정이 없으면 이 파일은 헤더만 두고 비워둔다.
> 탐색만 하고 채택하지 않은 안은 별도 레코드로 만들지 않고, 채택된 결정의 `고려한 대안`에 적는다.

---

## ADR-L1: 보상 완료 가드(`hasCompletedPayment`)를 제거하고 보상 대상 pgPaymentId를 무조건 취소한다

- 상태: accepted
- supersedes: ADR-014 (보상 진행 여부를 완료 Payment 존재로 판단)
- superseded-by: 없음

### 배경

- ADR-014는 보상 PG 취소 전 `hasCompletedPayment(merchantPayKey)`로 완료 Payment 존재 여부를 확인해, 이미 완료됐으면 취소를 skip했다. 원래 의도는 race window에서 실제 성공한 결제를 보상이 잘못 취소하는 것(#114)을 막는 것이었고, 이는 "merchantPayKey = 결제 1건" 옛 모델 전제 위에서 성립했다.
- `payment-order-redesign(#205)` 이후 한 merchantPayKey에 서로 다른 pgPaymentId의 결제 여러 건이 존재할 수 있다. 가드는 merchantPayKey 단위라, 보상 대상 pgPaymentId 자신이 아니라 형제 pgPaymentId의 성공을 잡는다.
- ADR-033(#226)에서 이중결제 보상이 fail-first 단일 경로(`compensateDuplicatePayment` → `runPgCancel`)로 통합되며 `PAYMENT_DUPLICATE`가 이 가드를 실제로 통과하기 시작했고, 형제 성공으로 가드가 항상 발동해 중복 pgPaymentId의 PG 취소를 skip → 이중청구가 드러났다.

### 고려한 대안

- **가드를 pgPaymentId 단위로 재정의**: 형제가 아닌 자기 pgPaymentId의 SUCCEEDED 여부로 판단하도록 시그니처를 좁힌다. 그러나 보상 진입 경로(verify 실패는 `saveApproved` 미도달, duplicate는 자기 `succeed`가 롤백)에서 보상 대상 pgPaymentId 자신은 SUCCEEDED로 커밋될 수 없어 가드가 항상 false인 실질 dead 코드가 된다. 사용처 없는 가드를 남기지 않는 원칙에 따라 기각한다.

### 결정 내용

- `runPgCancel`에서 `hasCompletedPayment` 완료 가드를 제거한다. duplicate·amount-mismatch 보상 모두 보상 대상 pgPaymentId를 무조건 PG 취소한다.
- 가드 제거로 사용처가 사라지는 `PaymentApprovalService.hasCompletedPayment`, `PaymentRepository.existsApproveSucceeded`(및 어댑터/JPA 구현)를 함께 제거한다.
- `cancelPayment.getStatus() != REQUESTED` skip은 멱등 안전망으로 유지한다(이미 처리된 cancel 재취소 방지).

### 근거

- 보상이 생성/조회하는 cancel payment는 항상 보상 대상 pgPaymentId로 만들어지며, 그 pgPaymentId는 실패한 결제(verify 불일치 또는 `uk_payment_approved_order_key` 위반으로 롤백)다. 따라서 그 pgPaymentId를 취소하는 것은 항상 올바르다.
- 형제 성공(`pgA`)은 별도 Payment row이고 보상이 건드리지 않으므로, 완료 가드 없이도 "성공한 결제를 잘못 취소"하는 ADR-014의 원래 위험은 발생하지 않는다.
- 보상 대상 pgPaymentId 자신이 SUCCEEDED로 커밋될 수 있는 경로가 없어, 완료 가드는 새 모델에서 형제 성공만 오탐하는 순손해다.

### 결과

- 같은 reservation·다른 pgPaymentId 경합으로 발생한 `PAYMENT_DUPLICATE`에서 중복 pgPaymentId의 PG 취소가 실제 수행되어 이중청구가 해소된다.
- ADR-014의 완료 가드 정책은 폐기되고, 보상 멱등성은 `cancelPayment` 상태 가드와 cancel payment 단위 멱등 처리에 의존한다.
