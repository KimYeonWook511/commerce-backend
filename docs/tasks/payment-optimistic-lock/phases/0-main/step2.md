# Step 2: 낙관 락 충돌 처리 — transition 전파 + useCase skip 래퍼

> 이 step은 #243 초기 구현(application에서 `ObjectOptimisticLockingFailureException` 직접 catch + `@Transactional` 제거 + tx 안 흡수)이 세 정책을 위반해 **재작성**된 명세다. 정본 결정은 `adr.md`의 ADR-L2.

## 읽어야 할 파일

- `/docs/tasks/payment-optimistic-lock/adr.md` (**ADR-L2 필독** — 충돌 처리 구조 / ADR-L1 @Version / ADR-L3 escalation)
- `/docs/exception-strategy.md` (DAO 예외 adapter 변환, "catch 안 메서드는 예외 안 던지게 설계")
- `/src/main/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryAdapter.java` (`saveUsed` — adapter 변환 선례, "충돌 후 tx는 rollback-only" 주석)
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (`saveApproved` 선례, `saveChecked` — **이미 추가됨**)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`saveChecked` 선언 — 이미 추가됨)
- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java` (`PAYMENT_CONCURRENTLY_MODIFIED` — 이미 추가됨)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalRecordService.java` (transition 대상: `fail`/`markUnknownIfRequested`/`failIfPending`)
- `/src/main/java/com/commerce/payment/application/PaymentCancellationService.java` (transition 대상: `succeed`/`fail`/`markUnknownIfRequested`)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval` — 전파 경로, 그대로)
- `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` (useCase — skip 래퍼 배치)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (useCase — `runPgCancel`, skip 래퍼)
- `/docs/testing-conventions.md`, `/docs/logging-conventions.md`

## 핵심 구조 (ADR-L2)

```
useCase (tx 없음)            ── skip 정책을 private 래퍼 메서드로
   │  try { transition.markUnknown(...); }
   │  catch (PaymentException e) { if (SKIPPABLE) skip; else throw; }
   ▼
transition (별도 빈, public @Transactional)   ── find + 도메인 전이 + saveChecked, catch 안 함
   ▼
adapter.saveChecked (saveAndFlush)            ── OptimisticLock → PAYMENT_CONCURRENTLY_MODIFIED 변환 throw
```

**함정(반드시)**: transition은 useCase와 **별도 빈의 public 메서드**(private이면 `@Transactional` 무효, 같은 빈 self-call이면 프록시 우회). useCase에는 `@Transactional`을 **달지 않는다**.

## 작업

### 1. transition service 정리 (`PaymentApprovalRecordService`, `PaymentCancellationService`)

- 종착 전이 메서드의 save를 **`saveChecked`로 교체**하고, **try-catch 흡수를 전부 제거**해 도메인 예외를 전파시킨다(catch 안 함).
- `markUnknownIfRequested`/`failIfPending`의 "사전 find + status skip + try-catch 흡수" 구조를 **transition 형태로 단순화**: `find(orElseThrow) → 도메인 전이(가드는 도메인 메서드가 예외) → saveChecked`. skip 판단은 useCase로 옮긴다.
  - 도메인 가드: `payment.markUnknown()`/`fail()`이 상태 안 맞으면 `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`를 던진다(기존 도메인 메서드 그대로). 이력 없으면 `PAYMENT_RECORD_NOT_FOUND`.
  - 메서드명: 흡수 의미(`IfRequested`/`IfPending`)를 떼고 transition 동사(`markUnknown`/`fail`)로. 호출처(useCase)가 skip을 담당하므로 transition은 "조건 안 맞으면 예외"가 맞다.
- `create`/`getOrCreate`(생성·INSERT)는 **그대로** 둔다(version 무관, unique는 별도 경로).
- `@Transactional`은 transition 메서드(public)에 유지/명시.

### 2. useCase에 private skip 래퍼 (`NaverPayApprovalService`, `PaymentApprovalCompensationService`)

- transition 호출을 private 래퍼로 감싼다:
  ```java
  private static final Set<PaymentErrorCode> SKIPPABLE = EnumSet.of(
      PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED,        // 버전 충돌 = 누가 먼저 전이
      PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED, // 가드 위반 = 이미 종착
      PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND);             // best-effort: 대상 없으면 skip
  private void markUnknownSkippable(...) {
      try { transition.markUnknown(...); }                   // 별도 빈 → 프록시 → tx 열림/롤백
      catch (PaymentException e) {
          if (SKIPPABLE.contains(e.getErrorCode())) { log.warn("...skip"); return; }
          throw e;
      }
  }
  ```
- 기존 호출처(보상 `runPgCancel`, 실시간 UNKNOWN/FAILED 분기)가 이 래퍼를 부르게 한다. 호출처는 평탄(try-catch 없음) 유지.
- useCase 클래스/메서드에 `@Transactional`이 없는지 확인한다.

### 3. 무조건 전이는 전파

- `succeedApproval`(`PaymentApprovalService`), 대사의 무조건 `fail`(`PaymentReconciliationService`)은 skip하지 않는다. `saveChecked`/`saveApproved`의 변환 예외 또는 `OptimisticLockException`이 전파 → `GlobalExceptionHandler` 409 / 대사 본 루프 건별 catch가 받는다. application에 새 try-catch를 심지 않는다.

### 4. 잔재 제거

- application/service에서 `ObjectOptimisticLockingFailureException`을 **직접 import·catch하는 코드를 모두 제거**한다(adapter `saveChecked`가 변환 담당). `PaymentReconciliationService`의 직접 catch도 점검.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

## 검증 절차

1. 위 커맨드 실행.
2. 확인:
   - transition은 별도 빈 public `@Transactional`, useCase는 `@Transactional` 없음?
   - application/service에 `ObjectOptimisticLockingFailureException` 직접 catch가 남아 있지 않은가?
   - skip은 useCase private 래퍼(tx 밖)에서만, `succeed`/무조건 `fail`은 전파?
   - 결정적 충돌 테스트(다음 step 또는 본 step)가 흡수/전파 경로를 version 강제 stale로 검증하는가?
3. 상태 갱신.

## 금지사항

- transition을 private 메서드 또는 useCase와 같은 빈에 두지 마라. 이유: `@Transactional` 무효(private) / self-call 프록시 우회 → 흡수가 tx 안으로 회귀 → `UnexpectedRollbackException`.
- useCase(orchestrator)에 `@Transactional`을 달지 마라. 이유: 흡수 catch가 tx 안으로 들어가 `UnexpectedRollbackException`. 외부호출 tx 밖 원칙도 위반.
- application에서 `ObjectOptimisticLockingFailureException`(DAO 예외)을 직접 catch하지 마라. 이유: adapter `saveChecked`가 도메인 예외로 변환하는 게 코드베이스 정책(`saveUsed`/`saveApproved` 선례).
- `succeed` 충돌을 skip하지 마라. 이유: 상대가 FAILED/UNKNOWN이면 "과금됐는데 실패 기록" 모순을 삼킨다.
- 기존 테스트를 깨뜨리지 마라.
