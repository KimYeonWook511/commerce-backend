# Step 2: redesign-flow-policy

## 읽어야 할 파일

먼저 아래를 읽고 설계 의도를 파악하라:

- `/docs/tasks/postprocess-unknown-redesign/prd.md`
- `/docs/tasks/postprocess-unknown-redesign/adr.md`
- Step 1에서 재작성된 파일:
  - `src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTarget.java`
- 현재 정책(재작성 대상):
  - `src/test/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlow.java`
  - `src/test/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlowPolicy.java`
  - `src/test/java/com/commerce/payment/postprocess/flow/PaymentVerificationStatus.java`
  - `src/test/java/com/commerce/payment/postprocess/flow/RelatedOrderStatus.java` (제거 대상)
  - `src/test/java/com/commerce/payment/postprocess/PaymentPostProcessFlowPolicyTest.java`
- 도메인 사실:
  - `src/main/java/com/commerce/payment/naverpay/infrastructure/code/NaverPayCancelCode.java` (`CancelDeadlineExpired`·`CancelNotComplete`·`AlreadyOnGoing`)

## 작업

`PaymentPostProcessFlowPolicy`를 Step 1의 새 Target에 맞춰 재작성한다. PG 검증 결과(`PaymentVerificationStatus`)는 source-agnostic로 유지한다.

### 1) `PaymentVerificationStatus` (유지)

기존 값 유지: `PG_APPROVED`, `PG_CANCELED`, `PENDING`, `HISTORY_NOT_FOUND`. (PG 조회로 확정된 사실. approve/cancel 양쪽 reconcile이 공유한다.)

### 2) `PaymentPostProcessFlow` enum (정리)

다음 값을 둔다. mismatch 전용으로만 쓰이던 흐름이 사라지므로 사용처 없는 값은 제거한다.

- `APPROVED_PAYMENT_PROCESS` — 승인 완료 확정 → 승인 완료 처리(succeed + order PAID)로 진행.
- `ALREADY_CANCELED_PAYMENT_PROCESS` — 이미 취소 확인 → ALREADY_CANCELED 종결.
- `CANCEL_RETRY_PROCESS` — 취소 미완 확인 → 재취소 요청.
- `CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS` — cancel 기록 생성 + 취소 요청.
- `MANUAL_REVIEW_PROCESS` — 자동 처리하지 않고 수동 확인.
- `KEEP_WAITING` — 아직 미확정. 다음 후처리 주기까지 대기.
- `NONE` — 추가 후처리 불필요.

### 3) `RelatedOrderStatus` 및 3-arg `resolveFlow` 제거

- `RelatedOrderStatus.java` 파일을 삭제한다.
- 옛 3-arg `resolveFlow(target, verificationStatus, relatedOrderStatus)`(merchantPayKey mismatch 전용)를 제거한다. mismatch는 Step 1에서 `MANUAL_REVIEW`로 격리되므로 order 상태 결합 분기가 필요 없다.

### 4) `resolveFlow` 재작성

검증이 필요 없는 Target — `resolveFlow(target)`:

| Target | Flow |
| --- | --- |
| `APPROVED_CANCEL_COMPENSATION` | `CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS` |
| `MANUAL_REVIEW` | `MANUAL_REVIEW_PROCESS` |
| `NONE` | `NONE` |
| (reconcile 대상) | `IllegalArgumentException`(verificationStatus 필요) |

검증이 필요한 reconcile Target — `resolveFlow(target, verificationStatus)`:

| Target | verification | Flow |
| --- | --- | --- |
| `APPROVE_RECONCILE` | PG_APPROVED | `APPROVED_PAYMENT_PROCESS` |
| `APPROVE_RECONCILE` | PG_CANCELED | `ALREADY_CANCELED_PAYMENT_PROCESS` |
| `APPROVE_RECONCILE` | PENDING, HISTORY_NOT_FOUND | `KEEP_WAITING` |
| `CANCEL_RECONCILE` | PG_CANCELED | `ALREADY_CANCELED_PAYMENT_PROCESS` |
| `CANCEL_RECONCILE` | PG_APPROVED | `CANCEL_RETRY_PROCESS` |
| `CANCEL_RECONCILE` | PENDING, HISTORY_NOT_FOUND | `KEEP_WAITING` |
| (그 외 Target) | — | `IllegalArgumentException` |

### 5) 테스트 재작성

`PaymentPostProcessFlowPolicyTest`를 위 표에 맞춰 재작성한다. 최소한 다음을 검증한다.

- APPROVE_RECONCILE: PG_APPROVED→승인완료, PG_CANCELED→ALREADY_CANCELED, PENDING/HISTORY_NOT_FOUND→KEEP_WAITING.
- CANCEL_RECONCILE: PG_CANCELED→ALREADY_CANCELED, PG_APPROVED→CANCEL_RETRY, PENDING/HISTORY_NOT_FOUND→KEEP_WAITING.
- APPROVED_CANCEL_COMPENSATION→CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS, MANUAL_REVIEW→MANUAL_REVIEW_PROCESS, NONE→NONE (검증 불필요 경로).
- reconcile Target에 검증 없이 호출하면 예외, 비-reconcile Target에 검증 버전 호출하면 예외.

## Acceptance Criteria

```bash
./gradlew test --tests "com.commerce.payment.postprocess.PaymentPostProcessFlowPolicyTest"
./gradlew test
```

(두 번째 전체 테스트: enum 제거·파일 삭제가 다른 참조를 깨지 않았는지 확인.)

## 검증 절차

1. 위 Acceptance Criteria 두 커맨드를 실행한다.
2. 아래를 확인한다.
   - `RelatedOrderStatus.java`가 삭제되고 어디서도 참조되지 않는가?
   - 3-arg `resolveFlow`가 제거됐는가?
   - Flow가 Step 1의 새 Target과 1:1로 매핑되는가?
3. 사용처 탐색으로 잔재가 없는지 확인한다.

```bash
rg "RelatedOrderStatus" src/main src/test
```

4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- production 코드(`src/main`)를 수정하지 마라. 이유: test-side 정책 한정, 운영 wiring은 #208.
- order 상태(PAID/INIT/CANCELED) 결합 분기를 새로 넣지 마라. 이유: 만료↔대사 타이밍(SUCCEEDED인데 주문 CANCELED 등)은 #222의 옵션 결정 후 다룬다. 지금 넣으면 사용처 없는 추정 코드가 된다.
- `RelatedOrderStatus`를 남겨두지 마라. 이유: 사용처가 사라졌다(CLAUDE.md "사용처 없는 코드 안 남김").
- 기존 테스트를 깨뜨리지 마라.
