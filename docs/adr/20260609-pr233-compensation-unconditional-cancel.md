# 보상 완료 가드(`hasCompletedPayment`)를 제거하고 보상 대상 pgPaymentId를 무조건 취소한다

- Status: accepted
- Date: 2026-06-09

## Context

이 결정은 보상 진행 여부를 완료 Payment 존재 여부로 판단하기로 한 기존 결정(→ PR#118)의 완료 가드 정책을 폐기하고 대체한다.

#230. 이중결제 보상을 adapter 도메인 예외 매핑 기반 fail-first 단일 경로로 통합한 결정(→ PR#226)으로 `PAYMENT_DUPLICATE`가 이 가드를 실제 통과하기 시작했다. 가드는 merchantPayKey 단위라, 결제 도메인 재설계(→ PR#205) 이후 한 merchantPayKey에 pgPaymentId가 여럿 가능해진 모델에서 보상 대상 pgPaymentId 자신이 아니라 형제 pgPaymentId의 성공만 잡아 중복 pgPaymentId의 PG 취소를 항상 잘못 skip → 환불 누락(이중청구)이 발생했다.

고려한 대안: 가드를 pgPaymentId 단위로 재정의(형제가 아닌 자기 pgPaymentId의 SUCCEEDED 여부로 판단) — 그러나 보상 진입 경로(verify 실패는 `saveApproved` 미도달, duplicate는 자기 `succeed`가 롤백)에서 보상 대상 pgPaymentId 자신은 SUCCEEDED로 커밋될 수 없어 항상 false인 dead 가드가 된다. 사용처 없는 코드를 남기지 않는 원칙에 따라 기각한다.

보상이 생성·조회하는 cancel payment는 항상 보상 대상 pgPaymentId(실패한 결제)로 만들어지므로 그 취소는 항상 올바르다. 형제 성공(`pgA`)은 별도 Payment row이고 보상이 건드리지 않아, 완료 가드 없이도 기존 완료 가드 결정(→ PR#118)의 원래 위험("성공한 결제를 잘못 취소")은 발생하지 않는다.

## Decision

`PaymentApprovalCompensationService.runPgCancel`에서 `hasCompletedPayment(merchantPayKey)` 완료 가드를 제거한다. duplicate·amount-mismatch 보상 모두 보상 대상 pgPaymentId를 무조건 PG 취소한다. 사용처가 사라진 `PaymentApprovalService.hasCompletedPayment`, `PaymentRepository.existsApproveSucceeded`(어댑터·JPA 구현 포함) 체인을 제거한다. `cancelPayment` 상태가 REQUESTED가 아니면 skip하는 멱등 안전망은 유지한다.

## Consequences

같은 reservation·다른 pgPaymentId 경합으로 발생한 `PAYMENT_DUPLICATE`에서 중복 pgPaymentId의 PG 취소가 실제 수행되어 이중청구가 해소된다. 보상 멱등성은 `cancelPayment` 상태 가드와 cancel payment 단위 멱등 처리에 의존한다. 형제 SUCCEEDED 상태에서 중복 pgPaymentId 취소가 수행됨을 `NaverPayServiceIntegrationTest` 통합 테스트로 검증한다.

연계: 보상 진행 여부 판단(→ PR#118)·보상 정책 책임 배치(→ PR#125), adapter 도메인 예외 매핑 결정(→ PR#226), #205, #226, #227, #230, PR #233.
