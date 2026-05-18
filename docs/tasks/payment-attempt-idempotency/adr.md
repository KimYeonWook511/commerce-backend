# 기능 ADR

## ADR-A: 멱등 재요청 amount mismatch에 신규 에러 코드 사용

### 배경

기존 `PAYMENT_AMOUNT_MISMATCH`(PAYMENT-400-8)는 `NaverPayApprovalService`에서 PG 응답 금액과 우리 기대 금액이 다를 때 이미 사용 중이다.
이번 변경에서 "동일 멱등 키 + 다른 amount 재요청" 케이스를 처리하려면 같은 코드를 재사용하거나 새 코드를 추가해야 한다.

### 결정 내용

`PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(PAYMENT-409-3, 409 Conflict)를 신규 추가한다.

### 근거

- PG 응답 mismatch(외부 원인, 400)와 호출자 측 재요청 mismatch(내부 원인, 409)는 모니터링/알람 기준이 다르다.
- 409 Conflict는 "이미 기록된 상태와 충돌한다"는 HTTP 의미와 정확히 맞는다.
- 에러 코드를 공유하면 핸들러에서 두 원인을 분기할 수 없다.

### 결과

- 에러 코드가 1개 추가된다.
- 발생 원인별 분리 모니터링이 가능해진다.

---

## ADR-B: amount 검증은 catch 블록에만 위치

### 배경

amount 검증을 catch 블록에만 둘지, `save()` 전 pre-check(select)도 함께 둘지 결정이 필요했다.

### 결정 내용

catch 블록에만 둔다. `save()` 전 pre-check는 추가하지 않는다.

### 근거

- `save()` 성공 경로는 충돌이 없었던 케이스라 검증 대상이 없다.
- pre-check를 추가하면 일반 경로(충돌 없음)에서 매번 불필요한 SELECT 쿼리가 발생한다.
- `Propagation.NOT_SUPPORTED`에서 `save()` commit 직후 unique 위반이 잡히므로 catch 블록 한 곳으로 충분하다.

### 결과

- 정상 경로 성능에 영향 없음.
- 기존 코드 구조(save-then-catch 패턴)가 그대로 유지된다.

---

## ADR-C: 기존 attempt status와 무관하게 mismatch 거부

### 배경

기존 attempt가 FAILED 상태인 경우, amount를 바꾼 재시도를 허용할지 여부를 결정해야 했다.

### 결정 내용

REQUESTED/FAILED/SUCCEEDED 상태와 무관하게 amount mismatch면 예외를 던진다.

### 근거

- 멱등성 계약은 "같은 키 → 같은 결과"다. amount가 다르면 사실상 다른 요청이다.
- amount를 바꾸려면 새 `merchantPayKey`로 새 요청을 발급하는 것이 정상 흐름이다.
- FAILED에만 예외를 두지 않으면 "FAILED면 amount 수정 가능"이라는 암묵적 규칙이 생겨 멱등성 계약이 흐려진다.

### 결과

- 일관된 정책이 유지된다.
- 호출자가 잘못된 amount로 재시도하면 즉시 4xx로 실패해 디버깅이 빠르다.

---

## ADR-D: 파라미터 명명을 엔티티 필드 기준으로 통일

### 배경

`PaymentAttemptService`의 일부 메서드 파라미터가 `pgPaymentId`로, 다른 메서드와 엔티티 필드는 `paymentId`로 혼재한다.
`Payment.pgPaymentId` 필드와 `NaverPayApproveResponse.pgPaymentId`는 "우리 내부 도메인에서 외부 결제 ID를 부르는 이름"이고,
`PaymentAttempt.paymentId`는 PG API 스펙 그대로의 외부 명명이다.

### 결정 내용

`PaymentAttemptService`의 `succeedApproveAttempt`, `failApproveAttempt` 파라미터를 `paymentId`로 통일한다.
`Payment.pgPaymentId`, `NaverPayApproveResponse.pgPaymentId`는 건드리지 않는다.

### 근거

- `PaymentAttempt` 엔티티 필드명(`paymentId`)과 서비스 파라미터명이 일치해야 코드 추적이 자연스럽다.
- 두 이름의 의도된 분리는 유지된다. (PG API 스펙 명명 ↔ 내부 도메인 명명)

### 결과

- 같은 파일 안에서 파라미터 명명이 일관해진다.
- 호출자(`NaverPayApprovalService`)는 `attempt.getPaymentId()`를 그대로 전달하므로 변경 불필요.
