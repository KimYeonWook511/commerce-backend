# step1 — add-next-reconcile-at-scan-gate

## 목표

대사 재조회 backoff의 **읽기 측 토대**를 만든다: 결제 행에 status-직교 필드 `next_reconcile_at`을
추가하고, 스캔 쿼리가 이 필드를 게이트로 존중하게 한다. 이 step만으로는 아무도 필드를 세팅하지
않으므로 동작 변화는 없지만, 게이트가 올바른지 테스트로 확정한다(쓰기 측은 step2).

## 배경·맥락 (중요)

- 현재 스캔(`findStaleApprovePaymentsForReconciliation`,
  `findStaleCancelPaymentsForReconciliation`, `JpaPaymentRepository.java:47, 93`)은
  `status`·시간 윈도우(`staleCutoff`/`requestedStaleCutoff`/`escalationCutoff`)로만 후보를 고른다.
- `escalated_at`(V8, ADR-049)이 따를 패턴이다 — `status` 상태머신을 건드리지 않는 직교 타임스탬프
  필드. `next_reconcile_at`도 같은 방식으로 추가한다.
- `respondedAt`을 재사용하면 안 된다(별도 ADR-L1). escalation·stale 윈도우 계산이 오염된다.

## 구현 지시

### 1) 마이그레이션 V10

- `src/main/resources/db/migration/V10__add_payment_next_reconcile_at.sql` 신설:
  `tbl_payment`에 `next_reconcile_at DATETIME(6) NULL` 컬럼 추가. nullable이라 백필 불필요.
  V8(`escalated_at`)의 주석 스타일을 따른다.

### 2) `Payment` 도메인

- `next_reconcile_at`을 매핑하는 필드 `next_reconcile_at: LocalDateTime`을 추가한다
  (`@Column(name = "next_reconcile_at")`, `escalated_at` 매핑 미러링). `@Getter`로 getter 노출.
- 도메인 메서드 `delayReconcile(LocalDateTime now, Duration backoff)`를 추가한다:
  `this.nextReconcileAt = now.plus(backoff)`만 세팅하고 `status`·`respondedAt` 등 다른 필드는
  바꾸지 않는다. 가드는 두지 않는다 — wait 분기는 `status`를 확정하지 않으므로 어떤 status가 와도
  (UNKNOWN/REQUESTED, 그리고 즉시 재조회되는 FAILED CANCEL 포함 — `PaymentPostProcessTargetPolicy`가
  일부 FAILED CANCEL을 `CANCEL_RECONCILE`로 돌려 `KEEP_WAITING`에 도달할 수 있다) `next_reconcile_at`만
  미루면 된다(ADR-L3).
- `Payment` 생성 빌더·정적 팩토리에는 `next_reconcile_at`을 넣지 않는다(생성 시 항상 NULL = 즉시
  대상).

### 3) 스캔 쿼리 게이트

- `JpaPaymentRepository`의 `findStaleApprovePaymentsForReconciliation`,
  `findStaleCancelPaymentsForReconciliation` 두 `@Query`의 WHERE에 backoff 게이트를 추가한다:
  `AND (p.nextReconcileAt IS NULL OR p.nextReconcileAt <= :now)`. `@Param("now") LocalDateTime now`
  파라미터를 추가한다.
- `PaymentRepository`(포트)와 `PaymentRepositoryAdapter`의 두 메서드 시그니처에 `now`를 더한다.
- `find*EscalationCandidates`는 변경하지 않는다(backoff와 무관).

### 4) 호출부 시그니처 정합

- `ReconcilePaymentUseCase.reconcile()`이 두 스캔 호출에 `now`를 넘기도록 인자를 추가한다(이미
  `now`를 계산해 보유). 이 step에서는 `now` 전달만 하고 backoff 기록(write)은 하지 않는다.
- **시그니처 변경에 깨지는 기존 테스트를 같은 step에서 갱신한다.** 두 스캔 메서드가 4-인자 →
  5-인자(`now` 추가)로 바뀌므로, 아래 테스트의 호출/stub도 모두 5-인자로 고쳐야 컴파일된다.
  누락하면 step1 AC(`compileTestJava`)가 실패한다.
  - `ReconciliationScanQueryIntegrationTest`: 모든 `findStale*PaymentsForReconciliation(...)` 직접
    호출에 `now`(현재시각)를 넘긴다.
  - `ReconcilePaymentUseCaseTest`·`CancelReconciliationUseCaseTest`: `given(...findStale*(...))`
    mock stub의 인자 matcher를 `any(), any(), any(), any(), any(Pageable.class)`로 맞춘다.

### 5) 테스트

- 도메인 단위 테스트: `delayReconcile(now, backoff)`가 `nextReconcileAt`을 `now+backoff`로 세팅하고
  `status`를 바꾸지 않음을 검증한다.
- 스캔 통합 테스트(`ReconciliationScanQueryIntegrationTest`, Testcontainers): APPROVE·CANCEL 각각
  (a) `nextReconcileAt`이 미래면 스캔에서 제외, (b) NULL이면 포함, (c) 과거면 포함됨을 검증한다.
  기존 stale 윈도우 테스트는 회귀 없이 통과해야 한다(게이트는 `now`를 넘겨 호출).

## 하지 마라

- `respondedAt`을 재사용하거나 backoff 용도로 갱신하지 마라. 이유: escalation·stale 윈도우 계산이
  `respondedAt`에 의존한다(오염 시 escalation 오판).
- 스캔 정렬(`id ASC`)을 바꾸지 마라. 이유: 이번 작업은 게이트로 starvation을 해소한다. 정렬 교체는
  범위 밖이다.
- `find*EscalationCandidates`에 게이트를 넣지 마라. 이유: escalation은 6시간 초과 종착 경로라
  재조회 cadence 대상이 아니다.
- step2의 backoff 기록(service·usecase 호출)을 여기서 하지 마라. 이유: 이 step은 읽기 측 토대만
  검증한다.

## 관련 파일

- `src/main/resources/db/migration/V8__add_payment_escalated_at.sql` (마이그레이션 스타일 참고)
- `src/main/java/com/commerce/payment/domain/Payment.java` (필드·메서드 추가)
- `src/main/java/com/commerce/payment/infrastructure/persistence/JpaPaymentRepository.java` (게이트)
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/persistence/PaymentRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCase.java` (now 전달)
- `src/test/java/com/commerce/payment/infrastructure/ReconciliationScanQueryIntegrationTest.java` (게이트 테스트 + 5-인자 호출 갱신)
- `src/test/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCaseTest.java` (스캔 mock stub 5-인자 갱신)
- `src/test/java/com/commerce/payment/application/usecase/CancelReconciliationUseCaseTest.java` (스캔 mock stub 5-인자 갱신)

## Acceptance Criteria

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests "*ReconciliationScanQueryIntegrationTest"
./gradlew test --tests "*ReconcilePaymentUseCaseTest" --tests "*CancelReconciliationUseCaseTest"
```
