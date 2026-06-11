# Step 2: terminal-transition-optimistic-absorb

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-optimistic-lock/prd.md`
- `/docs/tasks/payment-optimistic-lock/adr.md` (특히 ADR-L2 — 충돌 처리 정책)
- `/docs/exception-strategy.md` (**필독** — DAO 예외 catch 금지, `OptimisticLockingFailureException → 409` 핸들러, 보상 catch 안 메서드는 예외 안 던지게 설계)
- `/src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java` (`OptimisticLockingFailureException → 409` 핸들러가 이미 존재 — line 63 근처)
- `/src/main/java/com/commerce/payment/domain/Payment.java` (Step 1에서 `@Version` 추가됨)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalRecordService.java` (`fail`/`markUnknownIfRequested`/`failIfPending`)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval`/`succeedApprovalRecordOnly` — succeed 경로)
- `/src/main/java/com/commerce/payment/application/PaymentCancellationService.java` (CANCEL 경로의 `succeed`/`fail`/`markUnknownIfRequested`)
- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (대사 본 루프 `reconcile()`의 건별 `catch (Exception)` 격리 — line 78~89; `fail` 호출 지점)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (보상에서 `failIfPending` 호출)
- `/src/main/java/com/commerce/payment/infrastructure/NaverPayApprovalService.java` (실시간 승인/실패 반영의 상위 catch 구조)
- `/src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalServiceConcurrencyTest.java` (허용 예외 목록 — 회귀 점검)
- `/src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalRecordServiceConcurrencyTest.java`
- `/src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryApprovedConcurrencyTest.java`
- `/docs/tasks/approval-concurrency-guard/adr.md` (application에서 `ObjectOptimisticLockingFailureException` 처리 선례)

Task 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/testing-conventions.md` (`@Tag` 분류, 동시성 테스트 규칙)
- `/docs/logging-conventions.md`

이전 step에서 만들어진 코드(Step 1의 `@Version`)와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`@Version` 도입의 핵심 효과는 같은 행 동시 전이의 **lost update 차단**이다. 충돌(`OptimisticLockException`/`ObjectOptimisticLockingFailureException`)이 났을 때 "진 쪽"을 어떻게 처리할지는 **메서드 의도**로 가른다. 기존 예외 처리 정책과 정합해야 한다(`docs/exception-strategy.md`).

기존에 이미 있는 두 메커니즘을 **재사용**한다(새로 만들지 않는다):
- `GlobalExceptionHandler`의 `OptimisticLockingFailureException → 409`(COMMON-409-1) 핸들러 — HTTP 경로에서 충돌이 전파되면 자동 409.
- `PaymentReconciliationService.reconcile()` 대사 본 루프의 건별 `catch (Exception)` — 대사 경로에서 충돌이 전파되면 그 건 skip + 다음 건 진행.

### 1. 전이 경로 전수 점검 + 메서드 의도별 처리

`@Version`이 모든 payment UPDATE에 붙으므로, 전이를 하는 모든 경로를 점검해 메서드 의도대로 처리한다.

- **조건부 skip 메서드 → `OptimisticLockException` 내부 흡수(skip)**:
  - `PaymentApprovalRecordService.markUnknownIfRequested` / `failIfPending`
  - `PaymentCancellationService.markUnknownIfRequested` (CANCEL 경로도 동일)
  - 이 메서드들은 이미 "조건 안 맞으면 skip"(`IfRequested`/`IfPending`) 의도를 이름에 박았고 보상·best-effort 경로(예: `PaymentApprovalCompensationService`의 catch 안)에서 호출되므로 예외를 던지면 안 된다. `OptimisticLockException`(이미 다른 주체가 전이 = 단조 종착)도 같은 "skip" 의미로 흡수한다. 흡수 시 인프라 예외 타입 직접 의존은 최소화한다(`approval-concurrency-guard`의 `ObjectOptimisticLockingFailureException` 처리 선례 참고). `log.warn`/`log.info`로 skip 사실을 남긴다(기존 "skipping" 로그 패턴과 결 맞춤).
- **무조건 전이 메서드 → 전파(새 catch 심지 않음)**:
  - `PaymentApprovalRecordService.fail`, `PaymentCancellationService.fail`, `PaymentApprovalService.succeedApproval`/`succeedApprovalRecordOnly`, `PaymentCancellationService.succeed`
  - 충돌을 catch하지 않고 그대로 전파한다. HTTP 경로는 기존 `OptimisticLockingFailureException → 409` 핸들러가, 대사 경로는 `reconcile()` 본 루프의 건별 `catch (Exception)`가 받는다. **application/adapter에 새 try-catch를 추가하지 않는다**(DAO 예외 catch 금지 원칙).
  - 기존 사전 find 멱등 흡수(`status == SUCCEEDED`면 return 등)는 그대로 둔다.
  - **확인됨 — `succeedApproval` 전파의 종착(`NaverPayApprovalService.completeVerifiedApproval`)**: 보상(PG cancel)은 `catch (PaymentException)`의 errorCode switch(`MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`/`DUPLICATE`)에서만 트리거된다. `OptimisticLockException`은 `PaymentException`이 아니라 세 번째 `catch (Exception)`에 잡혀 `log.error` + 재전파만 되므로(→ GlobalExceptionHandler 409) **보상이 잘못 트리거되지 않는다.** 이 구조를 바꾸지 말 것(보상 switch에 `OptimisticLockException`/`OptimisticLockingFailureException` 분기를 추가하지 않는다).

> 도메인 메서드(`Payment.fail`/`markUnknown`/`succeed`)의 기존 메모리 상태 가드는 건드리지 않는다. 사전 find 가드와 save 시점 `@Version`은 멱등의 두 시점이다.

### 2. 기존 동시성 테스트 회귀 점검 + 신규 테스트

- **기존 테스트 회귀**: `PaymentApprovalServiceConcurrencyTest`(같은 orderId 동시 `succeedApproval`, succeed vs succeed)는 order `findByIdForUpdate` 비관 락으로 직렬화되므로 `@Version` 충돌이 새로 나지 않아야 한다(기본 기대: "직렬화로 충돌 안 남"). `PaymentApprovalRecordServiceConcurrencyTest`/`PaymentRepositoryApprovedConcurrencyTest`도 통과 유지. 환경상 새 충돌이 관찰되면 허용 예외 목록 조정을 검토하되, 무조건 전이 경로의 충돌은 전파(409/루프 격리)가 기대 동작이다.
- **신규 동시성 테스트** (`@Tag("concurrency")`, 실 DB): 같은 APPROVE Payment 행(REQUESTED)에 대해 한 스레드는 `succeed`, 다른 스레드는 `fail`(또는 `markUnknownIfRequested`)을 `CountDownLatch`로 동시 시도할 때:
  - 정확히 한쪽만 커밋 성공하고 다른쪽은 `OptimisticLockException`이 발생한다(lost update 차단의 핵심 검증).
  - 무조건 전이(`fail`/`succeed`)가 진 경우 예외가 전파되고, 조건부 skip(`markUnknownIfRequested`)이 진 경우 흡수(예외 없이 skip)됨을 메서드 의도대로 확인한다.
  - 최종 상태가 한쪽 전이로만 확정되고 나중 커밋이 앞을 덮는 lost update가 없음을 검증한다.
  - 기존 동시성 테스트 셋업 패턴(`startLatch`/`doneLatch`/`ExecutorService`/`ConcurrentLinkedQueue<Throwable>`)을 따른다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 조건부 skip 메서드(`*IfRequested`/`*IfPending`)는 `OptimisticLockException`을 흡수(skip)하고, 무조건 전이 메서드(`fail`/`succeed`)는 전파하는가?
   - 무조건 전이 경로에 새 try-catch를 심지 않았는가(기존 409 핸들러·대사 루프 격리에 위임)?
   - 조건부 skip 흡수가 APPROVE·CANCEL 양쪽 경로에 일관 적용됐는가?
   - 신규 succeed-vs-fail 동시성 테스트가 lost update 부재(한쪽만 성공)를 검증하는가?
   - 기존 동시성 테스트가 회귀 없이 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 무조건 전이 메서드(`fail`/`succeed`/`succeedApproval`/cancel `succeed`)에 `OptimisticLockException`을 흡수하는 try-catch를 새로 심지 마라. 이유: DAO 예외 catch 금지 원칙 위반이고, HTTP는 기존 `OptimisticLockingFailureException → 409` 핸들러, 대사는 본 루프 건별 catch가 이미 격리한다(중복).
- `succeed` 충돌을 흡수하지 마라. 이유: succeed가 졌을 때 상대가 FAILED/UNKNOWN이면 "과금됐는데 실패로 기록"한 모순을 삼킨다. 전파해서 드러내야 한다(ADR-L2).
- 조건부 skip 흡수를 APPROVE 경로에만 적용하고 CANCEL 경로를 빼지 마라. 이유: `@Version`은 모든 row에 붙어 CANCEL 종착 전이도 충돌 가능하다. 비대칭을 두면 CANCEL 보상 경로에서 예외가 새 보상 흐름을 깬다(ADR-L2).
- CANCEL 전용 동시 충돌 재현 테스트를 이 step에서 새로 만들지 마라. 이유: CANCEL 대사가 미구현이라 충돌 시나리오가 없어 인위적 테스트가 된다. 메커니즘 검증은 APPROVE succeed-vs-fail 테스트가 담당하고, CANCEL 전용 테스트는 Epic #208로 위임한다.
- 도메인 메서드(`Payment.fail`/`markUnknown`/`succeed`)의 기존 메모리 상태 가드를 제거하지 마라. 이유: 사전 find 멱등과 save 시점 `@Version` 멱등은 상호보완이다.
- `OptimisticLockException` 처리를 위해 자동 재시도 루프를 두지 마라. 이유: 단조 종착이라 재시도가 아니라 흡수(조건부 skip) 또는 전파(무조건)가 맞다(ADR-L1/L2).
- 기존 테스트를 깨뜨리지 마라.
