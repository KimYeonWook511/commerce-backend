# Step 2: transient-failure-keep-requested

## 읽어야 할 파일

먼저 아래를 읽고 설계 의도를 파악하라:

- `/docs/tasks/approval-compensation-cleanup/prd.md`
- `/docs/tasks/approval-compensation-cleanup/adr.md` (특히 ADR-L1)
- `/docs/tasks/approval-compensation-cleanup/architecture.md`
- step 1에서 변경된 코드:
  - `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` (`completeVerifiedApproval` — step 1에서 `DataIntegrityViolationException` catch가 제거되고 `case PAYMENT_DUPLICATE`가 live가 된 상태)
  - `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (`compensateUnexpected`, `compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`)
- 도메인·정책 사실:
  - `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
  - `src/main/java/com/commerce/common/exception/CustomException.java`
  - postprocess reconcile 정책(완료 self-heal 경로): `src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java`, `src/test/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlowPolicy.java` (`APPROVE_RECONCILE` + `PG_APPROVED` → `APPROVED_PAYMENT_PROCESS`)
- 기존 테스트:
  - `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`
  - `src/test/java/com/commerce/payment/application/PaymentApprovalCompensationServiceTest.java`

공통 맥락이 부족하면 `/docs/exception-strategy.md`(예외 전파·결과 불명 처리)와 `/docs/adr.md` ADR-027을 추가로 읽는다.

## 작업

정상 승인(PG SUCCESS + verify 통과) 후 기록이 transient하게 실패한 건을 환불·FAILED로 박제하지 않고, 예외를 전파(500)해 approve를 `REQUESTED`로 남긴다. 그러면 배치 reconcile(`APPROVE_RECONCILE` + `PG_APPROVED`)이 완료를 self-heal한다.

### 1) `completeVerifiedApproval`의 unmapped 예외 보상 제거

- `catch (PaymentException ex)`의 `switch`에서:
  - `PAYMENT_MERCHANT_KEY_MISMATCH` → `compensateMerchantKeyMismatch` **유지**.
  - `PAYMENT_AMOUNT_MISMATCH` → `compensateAmountMismatch` **유지**.
  - `PAYMENT_DUPLICATE` → `compensateDuplicatePayment` **유지**(step 1에서 live).
  - `default` → `compensateUnexpected` 호출을 **제거**한다. 보상 없이 `throw ex`로 전파한다. (default 분기는 빈 처리 + "정상 승인 후 기록 실패는 REQUESTED 유지 → reconcile self-heal" 의도를 주석으로 남긴다)
- `catch (CustomException ex)` 블록과 `catch (Exception ex)` 블록의 `compensateUnexpected` 호출을 **제거**한다. 이 두 예외는 보상 없이 그대로 전파되게 한다. (보상 없는 log-and-rethrow만 남기지 말고, GlobalExceptionHandler가 안전망 500으로 처리하도록 자연 전파시킨다. 단, approve 식별자 진단 로깅을 남기려면 한 곳에서 `log.error`로만 남기고 보상은 하지 않는다)
- 결과적으로 `completeVerifiedApproval`은 명시적 비정상 3종(`MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`/`PAYMENT_DUPLICATE`)만 보상하고, 나머지 모든 예외는 보상 없이 전파한다.

### 2) `compensateUnexpected` 제거

- 1)에서 호출처가 모두 사라지므로 `PaymentApprovalCompensationService.compensateUnexpected`를 제거한다.
- `runPgCancel`은 `compensateAmountMismatch`/`compensateDuplicatePayment`가 여전히 사용하므로 유지한다.

### 3) 테스트 갱신

- `NaverPayApprovalServiceTest`:
  - 기존에 unmapped `PaymentException`/`CustomException`/일반 `Exception`이 `compensateUnexpected`(환불)를 호출함을 검증하던 테스트를, "보상이 호출되지 않고(=PG cancel·failIfRequested 미발생) 예외가 그대로 전파되며 approve가 `REQUESTED`로 남는다"로 갱신한다.
  - 모르는 예외(예: `RuntimeException`)가 `succeedApproval`에서 발생하면 UNKNOWN/FAILED 둔갑 없이 전파(500)됨을 검증한다.
  - `MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`는 여전히 보상(환불)함을 검증하는 기존 테스트는 유지한다.
- `PaymentApprovalCompensationServiceTest`: `compensateUnexpected` 관련 테스트를 제거한다. 나머지 보상 메서드 테스트는 유지한다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

공통 예외/보상 흐름 변경이므로 단위/슬라이스(`test`)에 더해, `completeVerifiedApproval`을 다루는 통합(`integrationTest` — 예: `NaverPayServiceIntegrationTest`)과 동시 거동 회귀(`concurrencyTest`)까지 재실행한다. `integrationTest`/`concurrencyTest`는 Docker daemon이 필요하다(`verifyDockerDaemon`).

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 모두 실행한다. `integrationTest`/`concurrencyTest`는 Docker daemon이 떠 있어야 한다 — Docker 미가용으로 태스크가 실패하면 자동 우회하지 말고 step을 `blocked`로 두고 사용자에게 보고한다.
2. 아래를 확인한다.
   - `completeVerifiedApproval`이 명시적 비정상 3종만 보상하고 나머지 예외는 보상 없이 전파하는가?
   - `compensateUnexpected`가 제거됐고 사용처가 없는가? (`rg "compensateUnexpected" src`로 없음 확인)
   - transient 실패 경로에서 PG cancel·`failIfRequested`가 호출되지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 정상 승인 후 transient 실패를 환불하거나 FAILED로 마킹하지 마라. 이유: PG SUCCESS + verify 통과한 *맞는 결제*이며, REQUESTED 유지 시 reconcile이 완료시킨다(ADR-L1).
- 모르는 예외를 UNKNOWN/FAILED 한 status로 default하지 마라. 이유: "완료가 맞음 / FAILED가 맞음 / 버그"를 구분 못 하게 되고 정당한 매출이 박제된다.
- `MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH` 보상을 제거하지 마라. 이유: 이건 *틀린 결제*라 환불이 맞다(범위 밖).
- `runPgCancel`을 제거하지 마라. 이유: 다른 보상 메서드가 사용한다.
- 기존 테스트를 깨뜨리지 마라.
