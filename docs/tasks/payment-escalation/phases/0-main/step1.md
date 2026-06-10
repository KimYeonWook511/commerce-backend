# Step 1: escalation-terminate-notify

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-escalation/prd.md`
- `/docs/tasks/payment-escalation/adr.md`
- `/docs/tasks/payment-escalation/db-schema.md`
- `/src/main/java/com/commerce/payment/domain/Payment.java`
- `/src/main/java/com/commerce/payment/domain/PaymentStatus.java`
- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java`
- `/src/main/java/com/commerce/payment/application/PaymentApprovalRecordService.java` (조건부 상태 전이 패턴 참고 — `failIfPending`/`markUnknownIfRequested` 류)
- `/src/main/java/com/commerce/payment/application/port/NotificationPort.java`
- `/src/main/java/com/commerce/payment/infrastructure/LogNotificationAdapter.java`
- `/src/main/java/com/commerce/payment/infrastructure/JpaPaymentRepository.java`
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `/src/main/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java`
- `/src/main/resources/db/migration/V7__add_payment_reservation_version.sql` (직전 마이그레이션 — 다음 번호 확인)

Task 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/adr.md` (ADR-039, ADR-044, ADR-047 — status 모델·escalation 윈도우)
- `/docs/db-schema.md` (`tbl_payment` 현재 스키마)
- `/docs/tasks/unknown-reconciliation/adr.md` (ADR-L2/L5/L8 — 멱등성 방어·escalation 윈도우의 직전 결정)

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

6시간 초과 UNKNOWN/REQUESTED APPROVE 결제를 발견해 운영자에게 통지하고 종착 표시(`escalatedAt` 기록)한다. status는 바꾸지 않는다(ADR-L1). 멱등(중복 통지 방지)은 **조건부 UPDATE의 DB 레벨 원자성**으로 보장하며, 메모리 객체 상태 검사 가드에 의존하지 않는다(다중 인스턴스/동시 트랜잭션 race를 막아야 함). 아래를 한 step에서 완성한다.

### 1. Flyway 마이그레이션

- 파일: `src/main/resources/db/migration/V8__add_payment_escalated_at.sql`
- 내용: `tbl_payment`에 `escalated_at DATETIME(6) NULL` 컬럼 추가. nullable이라 백필 불필요.

### 2. `Payment` 도메인 — `escalatedAt` 필드

- `Payment`에 `escalatedAt`(`LocalDateTime`, nullable) 필드 + getter를 추가한다. 기존 `respondedAt`과 같은 타임스탬프 계열로 매핑한다.
- escalation 종착의 상태 변경은 **조건부 UPDATE(아래 3)** 가 담당하므로, 메모리 상태를 바꾸는 `escalate()` 도메인 메서드는 두지 않는다(두면 멱등을 메모리 가드로 착각할 여지가 생긴다). status는 어떤 경로로도 바뀌지 않는다.

### 3. escalation 스캔 쿼리 + 조건부 UPDATE (repository)

- **스캔 쿼리** (1차 후보 필터, 기존 `findStale...`와 별도): `type='APPROVE' AND escalatedAt IS NULL AND ( (status='UNKNOWN' AND respondedAt < :escalationCutoff) OR (status='REQUESTED' AND createdAt < :escalationCutoff) )`. `ORDER BY id ASC`, `Pageable` 배치 상한. 기존 `findStaleApprovePaymentsForReconciliation`은 건드리지 않는다.
  - UNKNOWN은 `respondedAt`, REQUESTED는 `createdAt` 기준 6시간(기존 정책·`findStale`과 동일 기준).
- **조건부 UPDATE** (멱등의 진실 원천): `@Modifying` 쿼리로 `UPDATE Payment p SET p.escalatedAt = :now WHERE p.id = :id AND p.escalatedAt IS NULL AND p.status IN ('UNKNOWN','REQUESTED')`. **영향 행 수(int)를 반환**한다. 기존 조건부 전이 패턴(`markUnknownIfRequested`/`failIfPending`)과 같은 스타일을 따른다.
- `PaymentRepository`(port)와 `PaymentRepositoryAdapter`에 스캔 메서드와 조건부 UPDATE 메서드를 추가한다.

### 4. `reconcile()` 흐름에 escalation 처리 통합

- `PaymentReconciliationService`에 `NotificationPort`를 주입한다(현재 없으면 추가).
- `ESCALATION_DELAY`(6시간)는 기존처럼 `PaymentPostProcessTargetPolicy.ESCALATION_DELAY`와 단일 출처를 공유한다.
- `reconcile()`의 기존 stale 처리 루프가 끝난 뒤, escalation 후보를 조회해 건별로 처리한다:
  1. 조건부 UPDATE를 호출하고 **영향 행 수**를 받는다(건별 단건 트랜잭션, 기존 대사 트랜잭션 경계 패턴과 동일).
  2. **영향 행 수가 1일 때만** "내가 escalate 주체"로 보고, 커밋 이후 `notificationPort.notifyManualReviewRequired(orderId, merchantPayKey, reason)`로 통지한다. `reason` 예: `"escalation: 6시간 초과 미확정 APPROVE"`. 영향 행 수가 0이면(이미 다른 주체가 처리) 통지하지 않는다.
  3. 통지는 best-effort다: try/catch로 감싸 전송 실패가 트랜잭션·루프를 막지 않게 하고 `log.warn`만 남긴다(ADR-L6, 기존 보상 통지 패턴 참고).
- 별도 스케줄러를 만들지 않는다. escalation은 같은 `reconcile()` 진입점 안에서 처리한다.

### 5. 테스트

- **통합 테스트** (`@Tag("docker")`, 실 DB): escalation 스캔이 6시간 초과 UNKNOWN/REQUESTED를 잡고, 조건부 UPDATE 후 `escalatedAt`이 기록되며, 같은 건이 다음 스캔에서 `escalatedAt IS NULL` 필터로 제외됨. 6시간 미만·이미 escalated 건은 잡지 않음. 기존 통합 테스트(`PaymentReconciliationIntegrationTest`/`ReconciliationScanQueryIntegrationTest`) 패턴을 따른다.
- **동시성 테스트** (`@Tag("concurrency")`, 실 DB): 같은 escalation 건에 대해 N개 스레드가 `CountDownLatch`로 동시에 조건부 UPDATE를 시도할 때, **정확히 1개 스레드만 영향 행 수 1을 받고 나머지는 0을 받음**을 검증한다(→ 통지 1회 보장). 기존 동시성 테스트(`@Tag("concurrency")`) 패턴을 따른다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `PaymentStatus`가 여전히 4개(`REQUESTED/SUCCEEDED/FAILED/UNKNOWN`)인가? (새 status 미도입 — ADR-044)
   - escalation 경로에서 `status`를 바꾸는 코드가 없는가? (`escalatedAt`만 기록)
   - 멱등이 메모리 가드가 아니라 조건부 UPDATE의 영향 행 수로 보장되는가?
   - 통지가 영향 행 수 1일 때만, escalate 커밋 이후 best-effort로 호출되는가?
   - 기존 `findStaleApprovePaymentsForReconciliation`이 변경되지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `PaymentStatus`에 새 값을 추가하거나 escalation 시 status를 바꾸지 마라. 이유: ADR-044 — status는 UNKNOWN/REQUESTED 유지, escalation은 `escalatedAt` 직교 필드로만 표현한다.
- escalation 멱등을 메모리 객체 상태 검사(예: 로드한 `Payment.escalatedAt == null` 확인 후 save)로 보장하지 마라. 이유: 두 트랜잭션이 동시에 `null`을 읽으면 둘 다 통지한다(Payment에 `@Version` 없음). 멱등은 조건부 UPDATE의 영향 행 수로만 판단한다.
- 통지를 영향 행 수가 0인 건에 보내지 마라. 이유: 영향 0은 이미 다른 주체가 escalate했다는 뜻이다(중복 통지).
- 통지를 조건부 UPDATE 커밋 **전**에 보내지 마라. 이유: 통지 후 UPDATE 실패 시 다음 주기에 중복 통지된다.
- 통지 전송 실패가 트랜잭션을 롤백하거나 루프를 중단하게 하지 마라. 이유: 통지는 best-effort이고 진실 원천은 `escalatedAt`이다(ADR-L6).
- 기존 `findStaleApprovePaymentsForReconciliation` 쿼리에 escalation 로직을 섞지 마라. 이유: 자동 대사 대상(1분~6시간)과 escalation(>6시간)은 다른 관심사다. 별도 쿼리로 둔다.
- CANCEL 타입을 escalation 스캔에 넣지 마라. 이유: CANCEL 대사 자체가 미구현이라 범위 밖이다(별도 이슈).
- 기존 테스트를 깨뜨리지 마라.
