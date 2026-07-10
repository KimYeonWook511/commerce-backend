# 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다

- Status: superseded by [20260609-pr233-compensation-unconditional-cancel](20260609-pr233-compensation-unconditional-cancel.md)
- Date: 2026-05-20

## Context

기존 구조는 `PaymentAttempt.status`로 보상 진행 여부를 판단했으나 attempt에 lock이 없어 race window에서 SUCCEEDED attempt에 cancel이 호출되는 결함(#114)이 있었다.

Payment는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique 제약이 있고 `completeApprovedPayment`가 Order FOR UPDATE 안에서 저장하므로 race-safe하다. DDD 관점에서 Payment Aggregate의 불변식을 cross-Aggregate 협력으로 활용한다. `hasCompletedPayment`는 Payment 도메인의 사실 조회로 표현되어 미래 Payment 도메인 분리 시 외부 API 경로가 자연스럽게 보존된다.

## Decision

보상 흐름은 PG cancel 진행 전 `PaymentApprovalService.hasCompletedPayment(merchantPayKey)`로 완료된 Payment row 존재 여부를 확인해 이미 존재하면 cancel을 skip한다.

- **PaymentAttempt Aggregate 캡슐화**: `PaymentAttempt.succeed`/`fail` 메서드는 `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService` 외부에서 직접 호출하지 않는다. 정책 강제는 코드가 아닌 ADR과 JavaDoc으로만 명시하며, ArchUnit 도입은 별도 후속 작업으로 분리한다.

## Consequences

Payment 조회 1회 추가되나 인덱스 조회라 성능 영향 미미하다.

- **후속 (payment-compensation-to-domain task)**: 보상 owner가 `NaverPayApprovalService.failApproveAndCancelApprovedPayment`에서 payment.application의 `PaymentApprovalCompensationService.runPgCancel`로 이동했다(→ PR#125). 정책 자체(Payment 존재 체크 → cancel skip)는 동일하게 유지된다.
- **후속 (#182, 2026-06-02)**: 메서드 이름과 의미를 도메인 사실 조회(`hasCompletedPayment`)로 정리하고, 내부 구현은 `existsByMerchantPayKeyAndStatus(merchantPayKey, COMPLETED)`로 status까지 명시해 의미와 본문을 정확히 일치시켰다. 보상 service의 호출 코드(`if (hasCompletedPayment) skip`)가 정책 적용을 담당한다. "row 존재 = 결제 완료"의 근거(merchantPayKey unique + Order FOR UPDATE 안에서 저장)는 Payment 도메인 소유 지식이므로 사실 조회를 소유자에 박아 두면 Payment 정의 변경 시 한 곳만 갱신하면 된다.
- **후속 (payment-order-redesign)**: 두 테이블 분리 모델에서 *Payment row 존재 = 결제 완료* 사실 조회가 재정의됐다. 구현은 `existsApproveSucceeded(merchantPayKey)` — `tbl_payment` 에서 `type=APPROVE ∧ status=SUCCEEDED` 인 행 존재. 메서드 의미(`hasCompletedPayment`)와 정책(cancel skip 판단)은 동일하게 유지된다. 세부 결정은 `docs/tasks/payment-order-redesign/adr.md` 참조.
- **supersede 사유 (compensation-completed-guard-removal task, → PR#233)**: payment-order-redesign 이후 한 merchantPayKey 에 pgPaymentId 가 여럿 가능해지면서 merchantPayKey 단위 가드가 보상 대상 자신이 아니라 형제 성공만 오탐해 중복 pgPaymentId 의 PG 취소를 잘못 skip(이중청구)했고, 보상 대상 pgPaymentId 자신은 SUCCEEDED 로 커밋될 수 없어 가드가 실질 무용했다. 가드와 `hasCompletedPayment`/`existsApproveSucceeded` 체인은 제거됐다.
