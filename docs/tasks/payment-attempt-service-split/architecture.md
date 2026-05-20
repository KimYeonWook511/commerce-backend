# 태스크 Architecture

## 개요

이 태스크는 `PaymentAttempt` 도메인 메서드를 통합·정리하고 `PaymentAttemptService`를 흐름별로 분리한다.
보상 dispatcher는 task B(`payment-compensation-to-domain`)에서 처리하므로 이 task는 Service·도메인 정리만 담당한다.

## 변경 전 구조

```
payment.domain
  PaymentAttempt
    markApproveSucceeded(respondedAt)
    markApproveFailed(failCode, detail, respondedAt)
    markCancelSucceeded(respondedAt)
    markCancelFailed(failCode, detail, respondedAt)

payment.application
  PaymentAttemptService          ← 7개 메서드, APPROVE/CANCEL 혼재
  PaymentApprovalService

naverpay.application
  NaverPayApprovalService
    validateApprovedMerchantPayKeyOrThrow(attempt, key)  ← private
    validateApprovedAmountOrThrow(attempt, amount)        ← private
    + 보상 메서드들 (task B 범위)
```

## 변경 후 구조

```
payment.domain
  PaymentAttempt
    succeed(respondedAt)                              ← mark 4개 통합, status 가드 유지, type 가드 제거
    fail(failCode, detail, respondedAt)               ← 통합
    verifyApprovedResponse(merchantPayKey, total)     ← 신설, NaverPay validate 흡수
    createApproveRequested(...)                       ← 유지
    createCancelRequested(...)                        ← 유지

payment.application
  PaymentApprovalAttemptService     ← 신설 (4개 메서드)
    getOrCreate(key, provider, paymentId, amount)
    succeed(key, provider, paymentId, respondedAt)
    fail(key, provider, paymentId, failCode, detail, respondedAt)
    failIfRequested(key, provider, paymentId, failCode, detail, respondedAt)
  PaymentCancellationAttemptService ← 신설 (3개 메서드)
    getOrCreate(key, provider, paymentId, cancelAmount)
    succeed(key, provider, paymentId, respondedAt)
    fail(key, provider, paymentId, failCode, detail, respondedAt)
  PaymentApprovalService            ← 유지
  PaymentAttemptService             ← 삭제

naverpay.application
  NaverPayApprovalService
    (validateApprovedMerchantPayKeyOrThrow 삭제)
    (validateApprovedAmountOrThrow 삭제)
    → attempt.verifyApprovedResponse(...) 한 줄로 교체
    (보상 메서드들은 task B에서 처리, 이 task에서 변경 없음)
```

## 트랜잭션 경계

클래스 레벨 `@Transactional`을 붙이지 않고 메서드별로 명시한다. 각 메서드의 트랜잭션 경계가 한 눈에 보인다.

```
PaymentApprovalAttemptService                   ← 클래스 레벨 @Transactional 없음
  @Transactional(NOT_SUPPORTED) getOrCreate     ← repository 자체 트랜잭션 사용
  @Transactional                succeed         ← 상태 전이 커밋 (readOnly=false)
  @Transactional                fail
  @Transactional                failIfRequested

PaymentCancellationAttemptService               ← 클래스 레벨 @Transactional 없음
  @Transactional(NOT_SUPPORTED) getOrCreate
  @Transactional                succeed
  @Transactional                fail

PaymentApprovalService                          ← 클래스 레벨 @Transactional 제거 (이 task에서 정리)
  @Transactional(readOnly = true)                       findPaymentByMerchantPayKey
  @Transactional(readOnly = true, REQUIRES_NEW)         isCompensationRequired
  @Transactional                                        completeApprovedPayment
```

`verifyApprovedResponse`는 DB 접근 없이 attempt 필드와 인자값을 비교만 하므로 트랜잭션 무관.
호출 컨텍스트(`NaverPayApprovalService.completeVerifiedApproval`) 자체가 트랜잭션 없이 실행되며, attempt는 `getOrCreateApproveAttempt`에서 반환된 detached 상태.

## 테스트 구조 변경

```
PaymentAttemptTest
  - type 가드 케이스 4개 삭제:
    markApproveSucceeded_whenTypeIsCancel_throwException
    markApproveFailed_whenTypeIsCancel_throwException
    markCancelSucceeded_whenTypeIsApprove_throwException
    markCancelFailed_whenTypeIsApprove_throwException
  - 기존 케이스 메서드명/시그니처 갱신 (mark* → succeed/fail)
  - verifyApprovedResponse 케이스 추가 (키 불일치, 금액 불일치, 정상)

PaymentAttemptServiceTest → 삭제 후 분할
  PaymentApprovalAttemptServiceTest
  PaymentCancellationAttemptServiceTest

PaymentAttemptServiceConcurrencyTest → 분할
  PaymentApprovalAttemptServiceConcurrencyTest (approve 관련 2개 케이스)
  PaymentCancellationAttemptServiceConcurrencyTest (cancel 관련 2개 케이스)
```
