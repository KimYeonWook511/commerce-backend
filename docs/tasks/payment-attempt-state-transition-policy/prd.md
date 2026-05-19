# 태스크 PRD

## 태스크명

- `payment-attempt-state-transition-policy`

## 배경

`PaymentAttempt`의 mark 메서드 4개(`markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`)는 호출 시점에 attempt의 `status`와 `type`을 검증하지 않고 status/failCode/respondedAt을 무조건 덮어쓴다.

정상 흐름에서는 `NaverPayApprovalService.processApproveAttempt`의 switch가 application 계층에서 안전망 역할을 하지만, 도메인 모델 자체에는 라이프사이클 보호가 없다.

대표 위험 시나리오:
- `FAILED(failCode=TIMEOUT)` 상태의 attempt에 `markApproveSucceeded()` 호출
- → status가 SUCCEEDED로 바뀌면서 failCode=null로 초기화
- → 실패 사유가 흔적 없이 사라짐

## 목표

- `PaymentAttempt` 도메인 모델이 자기 라이프사이클을 스스로 보호하도록 mark 메서드에 검증을 추가한다.
- 도메인 무결성 위반을 500 에러로 가시화하여 운영 모니터링에서 인지 가능하게 한다.
- Order 도메인의 명시적 선조건 검증 패턴(`Order.cancel`, `Order.completePayment`)과 결을 맞춰 일관성을 확보한다.

## 범위

### 포함

- `PaymentAttempt` 4개 mark 메서드에 `status`/`type` 선조건 검증 추가
- `PaymentErrorCode`에 신규 에러 코드 2개 추가 (HTTP 500)
- `NaverPayApprovalService.failApproveAndCancelApprovedPayment`의 `failApprove` 호출 try-catch 보호 (race window 회귀 방지)
- `PaymentAttemptTest`에 전이 거부 / type 위반 테스트 케이스 9개 추가
- `docs/ADR.md` ADR-012 추가

### 제외

- 보상 catch 2차 예외 처리 일반 원칙 문서화 (`docs/architecture.md` + ADR-013) — 후속 Issue #111
- `NaverPayApprovalService` 상위 catch 블록 (라인 130, 145) `log.error` 누락 보강 — 후속 Issue #111
- `PaymentAttemptStatus` enum 자체 변경
- `NaverPayApprovalService.processApproveAttempt` switch 분기 변경
- `PaymentAttemptService`의 4개 mark 호출 메서드 변경

## 주요 시나리오

### 정상: REQUESTED → SUCCEEDED

```
NaverPay 승인 성공
→ succeedApproveAttempt 호출
→ markApproveSucceeded(respondedAt)
→ status == REQUESTED, type == APPROVE 검증 통과
→ status = SUCCEEDED
```

### 거부: 상태 전이 위반

```
이미 FAILED 상태의 attempt에 markApproveSucceeded 호출
→ status != REQUESTED 검증 실패
→ PaymentException(PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED, 500)
```

### 거부: type 불일치

```
CANCEL attempt에 markApproveSucceeded 호출
→ type != APPROVE 검증 실패
→ PaymentException(PAYMENT_ATTEMPT_TYPE_MISMATCH, 500)
```

### 보상 흐름 보호: race window에서 failApprove throw 시

```
completeApprovedPayment 내 order.completePayment() race throw
→ catch 블록 진입 → failApproveAndCancelApprovedPayment 호출
→ failApprove (mark) → race window에서 attempt가 이미 SUCCEEDED 상태
→ 새 검증으로 throw
→ try-catch로 보호 (log.warn) + return 없이 PG cancel 진행
```

## 요구사항

1. `markApproveSucceeded`, `markApproveFailed`는 `type == APPROVE`인 attempt에서만 실행
2. `markCancelSucceeded`, `markCancelFailed`는 `type == CANCEL`인 attempt에서만 실행
3. 모든 mark 메서드는 `status == REQUESTED`인 attempt에서만 실행
4. 위반 시 `PaymentException` + 500 에러 코드 throw
5. 멱등 자기 전이(SUCCEEDED → SUCCEEDED 등)도 거부
6. `failApproveAndCancelApprovedPayment` 내 `failApprove` 호출은 mark throw 시에도 PG cancel 흐름을 중단하지 않음

## 제약사항

- 기존 정상 흐름에서는 새 검증이 trigger되지 않아야 한다 (회귀 위험 없음)
- 새 에러 코드는 외부 입력 mismatch(ADR-010, 409)와 구분되는 HTTP 500을 사용한다
- NaverPayApprovalService의 다른 catch 블록은 변경하지 않는다
