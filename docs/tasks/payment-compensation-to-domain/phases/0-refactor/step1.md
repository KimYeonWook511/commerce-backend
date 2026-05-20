# Step 2: introduce-pg-canceller-port

## 읽어야 할 파일

먼저 아래 파일들을 읽고 태스크 배경과 설계 의도를 파악하라:

- `docs/tasks/payment-compensation-to-domain/prd.md`
- `docs/tasks/payment-compensation-to-domain/architecture.md`
- `docs/tasks/payment-compensation-to-domain/adr.md`
- `src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `src/main/java/com/commerce/payment/domain/PaymentAttemptFailCode.java`
- `src/main/java/com/commerce/payment/naverpay/application/port/result/NaverPayCancelResult.java`

## 작업

### 신설 1: `PgCanceller` functional interface

경로: `src/main/java/com/commerce/payment/application/port/PgCanceller.java`

- `package com.commerce.payment.application.port`
- `@FunctionalInterface`
- 단일 메서드: `CancelOutcome cancel(PaymentAttempt cancelAttempt, String cancelReason)`
- import: `com.commerce.payment.application.port.result.CancelOutcome`, `com.commerce.payment.domain.PaymentAttempt`

### 신설 2: `CancelOutcome` record

경로: `src/main/java/com/commerce/payment/application/port/result/CancelOutcome.java`

- `package com.commerce.payment.application.port.result`
- record 선언: `CancelOutcome(Status status, PaymentAttemptFailCode failCode, String failDetail)`
- `Status` enum (nested): `SUCCESS`, `PROCESSING`, `FAILED`
- 정적 팩토리 메서드:
  - `success()` → `new CancelOutcome(Status.SUCCESS, null, null)`
  - `processing()` → `new CancelOutcome(Status.PROCESSING, null, null)`
  - `failed(PaymentAttemptFailCode failCode, String failDetail)` → `new CancelOutcome(Status.FAILED, failCode, failDetail)`
- import: `com.commerce.payment.domain.PaymentAttemptFailCode`

이 step은 신설만. 호출처 변경 없음. 기존 어떤 코드도 수정하지 않는다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행해 테스트가 모두 통과하는지 확인한다.
2. 아래를 확인한다:
   - `PgCanceller.java`가 `payment.application.port` 패키지에 있는가?
   - `CancelOutcome.java`가 `payment.application.port.result` 패키지에 있는가?
   - 기존 어떤 테스트도 새 파일 때문에 실패하지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이 step에서 호출처(NaverPayApprovalService 등)를 수정하지 마라. 이유: step 1은 신설만이며, 호출처 변경은 step 3에서 한다.
- `CancelOutcome`에 `NaverPayCancelResult`나 NaverPay 관련 클래스를 import하지 마라. 이유: `payment.application.port`가 NaverPay에 직접 의존하면 의존 방향이 역전된다.
