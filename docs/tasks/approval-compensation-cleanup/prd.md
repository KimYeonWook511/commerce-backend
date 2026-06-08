# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `approval-compensation-cleanup`

## 배경

- `NaverPayApprovalService.completeVerifiedApproval`은 PG가 SUCCESS(승인=캡처 완료)를 응답한 **뒤** 호출되어, `verifyApprovedResponse`(merchantPayKey·금액 일치 검증) 통과 후 `succeedApproval`(payment.succeed + 주문 PAID)을 수행한다.
- 이 메서드의 catch 흐름이 예외 전략(ADR-011)·완료 우선·보상 순서 일관성과 어긋난 점이 postprocess 정책 재설계(#221, PR #224) 코드 리뷰에서 식별됐다(리뷰 #1: 정상결제 환불, 리뷰 #3: 보상 cancel-first 순서).

## 목표

- 정상 승인된 결제(PG SUCCESS + verify 통과)인데 DB 기록만 transient하게 실패한 건을, 환불·FAILED로 박제하지 않고 배치 reconcile이 self-heal하도록 **완료 우선**으로 정리한다.
- 모르는 예외를 한 status(FAILED+환불)로 default하지 않고 **전파(500)**해 "완료가 맞는 상황 / FAILED가 맞는 상황 / 버그"를 구분 가능하게 한다.
- 이중결제 탐지를 application의 raw DAO 예외 직접 catch(ADR-011 위반)에서 **adapter 도메인 예외 매핑**으로 전환하고, 갈라진 이중결제 보상을 fail-first 단일 경로로 통일한다.

## 범위

- 포함 범위
  - `NaverPayApprovalService.completeVerifiedApproval`의 catch 흐름 재정리.
  - `PaymentRepository` / `PaymentRepositoryAdapter`에 succeed-approve 전용 저장 경로 추가 + `uk_payment_approved_order_key` 위반 → `PaymentException(PAYMENT_DUPLICATE)` 매핑.
  - `PaymentApprovalCompensationService`의 이중결제 보상 단일화(`compensateDuplicateApproval` 제거), `compensateUnexpected` 제거.
  - 관련 단위·통합·동시성 테스트 갱신.
- 제외 범위
  - postprocess 배치 reconcile 정책 자체(#221/#208 범위). 본 task는 실시간 경로가 reconcile에 "넘길 상태(REQUESTED)"를 올바르게 남기는 것까지만 책임진다.
  - DB 스키마·API 외부 계약 변경 없음.

## 주요 시나리오

- 정상 승인 후 `succeedApproval`이 transient(데드락 등)하게 실패 → 환불 없이 예외 전파, approve `REQUESTED` 유지 → 배치 `APPROVE_RECONCILE` + `PG_APPROVED`로 완료 self-heal.
- 모르는 예외(버그) 발생 → 환불·FAILED 둔갑 없이 전파(500)로 가시화.
- 명시적 비정상(`MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`) → *틀린 결제*라 현행(환불) 유지.
- 동시 두 승인(같은 orderId, 다른 pgPaymentId) → 하나만 SUCCEEDED, 나머지는 adapter가 `uk_payment_approved_order_key` 위반을 `PAYMENT_DUPLICATE`로 매핑 → fail-first 단일 경로로 보상.

## 요구사항

- `compensateUnexpected` 환불 제거: unmapped `PaymentException` default / `CustomException` / 일반 `Exception`은 보상 없이 전파, approve `REQUESTED` 유지.
- `MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`는 현행 보상(환불) 유지.
- application의 raw `catch(DataIntegrityViolationException)` 제거.
- adapter succeed-approve 전용 메서드가 `saveAndFlush` 위반의 cause에서 Hibernate `ConstraintViolationException.getConstraintName()`이 `uk_payment_approved_order_key`일 때만 `PaymentException(PAYMENT_DUPLICATE)`로 매핑하고, 그 외 무결성 위반은 원 예외 그대로 전파.
- 이중결제 보상은 fail-first 단일 경로(`compensateDuplicatePayment`)로 통일하고, cancel-first 경로(`compensateDuplicateApproval`)는 제거.

## 제약사항

- 금전 정합성 우선: 정상 매출을 transient 실패로 취소·환불하지 않는다. 희박한 경합/타이밍도 안전하게 다룬다.
- `saveAndFlush`의 조기 flush가 이중결제 위반을 adapter 호출 안에서 확정하는 load-bearing 의존성이다. 이 flush 타이밍을 깨지 않는다.
- ADR-011(app/adapter raw DAO 예외 직접 의존 금지)의 try-save-catch carve-out(adapter에서 인프라 예외를 도메인 예외로 번역) 범위 안에서만 매핑한다.
