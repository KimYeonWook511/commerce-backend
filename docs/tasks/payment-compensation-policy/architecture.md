# 태스크 Architecture

## 개요

이 태스크는 `NaverPayApprovalService.failApproveAndCancelApprovedPayment`의 race 우선순위 판단 근거를 `PaymentAttempt.status`에서 `Payment` 엔티티 존재 여부로 변경하고, `completeVerifiedApproval`의 보상 분기를 의미별로 정리한다. `PaymentApprovalService`가 Payment Aggregate의 보상 가능 여부 판단을 encapsulate하는 단일 채널이 된다.

## 변경 대상

| 계층 | 파일 | 변경 내용 |
|---|---|---|
| application | `PaymentApprovalService` | `isCompensationRequired(String merchantPayKey): boolean` 추가 |
| PG adapter | `NaverPayApprovalService` | `failApproveAndCancelApprovedPayment` Payment 체크 추가, `completeVerifiedApproval` 보상 메서드 분리 |
| domain | `PaymentAttempt` | JavaDoc 호출 정책 명시 (코드 변경 없음) |
| test | `PaymentApprovalServiceConcurrencyTest` | race 시나리오 보강/안정화 |
| test | `NaverPayServiceConcurrencyTest` | cancel skip 시나리오 보강 |

## 설계 방향

### Payment Aggregate 소유권 존중

```
NaverPayApprovalService
  → PaymentApprovalService.isCompensationRequired(merchantPayKey)
       → paymentRepository.findByMerchantPayKey(merchantPayKey).isEmpty()
```

- `NaverPayApprovalService`가 `paymentRepository`를 직접 의존하지 않는다.
- `PaymentApprovalService`가 Payment Aggregate 존재 여부를 외부에 노출하는 단일 채널이다.
- 미래 Payment 도메인 분리 시 `isCompensationRequired`는 Payment 서비스 API로 자연 승격 가능하다.

### 보상 메서드 의미별 분리

```
completeVerifiedApproval
  catch (PaymentException ex)
    switch errorCode:
      PAYMENT_MERCHANT_KEY_MISMATCH → compensateMerchantKeyMismatch(attempt)
      PAYMENT_AMOUNT_MISMATCH       → compensateAmountMismatch(attempt, responseTotalAmount)
      PAYMENT_DUPLICATE             → compensateDuplicatePayment(attempt, ex)
      default                       → compensateUnexpected(attempt, ex, failCode, message)
  catch (CustomException ex)        → compensateUnexpected(attempt, ex, APPROVE_PROCESS_FAILED, ...)
  catch (Exception ex)              → compensateUnexpected(attempt, ex, APPROVE_PROCESS_FAILED, ...)
```

- `compensateMerchantKeyMismatch`: failApprove만, PG cancel 없음
- 나머지 보상 메서드: `isCompensationRequired` 체크 후 cancel 진행

## 데이터 흐름

### 변경 전 (attempt.status 기반, #114 결함)

```
failApproveAndCancelApprovedPayment
  failApproveAttemptIfRequested  ← attempt.status == REQUESTED이면 mark, 아니면 skip
  getOrCreateCancelAttempt
  processCancelRequest  ← attempt가 SUCCEEDED여도 여기까지 도달 (외부 정합성 손상)
```

### 변경 후 (Payment 존재 기반)

```
failApproveAndCancelApprovedPayment
  failApproveAttemptIfRequested  ← 동일
  isCompensationRequired(merchantPayKey)
    Payment 존재? → log.warn("Payment already completed...") + return  ← race 안전
    Payment 없음? → 하위 단계 진행
  getOrCreateCancelAttempt
  processCancelRequest
```

## 예외 및 실패 처리

| 시나리오 | 처리 방식 |
|---|---|
| Payment 이미 존재 (race, Thread B) | `isCompensationRequired` false → log.warn + return |
| MERCHANT_KEY_MISMATCH | `failApprove`만 호출, cancel 없음 |
| AMOUNT_MISMATCH, DUPLICATE, 예상치 못한 예외 | `isCompensationRequired` 체크 후 cancel |
| PG cancel 실패 | 기존 처리 유지 — log.warn, 1차 예외 전파 (ADR-013 패턴) |
| cancelAttempt 이미 SUCCEEDED/FAILED | getOrCreateCancelAttempt 반환 후 status 체크 skip (기존 동작 유지) |

## 테스트 포인트

1. Thread A가 Payment 완료 후 Thread B 보상 진입 → cancel skip (race 시나리오)
2. Payment 미존재 시 보상 cancel 정상 진행 (기존 동작 보존)
3. MERCHANT_KEY_MISMATCH → cancel 호출 없음
4. AMOUNT_MISMATCH → cancel 호출 있음
5. `PaymentApprovalServiceConcurrencyTest` flaky 안정화
6. `NaverPayServiceConcurrencyTest` race window cancel skip 케이스
