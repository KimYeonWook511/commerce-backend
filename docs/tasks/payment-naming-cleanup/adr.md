# 태스크 ADR

## ADR-1: mark 계열 도메인 메서드를 동사형으로 일관화한다

### 배경

- `Payment` 안에서 `succeed`/`fail` 은 동사인데 `markUnknown` 만 `mark` 접두사라 패턴이 갈렸고, 그 `mark` 가 `PaymentReservation.markUsed`/`markExpired` 까지 번졌다.
- 후보: (a) 동사화(`use`/`expire`) (b) `mark*` 통일(`markSucceeded`/`markFailed` 까지).

### 결정 내용

- (a) 동사화를 채택한다. `markUsed` → `use`, `markExpired` → `expire`.
- `succeed`/`fail` 은 이미 동사이므로 유지한다.
- `markUnknown` 은 "결과 불명" 에 마땅한 동사가 없어 `mark` 를 정직한 표현으로 유지한다.

### 근거

- 멀쩡한 동사(`succeed`/`fail`)를 `mark*` 로 끌어내리는 대신, 어색한 쪽(`markUsed`/`markExpired`)을 동사로 올린다.
- 코드베이스의 도메인 행위 중심 네이밍 우선 원칙과 일관된다.

### 결과

- 결과/상태 반영 메서드가 `succeed`/`fail`/`markUnknown`/`use`/`expire` 로 정돈된다. `markUnknown` 1개만 예외로 남지만 동사 부재로 수용한다.
- NULL 트릭 캡슐화(상태 컬럼과 트릭 컬럼 동시 set)는 메서드 본문 그대로 보존한다.

## ADR-2: 옛 `PaymentAttempt` 식별자 잔재를 전면 제거하고 서비스/에러코드 식별자를 정돈한다

### 배경

- 엔티티는 `Payment` 인데 변수·repo 메서드·서비스 클래스·에러코드 식별자가 옛 `attempt` 네이밍을 유지했다.
- ADR(payment-order-redesign)은 "Payment = PG에 보낸 시도 단위 기록" 으로 시도 개념을 인정하지만, 엔티티는 `PaymentAttempt` → `Payment` 로 의도적으로 rename됐다.

### 결정 내용

- `Payment` 타입을 가리키는 식별자에서 `attempt` 를 전면 제거한다.
  - 변수/파라미터/필드: `attempt` → `payment`, `approveAttempt` → `approvePayment`, `cancelAttempt` → `cancelPayment`.
  - repo: `findApproveAttempt` → `findApprovePayment`, `findCancelAttempt` → `findCancelPayment`.
  - 처리 메서드: `processApproveAttempt` → `processApprovePayment`.
  - 서비스: `PaymentApprovalAttemptService` → `PaymentApprovalRecordService`, `PaymentCancellationAttemptService` → `PaymentCancellationService`.
  - 에러코드 식별자: `PAYMENT_ATTEMPT_NOT_FOUND` → `PAYMENT_RECORD_NOT_FOUND`, `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` → `PAYMENT_RECORD_AMOUNT_MISMATCH`, `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED` → `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`.
- 에러코드의 `code` 문자열(`PAYMENT-404-2` 등)과 한국어 메시지("결제 시도 이력...")는 외부 계약/자연스러운 도메인 표현이므로 **보존**한다.
- 서비스 클래스명 verb/noun 컨벤션 전면 정리는 이번 작업에서 다루지 않고 별도 후속 이슈로 분리한다.

### 근거

- 식별자가 현재 도메인 모델(`Payment`)과 일치해야 코드 가독성·검색성이 보장된다.
- `PaymentCancellationService` 는 충돌이 없어 깔끔하고, `PaymentApprovalRecordService` 는 기존 `PaymentApprovalService`(승인 성공 오케스트레이션)와 책임을 구분한다.

### 결과

- payment 패키지에 `Payment` 타입을 가리키는 `attempt` 식별자가 사라진다.
- 외부 계약(에러 code·메시지·HTTP status)은 변하지 않는다.

## ADR-3: 정리 경계 — 보존 대상을 명시한다

### 배경

- "attempt" 는 옛 엔티티 잔재(정리 대상)와 진짜 "시도(try)"(보존 대상)가 섞여 있다. 무차별 치환은 의미를 훼손한다.

### 결정 내용

- 보존한다:
  - 진짜 시도(try) 를 뜻하는 식별자: `attackerAttempt`(공격 시도), concurrent/retry attempt 등.
  - 한국어 "시도" 표현(@DisplayName·주석·에러 메시지).
  - test-only `postprocess` 패키지 전체 (배치 도입 시 일괄 정비 예정).
  - 역사 기록: `docs/adr.md` 과거 ADR 서술, 머지된 task 폴더, migration 파일, `logging-conventions.md`/`db-schema.md` 의 outbox `attempt_count`.
- `Payment.succeed()` 의 `failCode`/`failDetail` null 리셋 2줄은 제거한다. succeed 는 REQUESTED 상태에서만 호출되고 REQUESTED 결제는 failCode 가 없으므로 증명 가능한 no-op dead code다.
- `saveAndFlush` 즉시 flush에 의존하는 `succeed`/`succeedApproval` 의 명시 `save()` 호출은 손대지 않는다.

### 근거

- 동작 변경 금지 원칙 — flush 타이밍은 이중결제 보상 catch의 load-bearing 요소다.
- 역사 기록 문서는 결정 당시 상태(PaymentAttempt)를 기록한 것이라 소급 수정하지 않는다.

### 결과

- 정리 범위가 옛 엔티티 식별자에 한정되고, 외부 동작·역사 기록·미래 코드(postprocess)는 영향받지 않는다.
