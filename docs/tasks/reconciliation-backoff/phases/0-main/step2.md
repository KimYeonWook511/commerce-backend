# step2 — wire-backoff-on-wait

## 목표

대사 **쓰기 측**을 배선한다: PG 조회가 "아직 대기"로 끝나는 분기에서 `next_reconcile_at`을
`now + 고정 backoff`로 갱신해, 같은 건이 매 주기 재스캔·재조회되지 않게 한다. step1의 게이트가
이 값을 존중하므로, 이 step으로 starvation·PG 반복 조회가 실제로 해소된다.

## 배경·맥락 (중요)

- 현재 wait 분기는 행을 쓰지 않는다:
  - APPROVE: `processApproveReconcile`의 `KEEP_WAITING` → `SKIPPED`
    (`ReconcilePaymentUseCase.java`).
  - CANCEL: `processCancelReconcile`의 `KEEP_WAITING` → `SKIPPED`,
    `executeCancelRetry`의 `PROCESSING` → `SKIPPED`.
- 상태를 확정하는 분기(succeed/fail/markUnknown)는 backoff 대상이 아니다(ADR-L3) — 이미 행을 써서
  자기 cadence로 재진입을 늦춘다. 거기에 손대지 않는다.
- backoff 값은 단일 고정 간격이다(ADR-L2).

## 구현 지시

### 1) backoff 상수

- `PaymentPostProcessTargetPolicy`에 `RECONCILE_BACKOFF` 상수를 추가한다(예: `Duration.ofMinutes(5)`).
  기존 시간 상수들(`UNKNOWN_RECONCILE_DELAY` 등)과 같은 자리·주석 스타일("운영 config 승격 전제")을
  따른다. 단일 출처로 둔다.

### 2) backoff 기록 service

- `application/service/`에 `@Transactional` service를 신설한다(예: `DelayPaymentReconcileService`).
  대상 결제를 로드해 `payment.delayReconcile(now, RECONCILE_BACKOFF)`를 적용하고, 낙관 락을 거치는
  저장 경로(`saveChecked`)로 영속화한다.
- **로드 finder는 `type`으로 분기해야 한다.** 포트에는 `findApprovePayment(merchantPayKey,
  provider, pgPaymentId)`와 `findCancelPayment(...)`가 **type별로 분리**돼 있다(`PaymentRepository`).
  backoff는 APPROVE wait(1곳)와 CANCEL wait(2곳) **양쪽**에서 호출되므로, 단일 `findCancelPayment`만
  쓰면 APPROVE 건을 못 찾아 `PAYMENT_RECORD_NOT_FOUND`로 흡수돼 **APPROVE backoff가 silent no-op**이
  된다(PRD 성공 기준 "APPROVE·CANCEL 일관 적용" 위반). 따라서 service 메서드는 `PaymentType type`을
  받아 APPROVE면 `findApprovePayment`, CANCEL이면 `findCancelPayment`를 선택한다. 시그니처 예:
  `delay(merchantPayKey, provider, pgPaymentId, type, now)`. (호출부 usecase는 wait 분기에서 해당
  `payment.getType()`을 그대로 넘긴다.)
- `@Transactional`은 `service` 패키지에만 둔다(CLAUDE.md). usecase는 tx를 갖지 않는다.

### 3) usecase 배선 + skip 흡수

- `ReconcilePaymentUseCase`의 wait 분기 세 곳에서 위 service를 호출한다:
  - APPROVE `processApproveReconcile`의 `KEEP_WAITING`
  - CANCEL `processCancelReconcile`의 `KEEP_WAITING`
  - CANCEL `executeCancelRetry`의 `PROCESSING`
- 호출은 기존 `*Skippable` 래퍼 패턴을 따라, `PAYMENT_CONCURRENTLY_MODIFIED`·
  `PAYMENT_RECORD_NOT_FOUND`(있다면 `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`도)를 흡수해 skip하고
  `log.info`만 남긴다. backoff는 best-effort cadence 힌트라 충돌 시 다음 주기에 재시도되면 된다.
- wait로 인한 outcome(`SKIPPED`) 자체는 바꾸지 않는다. backoff 기록은 outcome에 영향을 주지 않는
  부수 효과다.

### 4) 테스트

- **mock 주입 정합(먼저 처리)**: 신설 delay service를 `ReconcilePaymentUseCaseTest`·
  `CancelReconciliationUseCaseTest`에 `@Mock` 필드로 추가해 `@InjectMocks reconcilePaymentUseCase`
  생성자에 주입되게 한다. 누락하면 주입 실패로 두 테스트 클래스 전체가 깨진다. wait가 아닌 기존
  테스트들은 이 mock에 대한 stub 없이도 통과해야 한다(호출 안 되는 경로).
- `ReconcilePaymentUseCaseTest`: APPROVE `KEEP_WAITING`(PG history PENDING)일 때 delay service가
  호출됨을 검증한다(기존 `reconcile_pgHistoryUnknown_doesNotChangeState`류에 backoff 호출 검증
  추가 또는 신규 테스트).
- `CancelReconciliationUseCaseTest`: CANCEL `KEEP_WAITING`과 retry `PROCESSING`에서 각각 delay
  service가 호출됨을 검증한다.
- delay service 단위 테스트: (a) `type=APPROVE`면 `findApprovePayment`, `type=CANCEL`면
  `findCancelPayment`로 로드해 `delayReconcile`이 적용됨을 검증한다(R1 silent no-op 방지 — usecase
  레벨 mock으로는 finder 분기를 못 잡으므로 service 레벨에서 못 박는다). (b) 낙관 락 충돌
  (`PAYMENT_CONCURRENTLY_MODIFIED`)이 호출부에서 흡수되어 대사 루프가 멈추지 않음을 검증한다.
- 상태 확정 분기(succeed/fail/markUnknown)에서는 delay service가 호출되지 않음을 한 테스트로 못
  박는다(범위 회귀 방지).

## 하지 마라

- 상태 확정 분기(succeed/fail/markUnknown)에 backoff를 추가하지 마라. 이유: 이미 행을 써서 자기
  cadence가 있고, 두 시점 필드가 경합한다(ADR-L3).
- 낙관 락 충돌을 tx 안에서 잡거나 재시도하지 마라. 이유: 도메인 예외로 전파 후 tx 밖에서 skip하는
  것이 기존 대사 규율이다(`docs/optimistic-lock-design.md`).
- backoff 값을 usecase·service에 흩뿌리지 마라. 이유: 단일 출처(`PaymentPostProcessTargetPolicy`)로
  둬야 운영 config 승격이 한 군데로 모인다.
- 지수(점증) backoff·시도 카운터를 추가하지 마라. 이유: 단일 고정 값으로 시작한다(ADR-L2).

## 관련 파일

- `src/main/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java` (상수)
- `src/main/java/com/commerce/payment/application/service/` (신설 delay service, 기존 `*CancelPaymentService` 패턴 참고)
- `src/main/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCase.java` (wait 분기 배선·skip 흡수)
- `src/main/java/com/commerce/payment/domain/Payment.java` (step1의 `delayReconcile`)
- `src/test/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCaseTest.java`
- `src/test/java/com/commerce/payment/application/usecase/CancelReconciliationUseCaseTest.java`

## Acceptance Criteria

```bash
./gradlew test --tests "*ReconcilePaymentUseCaseTest"
./gradlew test --tests "*CancelReconciliationUseCaseTest"
./gradlew test --tests "*Reconcil*"
```
