# 보상 정책은 payment.application 책임으로 두고, PG 어댑터는 cancel 콜백만 제공한다

- Status: accepted
- Date: 2026-05-20

## Context

보상 정책(어떤 실패 → cancel 필요/불필요, cancel reason, cancel amount)은 PG-agnostic 결제 도메인 책임이다. PG-specific한 부분은 cancel API 호출과 NaverPayCancelResult 응답 해석뿐이다. NaverPayApprovalService가 보상 정책을 내장하면 레이어 의존이 역전되고 PG 변경 시 정책 코드도 함께 영향받는다.

`PgCanceller` 좁은 콜백은 PaymentGateway port 완전 inversion(PG 둘 이상 추가 시)보다 지금 필요한 최소 구조만 도입한다. NaverPayApprovalService가 메서드 참조(`this::pgCancel`)로 구현하므로 인터페이스 추가 없이 의존 역전이 성립한다.

## Decision

`NaverPayApprovalService`에 있던 보상 dispatcher 4개와 공통 골격을 `PaymentApprovalCompensationService`(payment.application)로 이동한다. PG cancel 호출은 `PgCanceller` @FunctionalInterface 콜백으로 위임하고, PG 응답은 `CancelOutcome` record로 변환해 payment.application이 `NaverPayCancelResult`를 직접 import하지 않도록 한다.

- **트랜잭션 정책**: `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional` 없음. 보상 흐름의 단계별 독립 commit 을 유지하기 위해 각 단계(`failIfRequested`, `hasCompletedPayment`, `getOrCreate`, `succeed`/`fail`)가 자기 트랜잭션을 가진다. 단일 트랜잭션으로 묶이면 한 단계 실패가 이전 단계 진행까지 롤백시켜 부분 진행 보존이 불가능해진다.

## Consequences

PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치를 재설계해야 한다. 이때 PaymentGateway port 완전 inversion으로 자연 승격 가능하다.

- **후속 (payment-order-redesign)**: `compensateDuplicateApproval` 보상 dispatcher 가 추가됐다. `uk_payment_approved_order_key` (NULL 트릭 partial unique) 위반 시나리오 — 같은 orderId 에 두 번째 APPROVE 가 성공으로 진입한 경우 — 를 막고 PG cancel 로 환불한다. 책임 위치는 본 ADR 그대로 (`PaymentApprovalCompensationService`). 세부 결정은 `docs/tasks/payment-order-redesign/adr.md` 참조.
