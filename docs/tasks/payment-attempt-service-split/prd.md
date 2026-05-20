# 태스크 PRD

## 태스크명

- `payment-attempt-service-split`

## 배경

- `PaymentAttemptService`는 APPROVE와 CANCEL 두 흐름이 한 서비스에 섞여 7개 메서드로 비대하다. 클래스명 `PaymentAttemptService`는 prefix 뒤에 "흐름/유스케이스"가 아니라 엔티티명이 박혀 있어 이 Repo의 다른 서비스 컨벤션(`PaymentApprovalService`, `OrderCreateService`, `AuthLoginService` 등)에서 유일하게 이탈한다.
- `PaymentAttempt` 도메인 메서드 4개(`markApproveSucceeded`, `markCancelSucceeded`, `markApproveFailed`, `markCancelFailed`)는 본문 동작이 거의 동일한데 진입할 때 `if (this.type != APPROVE) throw ...` 같은 type 가드를 중복으로 가진다. 각 메서드에 `Approve`/`Cancel` 접두어가 강제된다.
- `NaverPayApprovalService`의 `validateApprovedMerchantPayKeyOrThrow`/`validateApprovedAmountOrThrow`는 attempt의 자기 필드와 PG 응답을 비교하는 순수 무결성 검증이다. 도메인 메서드로 옮기는 게 자연스럽고, DB 접근이 없어 트랜잭션 영향도 없다.

## 목표

- `PaymentAttemptService`를 APPROVE 흐름(`PaymentApprovalAttemptService`)과 CANCEL 흐름(`PaymentCancellationAttemptService`)으로 분리해 이 Repo의 서비스 컨벤션을 회복한다.
- `PaymentAttempt` 도메인 메서드를 `succeed`/`fail` 두 개로 통합하고 `verifyApprovedResponse`를 신설해 도메인 표현을 명확히 한다.

## 범위

포함 범위:
- `PaymentAttempt` 도메인 메서드 통합: mark 4개 → `succeed`/`fail` 2개 + `verifyApprovedResponse` 신설
- `PaymentAttemptService` → `PaymentApprovalAttemptService` + `PaymentCancellationAttemptService` 분리 및 `PaymentAttemptService.java` 삭제
- `NaverPayApprovalService`의 validate 두 메서드를 도메인으로 이동
- 호출처(`PaymentApprovalService`, `NaverPayApprovalService`) 갱신
- 테스트 분할

제외 범위:
- 보상 dispatcher(`compensateMerchantKeyMismatch`/`AmountMismatch`/`Duplicate`/`Unexpected`) 이동 — task B `payment-compensation-to-domain`에서 처리
- `PgCanceller`/`CancelOutcome` 신설 — task B
- PaymentGateway port 추상화 — 별도 작업
- `NaverPayApprovalService`의 보상 관련 메서드(`failApproveAndCancelApprovedPayment`, `processCancelRequest`, `compensate*`) 변경 — task B

## 주요 시나리오

- `PaymentApprovalService.completeApprovedPayment` 내에서 `paymentApprovalAttemptService.succeed(...)` 호출
- `NaverPayApprovalService.approve` 내에서 `paymentApprovalAttemptService.getOrCreate(...)` 호출
- `NaverPayApprovalService.completeVerifiedApproval` 내에서 `attempt.verifyApprovedResponse(...)` 한 줄로 교체 (기존 validate 두 줄 삭제)
- 보상 흐름(`failApproveAndCancelApprovedPayment`)에서 `paymentApprovalAttemptService.failIfRequested(...)`, `paymentCancellationAttemptService.getOrCreate(...)`, `paymentCancellationAttemptService.succeed/fail(...)` 각각 호출

## 요구사항

- `PaymentAttempt.succeed(respondedAt)`: `markApproveSucceeded` + `markCancelSucceeded` 통합. `status != REQUESTED` 가드 유지. type 가드 제거
- `PaymentAttempt.fail(failCode, detail, respondedAt)`: `markApproveFailed` + `markCancelFailed` 통합. 동일 정책
- `PaymentAttempt.verifyApprovedResponse(merchantPayKey, totalAmount)`: merchantPayKey 불일치 시 `PAYMENT_MERCHANT_KEY_MISMATCH` throw, amount 불일치 시 `PAYMENT_AMOUNT_MISMATCH` throw
- `PaymentApprovalAttemptService`: `getOrCreate(@Transactional(NOT_SUPPORTED))`, `succeed/fail/failIfRequested(@Transactional(REQUIRED))`, 클래스 상단 `@Transactional(readOnly = true)`
- `PaymentCancellationAttemptService`: `getOrCreate(@Transactional(NOT_SUPPORTED))`, `succeed/fail(@Transactional(REQUIRED))`. `failIfRequested` 없음(현재 사용처 없음)
- `PaymentAttemptService.java` 삭제

## 제약사항

- 클래스 레벨 `@Transactional` 없이 메서드별 명시: `getOrCreate`는 `NOT_SUPPORTED`, `succeed`/`fail`/`failIfRequested`는 `REQUIRED`, 조회 전용 메서드는 `readOnly = true`
- 이 작업은 task B(`payment-compensation-to-domain`)의 선행 작업. task A 완료 시점에 컴파일·테스트 모두 통과하는 일관된 중간 상태여야 한다
- `NaverPayApprovalService` 안의 보상 메서드(`compensate*`, `failApproveAndCancelApprovedPayment`, `processCancelRequest` 등)는 이 task에서 변경하지 않는다
