# Step 3: escalation-to-domain-method

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-optimistic-lock/prd.md`
- `/docs/tasks/payment-optimistic-lock/adr.md` (특히 ADR-L3 — escalation 환원; ADR-L2 — 충돌 처리 정책)
- `/docs/exception-strategy.md` (DAO 예외 catch 금지, `OptimisticLockingFailureException → 409` 핸들러 — escalation 충돌 skip이 정책과 정합한지 확인)
- `/src/main/java/com/commerce/payment/domain/Payment.java` (Step 1의 `@Version`, `escalatedAt` 필드)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`escalateIfPending` 시그니처 — 제거 대상)
- `/src/main/java/com/commerce/payment/infrastructure/JpaPaymentRepository.java` (`escalateIfPending` 조건부 UPDATE 구현 — 제거 대상)
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (`escalateIfPending` 위임 — 제거 대상)
- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (`processEscalations` — 통지 흐름 전환 대상)
- `/src/main/java/com/commerce/payment/application/port/NotificationPort.java`
- `/src/test/java/.../PaymentEscalationConcurrencyTest.java` (영향 행 수=1 검증 — 예외 흡수 방식으로 갱신 대상)
- `/docs/tasks/payment-escalation/adr.md` (이번에 supersede하는 escalation 멱등 메커니즘 결정)
- `/docs/tasks/payment-escalation/prd.md` (escalation 의도·`escalatedAt` 직교 필드 — 유지 부분 확인)

Task 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다(이 step이 건드리는 영역).

- `/docs/testing-conventions.md` (`@Tag` 분류, 동시성 테스트 — `PaymentEscalationConcurrencyTest` 갱신)
- `/docs/logging-conventions.md` (escalation 통지·skip 로그 레벨)

이전 step에서 만들어진 코드(`@Version`, Step 2의 흡수/전파)와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

escalation의 멱등 메커니즘을 조건부 UPDATE(CAS)에서 `Payment.escalate()` 도메인 메서드 + `@Version`으로 환원한다. `escalatedAt`을 status와 무관한 직교 필드로 두는 것, status를 늘리지 않는 것, 통지가 commit 후 best-effort인 것은 **유지**한다. 바뀌는 것은 멱등 메커니즘(영향 행 수 → `@Version`)과 규칙 위치(SQL WHERE → 도메인 메서드)뿐이다.

### 1. `Payment.escalate()` 도메인 메서드

- `Payment`에 `escalate(LocalDateTime now)` 도메인 메서드를 추가한다. 가드를 엔티티 안에 둔다:
  - escalation 가능 상태(`status IN (UNKNOWN, REQUESTED)`)가 아니면 escalation 대상이 아님 — 다른 전이 메서드(`fail`/`markUnknown`)의 가드와 결을 맞춘다.
  - 이미 `escalatedAt`이 채워져 있으면 멱등(이미 escalation됨).
  - 가드 통과 시 `escalatedAt = now`로 설정한다(status는 바꾸지 않는다 — 직교 필드).
- "통지 주체인지"를 application이 판단할 수 있게 한다(예: escalation을 실제로 수행했으면 그 사실이 드러나도록). 표현 방식(boolean 반환 / 가드 후 set 등)은 구현에 맡기되, 다른 전이 메서드와의 일관성을 고려한다.

### 2. `escalateIfPending`(조건부 UPDATE/CAS) 제거

- `PaymentRepository`(port), `JpaPaymentRepository`(`@Modifying @Query` 조건부 UPDATE), `PaymentRepositoryAdapter`에서 `escalateIfPending`을 제거한다.
- escalation 후보 조회(`findEscalationCandidates`)는 read 경로라 그대로 둔다.

### 3. escalation을 transition + useCase 구조로 전환 (ADR-L2 적용)

- **escalate transition**(별도 빈의 public `@Transactional` 메서드 — 예: `PaymentApprovalRecordService.escalate(...)`): `find → escalate() → saveChecked`. 충돌은 catch 안 함 → `PAYMENT_CONCURRENTLY_MODIFIED` 전파. 사전 find에서 이미 `escalatedAt != null`이거나 status가 종착이면 escalation 대상이 아니다(도메인 가드가 예외 또는 no-op).
- **useCase `processEscalations`**(`PaymentReconciliationService`, **트랜잭션 없음**): escalation 후보별로 transition.escalate를 호출한다. **정상 완료 = 이 건이 통지 주체 → 커밋 이후 통지**. private 래퍼에서 `PAYMENT_CONCURRENTLY_MODIFIED`(다른 주체가 먼저 escalation)를 catch → skip(통지 안 함). transition이 **별도 빈**이라 `@Transactional`이 적용되고 충돌 시 그 트랜잭션만 롤백된다.
  - 함정(ADR-L2): escalate transition은 `processEscalations`와 **별도 빈**(self-call 금지). `processEscalations`(useCase)에는 `@Transactional`을 달지 않는다.
- **충돌 skip의 로그 레벨**: escalation 충돌은 "이미 다른 주체가 escalation" = 정상 skip이지 처리 실패가 아니다. `processEscalations`의 기존 건별 `catch (Exception) { log.error("escalation 처리 실패" ...) }`가 이 충돌을 ERROR로 남기지 않도록, skip 래퍼가 `PAYMENT_CONCURRENTLY_MODIFIED`를 **먼저 구분해 `log.debug`/`log.info`로** 처리한다. 그 외 진짜 처리 실패만 기존 `log.error` 경로로.
- 통지는 commit 이후 best-effort(try/catch, 전송 실패가 트랜잭션·루프를 막지 않음, `log.warn`)를 유지한다. 통지를 커밋 **전**에 보내지 않는다.
- `NotificationPort` 호출(`notifyManualReviewRequired` 등) 시그니처·reason 문자열은 기존 escalation 통지와 동일하게 유지한다.

### 4. 동시성 테스트 갱신

- 기존 `PaymentEscalationConcurrencyTest`는 "N스레드 동시 `escalateIfPending` → 영향 행 수 합계=1"을 검증한다. `escalateIfPending`이 사라지므로, 같은 escalation 건에 N스레드가 동시에 `find → escalate() → save`를 시도할 때 **정확히 한 스레드만 save 성공(= 통지 주체)하고 나머지는 `OptimisticLockException`으로 skip**됨을 검증하는 방식으로 갱신한다(통지 1회 보장). `@Tag("concurrency")` 유지.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `escalateIfPending`이 port/adapter/Jpa repository에서 완전히 제거됐는가? (잔재 없음)
   - escalation 규칙(`status IN (UNKNOWN,REQUESTED)`, `escalatedAt IS NULL` 멱등)이 `Payment.escalate()` 도메인 메서드 안으로 옮겨졌는가?
   - escalation 시 status를 바꾸지 않고 `escalatedAt`만 기록하는가? (직교 필드 유지)
   - 통지가 save 성공(커밋) 이후 best-effort로, 주체 1회만 호출되는가?
   - 갱신된 동시성 테스트가 "정확히 1회 통지(1 스레드만 주체)"를 검증하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `escalateIfPending` 조건부 UPDATE를 남겨두거나 `version = version + 1` 수동 bump 방식으로 유지하지 마라. 이유: `@Version`이 생긴 이상 규칙을 도메인 메서드로 환원하는 게 다른 전이와 일관된다(ADR-L3). CAS 잔존은 비대칭을 남긴다.
- escalation 시 `status`를 바꾸거나 새 status를 도입하지 마라. 이유: `escalatedAt`은 status와 무관한 직교 필드다(`payment-escalation` 결정 유지).
- 통지를 save 커밋 **전**에 보내지 마라. 이유: 통지 후 save 실패 시 다음 주기에 중복 통지된다.
- 통지를 `OptimisticLockException`으로 진(save 실패) 건에 보내지 마라. 이유: 그건 다른 트랜잭션이 이미 escalation 주체라는 뜻이다(중복 통지).
- 통지 전송 실패가 트랜잭션을 롤백하거나 루프를 중단하게 하지 마라. 이유: 통지는 best-effort이고 진실 원천은 `escalatedAt`이다.
- `findEscalationCandidates`(후보 조회)를 제거하지 마라. 이유: 후보 스캔은 여전히 필요하다. 제거 대상은 조건부 UPDATE(`escalateIfPending`)뿐이다.
- 기존 테스트를 깨뜨리지 마라.
