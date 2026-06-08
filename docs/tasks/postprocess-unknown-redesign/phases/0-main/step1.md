# Step 1: redesign-target-policy

## 읽어야 할 파일

먼저 아래를 읽고 설계 의도를 파악하라:

- `/docs/tasks/postprocess-unknown-redesign/prd.md`
- `/docs/tasks/postprocess-unknown-redesign/adr.md`
- 현재 정책(재작성 대상):
  - `src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTarget.java`
  - `src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java`
  - `src/test/java/com/commerce/payment/postprocess/PaymentPostProcessTargetPolicyTest.java`
- 도메인 사실:
  - `src/main/java/com/commerce/payment/domain/Payment.java` (status·type·failCode·createdAt·respondedAt, 팩토리 `createRequested`/`createCancelRequested`)
  - `src/main/java/com/commerce/payment/domain/PaymentStatus.java` (REQUESTED·SUCCEEDED·FAILED·UNKNOWN)
  - `src/main/java/com/commerce/payment/domain/PaymentFailCode.java`
  - `src/main/java/com/commerce/payment/domain/PaymentType.java` (APPROVE·CANCEL)
  - `src/main/java/com/commerce/payment/naverpay/infrastructure/code/NaverPayApproveCode.java` (`TIME_EXPIRED` = 승인 가능 시간 10분 초과)

공통 맥락이 부족하면 `/docs/adr.md`의 ADR-026/027/028을 추가로 읽는다.

## 작업

`PaymentPostProcessTarget`(enum)과 `PaymentPostProcessTargetPolicy`를 status 중심으로 재작성하고, `PaymentPostProcessTargetPolicyTest`를 현재 모델 기준으로 재작성한다.

### 1) `PaymentPostProcessTarget` enum 재정의

아래 값만 둔다(설명 description 포함). 옛 값(`APPROVE_REQUESTED_TARGET`, `FAILED_APPROVE_RESULT_TARGET`, `MERCHANT_PAY_KEY_MISMATCH_TARGET`, `APPROVED_PAYMENT_CANCEL_ACTION`, `CANCEL_REQUESTED_TARGET`, `CANCEL_RETRY_TARGET`)은 제거·대체한다.

- `APPROVE_RECONCILE` — APPROVE UNKNOWN 또는 stale REQUESTED. PG 조회로 승인 결과를 확정할 대상.
- `CANCEL_RECONCILE` — CANCEL UNKNOWN·stale REQUESTED·재시도 가능 FAILED. PG 조회로 취소 상태를 확정할 대상.
- `APPROVED_CANCEL_COMPENSATION` — 실시간 보상이 끊겨 cancel 기록이 없는 잔여. cancel 기록 생성 + 취소 요청 대상.
- `MANUAL_REVIEW` — 자동 처리 불가. 운영자 확인 대상.
- `NONE` — 추가 후처리 불필요.

### 2) 임계 상수 (`private static final Duration`)

NaverPay 승인 가능 시간(10분)에서 파생한다. 각 상수 위에 근거 주석을 단다. #208 운영 config 승격 전제임을 주석에 남긴다.

- `UNKNOWN_RECONCILE_DELAY` = `Duration.ofMinutes(1)` — UNKNOWN은 빨리 폴링(차단 해제 + capture 후 ack 유실 빠른 복구).
- `REQUESTED_STALE_DELAY` = `Duration.ofMinutes(15)` — 승인 가능 시간 10분 + 마진 5분. 윈도우가 닫힌 뒤 reconcile.
- `ESCALATION_DELAY` = `Duration.ofHours(6)` — reconcile 대상이 이 시간을 넘도록 결론이 안 나면 MANUAL 승급. (운영 config로 확정 예정)

### 3) 경과 측정 기준 시각

- REQUESTED: `createdAt` 기준
- UNKNOWN / FAILED: `respondedAt` 기준
- `hasElapsed(baseTime, delay, now)` 헬퍼로 표현한다.

### 4) `resolvePostProcessTarget(approvePayment, cancelPayment, now)` 결정 규칙

approve를 먼저 검사한다(동시 UNKNOWN 시 approve 우선 확정). 시그니처는 기존과 동일하게 유지한다.

approvePayment != null:

| approve 상태 | 조건 | Target |
| --- | --- | --- |
| UNKNOWN | escalated(respondedAt) | `MANUAL_REVIEW` |
| UNKNOWN | elapsed(respondedAt, UNKNOWN_RECONCILE_DELAY) | `APPROVE_RECONCILE` |
| REQUESTED | escalated(createdAt) | `MANUAL_REVIEW` |
| REQUESTED | elapsed(createdAt, REQUESTED_STALE_DELAY) | `APPROVE_RECONCILE` |
| FAILED | failCode == MERCHANT_PAY_KEY_MISMATCH | `MANUAL_REVIEW` |
| FAILED | failCode ∈ {AMOUNT_MISMATCH, DUPLICATE_PAYMENT} ∧ cancelPayment == null | `APPROVED_CANCEL_COMPENSATION` |

cancelPayment != null:

| cancel 상태 | 조건 | Target |
| --- | --- | --- |
| UNKNOWN | escalated(respondedAt) | `MANUAL_REVIEW` |
| UNKNOWN | elapsed(respondedAt, UNKNOWN_RECONCILE_DELAY) | `CANCEL_RECONCILE` |
| REQUESTED | escalated(createdAt) | `MANUAL_REVIEW` |
| REQUESTED | elapsed(createdAt, REQUESTED_STALE_DELAY) | `CANCEL_RECONCILE` |
| FAILED | failCode ∈ {CANCEL_PROCESS_FAILED, PG_INVALID_RESPONSE} ∧ escalated(respondedAt) | `MANUAL_REVIEW` |
| FAILED | failCode ∈ {CANCEL_PROCESS_FAILED, PG_INVALID_RESPONSE} | `CANCEL_RECONCILE` |
| FAILED | failCode == PG_REQUEST_REJECTED | `MANUAL_REVIEW` |

위에 안 걸리는 모든 경우(SUCCEEDED, 확정 FAILED(TIME_EXPIRED·INVALID_MERCHANT·INVALID_PG_PAYMENT_ID·ALREADY_CANCELED·OWNER_AUTH_FAILED·NOT_ENOUGH_ACCOUNT_BALANCE·PG_REQUEST_REJECTED(approve)), 임계 미경과) → `NONE`.

- `escalated(base)` = `hasElapsed(base, ESCALATION_DELAY, now)`. escalation은 reconcile 후보(UNKNOWN/stale REQUESTED/cancel 재시도 FAILED)에만 적용한다.
- `APPROVED_CANCEL_COMPENSATION`은 자체 escalation을 두지 않는다(생성된 cancel 기록이 이후 `CANCEL_RECONCILE`로 escalation된다).

### 5) 테스트 재작성

`PaymentPostProcessTargetPolicyTest`를 위 규칙에 맞춰 재작성한다. 최소한 다음을 검증한다.

- APPROVE UNKNOWN(임계 경과) → `APPROVE_RECONCILE`; UNKNOWN escalation 경과 → `MANUAL_REVIEW`.
- stale REQUESTED(15분 경과) → `APPROVE_RECONCILE`; 미경과 → `NONE`.
- approve FAILED + MERCHANT_PAY_KEY_MISMATCH → `MANUAL_REVIEW`.
- approve FAILED + AMOUNT_MISMATCH/DUPLICATE_PAYMENT + cancel 없음 → `APPROVED_CANCEL_COMPENSATION`; cancel 있으면 다시 잡지 않음.
- 확정 FAILED(TIME_EXPIRED 등)·SUCCEEDED → `NONE`.
- CANCEL UNKNOWN → `CANCEL_RECONCILE`; CANCEL FAILED(CANCEL_PROCESS_FAILED·PG_INVALID_RESPONSE) → `CANCEL_RECONCILE`; CANCEL FAILED(PG_REQUEST_REJECTED) → `MANUAL_REVIEW`.
- approve·cancel 동시 UNKNOWN → `APPROVE_RECONCILE`(approve 우선).
- 시각 기준: REQUESTED=createdAt, UNKNOWN/FAILED=respondedAt 으로 경과가 계산됨.

테스트에서 시각 조작은 기존 방식(`ReflectionTestUtils.setField(payment, "createdAt", ...)`, `fail(...,respondedAt)`/`markUnknown(...,respondedAt)`)을 따른다.

## Acceptance Criteria

```bash
./gradlew test --tests "com.commerce.payment.postprocess.PaymentPostProcessTargetPolicyTest"
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - Target enum 값이 5개(`APPROVE_RECONCILE`·`CANCEL_RECONCILE`·`APPROVED_CANCEL_COMPENSATION`·`MANUAL_REVIEW`·`NONE`)로 정리됐는가?
   - approve "결과 불명" failCode 열거 분기가 제거됐는가?
   - 임계 상수에 NaverPay 승인 가능 시간 파생 근거 주석이 있는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- production 코드(`src/main`)를 수정하지 마라. 이유: 이번 task는 test-side 결정 정책 한정이며 운영 wiring은 #208 범위다.
- approve 결과 불명을 failCode로 다시 식별하지 마라. 이유: 현재 모델에서 그 케이스는 `status=UNKNOWN`으로만 존재한다(ADR-027/028).
- 임계를 NaverPay 시간과 무관한 매직넘버로 두지 마라. 이유: stale 판단은 "PG 처리 시간 + 마진"에서 파생돼야 오판/과차단을 막는다(ADR-L2).
- 기존 테스트를 깨뜨리지 마라.
