# 태스크 PRD

## 태스크명

- `payment-compensation-to-domain`

## 배경

- `NaverPayApprovalService`의 보상 dispatcher 4개(`compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`, `compensateUnexpected`)와 공통 골격 `failApproveAndCancelApprovedPayment`는 결제 도메인 의미의 정책을 NaverPay 어댑터 안에 가두고 있다.
- 보상 정책(어떤 실패 → cancel 필요/불필요, cancel reason, cancel amount)은 PG가 달라져도 동일한 결제 도메인 책임이다. PG-specific한 부분은 cancel API 호출과 응답 해석뿐이다.
- task A(`payment-attempt-service-split`)로 `PaymentAttemptService`가 `PaymentApprovalAttemptService`/`PaymentCancellationAttemptService`로 분리됐고, 이 task는 그 분리를 기반으로 보상 정책 이동을 완성한다.

## 목표

- 보상 dispatcher 4개와 공통 골격을 `payment.application`의 `PaymentApprovalCompensationService`로 이동한다.
- PG cancel 호출은 `PgCanceller` functional interface로 위임해, `payment.application` 코드가 `NaverPayCancelResult`를 직접 import하지 않도록 의존 방향을 정리한다.
- `NaverPayApprovalService`를 main flow + PG cancel 콜백으로만 구성하여 라인 수와 책임을 줄인다.

## 범위

포함 범위:
- `PgCanceller` functional interface 신설 (`payment.application.port`)
- `CancelOutcome` record 신설 (`payment.application.port.result`)
- `PaymentApprovalCompensationService` 신설 (`payment.application`): dispatcher 4개 + private `runPgCancel`
- `NaverPayApprovalService`: 보상 메서드 4개 삭제, `processCancelRequest`/`succeedCancel`/`markCancelFailed` 삭제, `pgCancel` private 메서드 신설, catch 블록을 메서드 참조로 정리
- 테스트: `PaymentApprovalCompensationServiceTest` 신설, `NaverPayApprovalServiceTest` 보상 케이스 갱신
- 루트 docs 동기화 (ADR-015, architecture.md, exception-strategy.md, testing-conventions.md)

제외 범위:
- PaymentGateway port 완전 inversion (PG 둘 이상 추가 시)
- `PaymentReference` Value Object 도입
- ArchUnit으로 mark 가시성 강제
- `PaymentAttempt` 엔티티 분리 (ApproveAttempt/CancelAttempt)
- Strategy 패턴으로 보상 정책 추상화
- `commerce-workspace/docs/` 하위 문서 수정 (Frontend 세션 책임)

## 주요 시나리오

- `NaverPayApprovalService.completeVerifiedApproval` catch에서 `paymentApprovalCompensationService.compensateXxx(..., this::pgCancel)` 한 줄로 dispatcher 호출
- `PaymentApprovalCompensationService.runPgCancel`이 `failIfRequested` → `isCompensationRequired` → `getOrCreate` → `pgCanceller.cancel` → outcome 분기 순서로 보상 실행
- `pgCancel`은 `NaverPayGateway.cancel` 호출 후 `NaverPayCancelResult.Status` → `CancelOutcome.Status` 변환

## 요구사항

- `PgCanceller`: `@FunctionalInterface`, 시그니처 `CancelOutcome cancel(PaymentAttempt cancelAttempt, String cancelReason)`
- `CancelOutcome`: record, `Status` enum(SUCCESS/PROCESSING/FAILED), `failCode`/`failDetail` nullable 필드, 정적 팩토리 `success()`/`processing()`/`failed(failCode, detail)`
- NaverPay → CancelOutcome 매핑: `SUCCESS`/`ALREADY_CANCELED` → `CancelOutcome.success()`, `PROCESSING` → `CancelOutcome.processing()`, `FAILED` → `CancelOutcome.failed(failCode, failDetail)`
- `PaymentApprovalCompensationService`: 클래스 레벨 `@Transactional` 없음. 각 단계가 자기 트랜잭션을 가짐 (race-safe성 보존)
- `compensateMerchantKeyMismatch`: PG cancel 없이 `failIfRequested`만 호출
- `compensateAmountMismatch`/`compensateDuplicatePayment`/`compensateUnexpected`: `runPgCancel` 호출
- `runPgCancel` 순서: `failIfRequested` → `isCompensationRequired` (false면 warn + return) → `cancelAttempt.getOrCreate` → `cancelAttempt.getStatus() != REQUESTED`면 return → `pgCanceller.cancel` → outcome 분기

## 제약사항

- `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional`을 붙이지 않는다. 각 단계가 독립 commit 되어야 일부 실패 시 진행한 부분이 보존된다 (ADR-T2)
- `payment.application` 코드가 `NaverPayCancelResult`를 import하지 않아야 한다
- 각 단계(failIfRequested, isCompensationRequired, getOrCreate, succeed/fail)는 각자의 트랜잭션 경계를 그대로 유지한다
- task A가 develop에 merge된 상태에서 진행한다 (선행 조건 충족 ✅)
