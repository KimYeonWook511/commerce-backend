# 태스크 Architecture

## PaymentAttempt 라이프사이클

PaymentAttempt는 결제 승인/취소 시도의 상태를 기록한다. 동일 결제 키에 대해 APPROVE attempt 1건, CANCEL attempt 1건이 분리 저장된다.

```
PaymentAttempt 생성
  createApproveRequested(...) → type=APPROVE, status=REQUESTED
  createCancelRequested(...)  → type=CANCEL,  status=REQUESTED

상태 전이 (이번 작업으로 검증 추가)
  REQUESTED → SUCCEEDED  (markApproveSucceeded / markCancelSucceeded)
  REQUESTED → FAILED     (markApproveFailed    / markCancelFailed)

거부 (새 검증)
  SUCCEEDED → * : PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED (500)
  FAILED    → * : PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED (500)
  CANCEL attempt + markApprove* : PAYMENT_ATTEMPT_TYPE_MISMATCH (500)
  APPROVE attempt + markCancel* : PAYMENT_ATTEMPT_TYPE_MISMATCH (500)
```

## 호출 흐름

```
NaverPayController
  → NaverPayApprovalService.approve()
    → getOrCreateApproveAttempt()  ← 멱등 처리 (상태 무관 재사용)
    → processApproveAttempt()      ← 상태별 분기 (switch)
        case REQUESTED → processApproveRequest()
            → naverPayGateway.approve()
            → completeVerifiedApproval()
                → paymentApprovalService.completeApprovedPayment()
                    → succeedApproveAttempt() → markApproveSucceeded() ← 검증 추가
        case SUCCEEDED → processSucceededApproveAttempt() (mark 없음)
        case FAILED → throw PaymentException                           (mark 없음)
```

## race 시나리오

신규 검증이 trigger될 수 있는 유일한 실제 race 경로:

```
PaymentApprovalService.completeApprovedPayment (라인 35-65):
  1. order = findByMerchantPayKeyForUpdate
  2. completedPayment = validateCompletedPaymentOrThrow  ← PAYMENT_DUPLICATE 가능
  3. succeedApproveAttempt → markApproveSucceeded  ← 메모리상 SUCCEEDED
  4. order.completePayment()  ← race: 다른 트랜잭션이 먼저 INIT → 다른 상태로 변경 시 throw

NaverPayApprovalService.completeVerifiedApproval:
  catch (CustomException ex)
    → failApproveAndCancelApprovedPayment
      → failApprove → markApproveFailed
        → attempt 메모리상 SUCCEEDED → 새 검증 throw

회귀 방지 (이번 작업):
  failApproveAndCancelApprovedPayment 내 failApprove 호출을 try-catch로 감쌈
  → throw 발생해도 PG cancel은 무조건 진행
```

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java` | 신규 에러 코드 2개 (500) |
| `src/main/java/com/commerce/payment/domain/PaymentAttempt.java` | 4개 mark 메서드에 type + status 검증 |
| `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` | `failApproveAndCancelApprovedPayment` 내 `failApprove` try-catch 보호 |
| `src/test/java/com/commerce/payment/domain/PaymentAttemptTest.java` | 전이/type 위반 케이스 9개 추가 |
| `docs/ADR.md` | ADR-012 추가 |
