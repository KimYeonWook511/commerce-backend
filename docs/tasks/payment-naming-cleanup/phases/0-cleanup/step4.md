# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/payment-naming-cleanup/prd.md`
- `/docs/tasks/payment-naming-cleanup/adr.md`
- Step 3에서 rename된 서비스/에러코드 결과 (`src/main/java/com/commerce/payment/**`)
- `/docs/architecture.md`
- `/docs/exception-strategy.md`
- `/docs/testing-conventions.md`

## 작업

현재 구조를 기술하는 루트 docs를 Step 1~3 변경에 맞춰 동기화한다. **현재 코드 구조를 기술하는 문장만** 갱신하고, 역사 기록은 건드리지 않는다.

### `docs/architecture.md`

- 서비스 목록·결제 흐름 설명의 `PaymentApprovalAttemptService` → `PaymentApprovalRecordService`, `PaymentCancellationAttemptService` → `PaymentCancellationService` 로 갱신한다 (해당 라인 인근의 "승인 시도 상태 반영"/"취소 시도 이력 기록" 같은 한국어 설명은 의미가 맞으면 유지, 클래스명만 정정).

### `docs/exception-strategy.md`

- "현재 적용 대상" 서비스 목록의 두 클래스명을 갱신한다.
- `PaymentApprovalAttemptService.failIfRequested` 등 메서드 참조의 클래스명을 갱신한다.
- 예시 시그니처 `PgCanceller.cancel(cancelAttempt, cancelReason)` 의 변수명을 Step 3 결과(`cancelPayment`)와 일치시킨다.
- "approve attempt" 같은 영어 표현이 `Payment` 를 가리키면 `approve payment` 로 다듬되, 한국어 "시도" 는 유지한다.

### `docs/testing-conventions.md`

- 예시 코드 `then(pgCanceller).should().cancel(eq(cancelAttempt), ...)` 의 변수명을 Step 3 결과와 일치시킨다.
- `countAttempts` 등 실제 테스트 식별자를 참조하는 문장은 Step 3에서 그 식별자가 바뀐 경우에만 함께 갱신한다. 바뀌지 않았으면 그대로 둔다.

### 건드리지 않는 문서

- `docs/ADR.md`: 과거 ADR 서술(ADR-010/012/013/018 등 PaymentAttempt 시절 기록)과 `payment-attempt-*` task 폴더명 참조는 역사 기록이라 수정하지 않는다.
- `docs/logging-conventions.md` 의 "retry attempt={}", `docs/db-schema.md` 의 outbox `attempt_count`: payment과 무관한 영어 단어라 수정하지 않는다.
- 머지된 task 폴더(`docs/tasks/payment-order-redesign/` 등)와 migration 파일: 불변.

## Acceptance Criteria

```bash
./gradlew test
```

```bash
! rg -q "PaymentApprovalAttemptService|PaymentCancellationAttemptService" docs/architecture.md docs/exception-strategy.md docs/testing-conventions.md
```

## 검증 절차

1. `./gradlew test` 통과를 확인한다 (문서 변경이라 영향 없어야 함).
2. 두 번째 커맨드 결과가 0건인지 확인한다 (현재 구조 기술 문서에 옛 클래스명이 남지 않음).
3. `docs/ADR.md` 가 변경되지 않았는지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/ADR.md` 의 과거 ADR 서술을 소급 수정하지 마라. 이유: 결정 당시 상태(PaymentAttempt)를 기록한 역사다.
- 머지된 task 폴더 문서를 수정하지 마라. 이유: 완료된 task 문서 불변 원칙.
- outbox `attempt_count`, "retry attempt" 를 바꾸지 마라. 이유: payment과 무관한 영어 단어다.
- 기존 테스트를 깨뜨리지 마라.
