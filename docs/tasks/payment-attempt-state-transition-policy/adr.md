# 태스크 ADR

## 결정 제목

PaymentAttempt mark 메서드는 상태 전이와 type 정합성을 도메인에서 검증한다

---

## ADR-A: 상태 전이 검증 — 엄격한 검증 (멱등 자기 전이 거부)

### 배경

mark 메서드는 (1) REQUESTED → SUCCEEDED, (2) REQUESTED → FAILED 두 가지 전이만 의미 있다. 그 외의 전이(SUCCEEDED → FAILED, FAILED → SUCCEEDED, 자기 전이 포함)는 발생해서는 안 되는 케이스다.

옵션 비교:
- **옵션 A (채택): 엄격한 검증** — REQUESTED → SUCCEEDED/FAILED만 허용. 멱등 자기 전이도 거부.
- **옵션 B: 멱등 허용** — SUCCEEDED → SUCCEEDED, FAILED → FAILED는 통과. 재시도 대비.

### 결정 내용

REQUESTED → SUCCEEDED/FAILED만 허용. 그 외 모든 전이 거부.

### 근거

- 멱등성은 이미 상위 레이어에서 처리된다: `PaymentAttemptService.getOrCreateApproveAttempt`가 동일 키 재요청 시 기존 attempt를 반환하고, `NaverPayApprovalService.processApproveAttempt`의 switch가 SUCCEEDED/FAILED 상태별로 mark 없이 처리한다. mark가 멱등을 책임질 필요 없다.
- Order 도메인의 명시적 선조건 검증(`Order.cancel`: `status != INIT` → throw)과 일관성.
- failCode 보호: FAILED → SUCCEEDED 시 failCode=null 초기화로 실패 사유가 사라지는 문제를 원천 차단.

### 결과

- 정상 흐름에서는 영향 없음 (호출처는 항상 REQUESTED → 통과)
- 코드 버그 또는 race window에서 잘못된 전이 시도 시 500으로 가시화

---

## ADR-B: type 정합성 검증 포함

### 배경

mark 메서드 4개는 이름으로 APPROVE/CANCEL type 의도를 드러내지만, 내부에서 attempt.type을 확인하지 않는다.

옵션 비교:
- **옵션 A (채택): type 검증 포함** — type 불일치 시 throw. 도메인 모델 무결성.
- **옵션 B: type 검증 없음** — 기존처럼 type 확인 없이 status만 검증.

### 결정 내용

`markApprove*`는 `type == APPROVE`, `markCancel*`는 `type == CANCEL`만 허용.

### 근거

- DB unique 제약 `(merchant_pay_key, provider, payment_id, type)`으로 APPROVE/CANCEL attempt가 별도 행으로 분리되어 있어 실제 위반 가능성은 낮다.
- 그러나 도메인 모델이 메서드 이름이 약속하는 의도를 강제하는 것은 무결성 측면에서 가치가 있다. 향후 호출처 추가 시 방어선 역할.

### 결과

- 현실적 위반 경로는 거의 없지만, 호출처 확장 시 잘못된 mark를 즉시 감지 가능

---

## ADR-C: 신규 에러 코드 HTTP 상태 — 500 INTERNAL_SERVER_ERROR

### 배경

옵션 비교:
- **옵션 A (채택): 500** — 도메인 무결성 위반은 내부 결함 신호. 운영 알람 대상.
- **옵션 B: 409 CONFLICT** — ADR-010(amount mismatch, 409)과 일관성.

### 결정 내용

`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`, `PAYMENT_ATTEMPT_TYPE_MISMATCH` 모두 HTTP 500.

### 근거

- ADR-010(amount mismatch)은 **호출자의 잘못된 입력**(외부 요인) → 4xx 적절.
- 새 코드는 **내부 코드 버그 또는 race window**(내부 결함) → 5xx 적절. 운영 모니터링 알람 분리.

### 결과

- 운영 대시보드에서 "호출자 4xx"와 "내부 5xx"가 명확히 구분됨

---

## ADR-D: catch swallow 회귀 방지 — failApproveAndCancelApprovedPayment 내 try-catch

### 배경

새 검증이 trigger될 수 있는 race 시나리오:

```
PaymentApprovalService.completeApprovedPayment (라인 35-65):
  1. order = findByMerchantPayKeyForUpdate(...)
  2. completedPayment = validateCompletedPaymentOrThrow(...)
  3. succeedApproveAttempt(...) → markApproveSucceeded() → 메모리상 SUCCEEDED
  4. order.completePayment() → race로 throw OrderException
```

catch (CustomException ex)가 `failApproveAndCancelApprovedPayment` 호출
→ `failApprove` → `markApproveFailed` → attempt 메모리상 SUCCEEDED → **새 검증 throw**
→ PG cancel 흐름(`processCancelRequest`) 중단
→ PG 결제 승인됨 + 우리 시스템 미반영 (외부 정합성 깨짐)

옵션 비교:
- **옵션 A (채택): failApprove try-catch + return 없이 PG cancel 진행**
  - mark 실패 시 log.warn + PG cancel은 무조건 시도
- **옵션 B: ADR에만 명시**
  - 코드 변경 없음. 발생 가능성 낮으나 운영 risk 존재.
- **옵션 C: catch 분기 수정 (3곳)**
  - 변경 범위 큼. 로깅 보강과 함께 후속 PR에서 처리가 더 적합.

### 결정 내용

`failApproveAndCancelApprovedPayment` 함수 내부의 `failApprove` 호출 한 곳만 try-catch로 감싼다. **return 없이** PG cancel은 무조건 진행.

```java
try {
    failApprove(approveAttempt, failCode, failDetail);
} catch (PaymentException markEx) {
    log.warn(
        "Approve attempt mark failed during compensation, proceeding to PG cancel: merchantPayKey={}, paymentId={}, errorCode={}",
        approveAttempt.getMerchantPayKey(),
        approveAttempt.getPaymentId(),
        markEx.getErrorCode(),
        markEx
    );
    // return 없음 — PG cancel은 무조건 시도 (외부 정합성 보존)
}
```

### 근거

- **데이터 정합성 보존이 최우선**: mark 실패해도 PG cancel을 시도해야 PG 결제 승인이 우리 시스템에 반영되지 않은 채 남는 것을 막을 수 있다.
- mark 실패(2차 예외)는 "덜 중요" 분기: 트랜잭션 rollback으로 attempt 상태 자체는 보존됨. log.warn + 1차 전파로 충분.
- 상위 catch 블록(라인 130, 145)의 log.error 보강은 별도 PR #111에서 처리.

### 결과

- race window에서 PG cancel 흐름 중단 방지
- 함수 내부 한 곳만 변경으로 범위 최소화
- 상위 catch 블록의 1차 예외 로깅 보강과 일반 원칙 문서화는 후속 Issue #111에서 정의 예정

---

## 회귀 안전성

| 테스트 | 근거 |
|---|---|
| `PaymentAttemptTest` | 기존 케이스 모두 REQUESTED → 통과 |
| `PaymentAttemptServiceTest` | mark 호출 시 REQUESTED 상태에서만 |
| `PaymentAttemptServiceConcurrencyTest` | mark 직접 호출 없음 |
| `NaverPayApprovalServiceTest` | setup mark 호출(라인 72, 533, 985, 1010) 모두 REQUESTED 시작 |
| `NaverPayServiceIntegrationTest` | service 내부 mark만, REQUESTED 보장 |
