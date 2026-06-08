# Task 아키텍처

> 이 문서는 이번 Task 시점의 **변경 제안 스냅샷**이다.
> 시스템의 현재 진실은 루트 `docs/architecture.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다.

---

## 개요

- 결제 승인 완료(`completeVerifiedApproval`)의 예외·보상 책임을 정리한다. 이중결제 탐지 책임이 application의 raw 인프라 예외 catch에서 infrastructure adapter의 도메인 예외 매핑으로 이동한다.

## 변경 대상

- presentation/application(naverpay): `NaverPayApprovalService.completeVerifiedApproval` catch 흐름.
- application(payment): `PaymentApprovalCompensationService`(이중결제 보상 단일화, `compensateUnexpected` 제거), `PaymentApprovalService.succeedApproval`(전용 저장 경로 호출).
- domain repository port: `PaymentRepository`(succeed-approve 전용 저장 메서드 추가).
- infrastructure: `PaymentRepositoryAdapter`(전용 메서드에서 `uk_payment_approved_order_key` 위반 → `PaymentException(PAYMENT_DUPLICATE)` 매핑).

## 설계 방향

- **계층 책임 정렬(ADR-011 carve-out)**: 인프라 예외(`DataIntegrityViolationException`/Hibernate `ConstraintViolationException`)는 infrastructure adapter 안에서 도메인 예외(`PaymentException`)로 번역하고, application은 도메인 예외만 본다.
- **매핑 범위 한정**: succeed-approve 전용 메서드 + constraint name 확인의 이중 한정으로, 다른 무결성 위반을 `PAYMENT_DUPLICATE`로 오매핑하지 않는다. 범용 `save()`는 매핑하지 않는다.
- **완료 우선**: 정상 승인 후 transient 기록 실패는 보상하지 않고 `REQUESTED`로 두어 배치 reconcile에 위임한다. 실시간은 "완료 또는 흔적 남김"까지만 책임진다.

## 데이터 흐름

- approve(SUCCESS) → `verifyApprovedResponse` → `succeedApproval`(payment.succeed + order PAID, 한 트랜잭션).
  - `succeedApproval` 내부의 payment 저장이 succeed-approve 전용 경로(`saveAndFlush`)를 탄다.
  - 같은 orderId에 이미 SUCCEEDED APPROVE 행이 있으면 `uk_payment_approved_order_key` 위반 → adapter가 `PaymentException(PAYMENT_DUPLICATE)` 던짐 → 트랜잭션 롤백 → application `case PAYMENT_DUPLICATE` → `compensateDuplicatePayment`(fail-first: approve FAILED 마킹 후 PG cancel).
  - 키/금액 불일치 → `MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH` 보상(현행 환불 유지).
  - 그 외 unmapped 예외 → 보상 없이 전파(500), approve `REQUESTED` 유지.

## 예외 및 실패 처리

- `PAYMENT_DUPLICATE`: adapter 매핑 → fail-first 보상 → `PAYMENT_DUPLICATE` 전파(409).
- `MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`: 틀린 결제 → 환불 보상 후 전파.
- unmapped `PaymentException`/`CustomException`/`Exception`: 보상 없이 전파(500). approve `REQUESTED` → reconcile self-heal.

## 테스트 포인트

- transient 실패 시 환불 미발생 + approve `REQUESTED` 유지.
- unmapped 예외가 UNKNOWN/FAILED로 둔갑하지 않고 전파(500).
- 동시 두 승인(같은 orderId): 하나만 SUCCEEDED, 나머지는 `PAYMENT_DUPLICATE`로 fail-first 보상. application이 raw DAO 예외에 의존하지 않음.
- adapter 매핑이 succeed-approve 경로 + `uk_payment_approved_order_key`에 한정 — 다른 무결성 위반은 원 예외 전파(오매핑 없음).
- 이중결제 보상이 fail-first 단일 경로로 통일되어 "approve REQUESTED + cancel" 잔여가 안 생김.
