# 회고록: payment-compensation-to-domain

## 1. 작업 요약

### 무엇을 변경했는가

보상 정책 코드(dispatcher 4개 + 공통 골격)를 `NaverPayApprovalService`(PG 어댑터)에서 `PaymentApprovalCompensationService`(payment.application)로 이동했다. `PgCanceller` functional interface를 경계로 삼아 `payment.application` 코드가 `NaverPayCancelResult`를 직접 import하지 않도록 의존 방향을 정리했다.

**신설된 파일 (코드):**
- `payment/application/port/PgCanceller.java` (10줄) — `cancel(PaymentAttempt, String): CancelOutcome` 시그니처의 @FunctionalInterface
- `payment/application/port/result/CancelOutcome.java` (25줄) — Status enum(SUCCESS/PROCESSING/FAILED) + 정적 팩토리 3개(success/processing/failed) record
- `payment/application/PaymentApprovalCompensationService.java` (118줄) — dispatcher 4개(`compensateMerchantKeyMismatch`/`AmountMismatch`/`DuplicatePayment`/`Unexpected`) + private `runPgCancel`

**수정된 파일 (코드):**
- `payment/naverpay/application/NaverPayApprovalService.java` — 보상 메서드 8개 삭제(`compensate*` 4개, `failApproveAndCancelApprovedPayment`, `processCancelRequest`, `succeedCancel`, `markCancelFailed`), `pgCancel` package-private 메서드 신설, catch 블록을 `paymentApprovalCompensationService.compensateXxx(..., this::pgCancel)` 1줄로 단순화. ~330줄 → 236줄.

**신설된 파일 (테스트):**
- `test/.../PaymentApprovalCompensationServiceTest.java` (264줄, 12개 케이스) — dispatcher 4개 × 보상 필요 여부 × PG cancel outcome 분기 매트릭스 커버

**수정된 파일 (테스트):**
- `test/.../NaverPayApprovalServiceTest.java` — 보상 관련 케이스를 `paymentApprovalCompensationService.compensateXxx` 호출 검증으로 갱신, `pgCancel` 변환 케이스(NaverPayCancelResult.Status별 CancelOutcome 매핑) 독립 추가

**갱신된 루트 docs:**
- `docs/ADR.md` — ADR-015 신설(보상 정책 payment.application 이동), ADR-014에 후속 노트 추가
- `docs/architecture.md` — 서비스 테이블과 결제 승인 데이터 흐름에 PaymentApprovalCompensationService 추가
- `docs/exception-strategy.md` — failIfRequested 호출처 명칭 갱신, PgCanceller 섹션 추가
- `docs/testing-conventions.md` — Application Layer 섹션에 PgCanceller Mock 패턴 추가

---

## 2. 설계 결정 요약

### ADR-T1: PgCanceller 좁은 콜백 방식 채택 (PaymentGateway 완전 inversion 대신)

세 가지 선택지가 있었다:

1. **PgCanceller 좁은 콜백** (채택): `@FunctionalInterface PgCanceller` + `CancelOutcome` record 도입. `payment.application`이 `NaverPayCancelResult`를 직접 import하지 않는 최소 구조.
2. **PaymentGateway port 완전 inversion**: PG-agnostic approve/cancel 통합 port. PG가 둘 이상이 될 때 자연스러운 방향이나 현 시점에서 over-engineering.
3. **Strategy 패턴**: PG별 보상 전략 객체. PG가 하나뿐인 현 시점에 premature.

`PgCanceller`는 NaverPay가 메서드 참조(`this::pgCancel`)로 구현하므로 좁은 경계를 만들면서 PaymentGateway port 추가 시 자연 승격이 가능하다. `CancelOutcome` record로 PG 특화 응답 타입이 application 레이어로 새어나오는 도메인 오염을 차단한다.

trade-off: PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치(현재 NaverPayApprovalService 생성자 주입 → 공통 팩토리)를 재설계해야 한다.

### ADR-T2: PaymentApprovalCompensationService에 클래스 레벨 @Transactional 금지

`runPgCancel` 내부에서 `isCompensationRequired`가 `@Transactional(REQUIRES_NEW)`로 격리된다. 클래스 레벨 `@Transactional`이 붙으면 이 메서드가 외부 트랜잭션을 이어받아 격리가 깨진다. ADR-014(payment-compensation-policy task)에서 확립한 race-safe 보상 판단 정책이 무너지는 것을 막기 위해 명시적으로 금지했다. 각 단계(failIfRequested, isCompensationRequired, getOrCreate, succeed/fail)가 자기 `@Transactional` 어노테이션으로 독립적인 트랜잭션 경계를 유지한다.

### PgCanceller 예외 swallow 정책

`pgCanceller.cancel` 중 `PaymentException` 발생 시 `runPgCancel` 내에서 log.warn 후 swallow한다. 원래 승인 실패 예외가 보상 실패 예외에 가려지지 않도록 하기 위함이다. 이 정책은 이전 task(payment-compensation-policy)에서 `failApproveAndCancelApprovedPayment` 내에 있던 `PaymentException` catch와 동일한 의도를 이어받는다.

---

## 3. 발견한 것

### NaverPayApprovalService 라인 수 감소 예상치와 실제 차이

PRD에서 "~330줄 → ~150줄 이하"를 예상했으나 실제 결과는 236줄이었다. 보상 메서드 8개를 이동했음에도 `pgCancel`(NaverPayCancelResult.Status → CancelOutcome 변환 포함), `completeVerifiedApproval` catch 블록 구조, 응답 변환 유틸 메서드들이 남아 예상보다 많은 라인이 유지됐다. 150줄 예상은 유틸 메서드 규모를 과소평가한 결과다. 다만 보상 관련 책임의 이동이 핵심 목표였으므로 라인 수 자체는 부차적 지표다.

### compensateMerchantKeyMismatch에서 PG cancel 부재 (이전 task와 동일 발견)

`MERCHANT_KEY_MISMATCH`는 우리 시스템이 발급한 `merchantPayKey`를 PG가 모르는 상황이므로 PG 측에 cancel 요청 대상 자체가 없다. 이 사실은 `NaverPayApprovalService`에서 보상 메서드를 분리하는 이전 task(payment-compensation-policy)에서 이미 발견됐으나, 이번 task에서 `PaymentApprovalCompensationService`의 구조로 명문화하는 과정에서 다시 명확히 드러났다. `compensateMerchantKeyMismatch`가 `PgCanceller`를 파라미터로 받지 않는 설계가 이 의도를 메서드 시그니처로 표현한다.

### cancelAttempt.getStatus() != REQUESTED 조건의 역할

`runPgCancel` 4단계에서 `cancelAttempt.getStatus() != REQUESTED`이면 return한다. `getOrCreate`가 NOT_SUPPORTED 트랜잭션으로 실행되어 이미 진행 중이거나 완료된 cancel attempt를 그대로 반환할 수 있다. 이 조건이 없으면 이미 SUCCEEDED/FAILED 상태인 attempt에 대해 PG cancel을 재시도하는 경로가 열린다. `isCompensationRequired`의 REQUIRES_NEW 격리와 이 상태 가드가 두 겹의 방어선을 형성한다.

### PaymentApprovalCompensationServiceTest의 PgCanceller Mock 패턴

`PgCanceller`가 @FunctionalInterface이므로 Mockito `@Mock`으로 직접 주입한다. `PaymentApprovalCompensationService`가 `PgCanceller`를 메서드 파라미터로 받는 구조(필드 의존이 아닌 메서드 파라미터)라 테스트에서 PgCanceller를 @Mock으로 선언하고 인자로 넘기는 방식이 자연스럽게 맞아떨어졌다. 이 패턴을 `docs/testing-conventions.md`에 추가했다.

---

## 4. 미결 과제

### PaymentGateway port 완전 inversion (PG 둘 이상 추가 시 자연 승격 방향)

현재 `PgCanceller`는 `NaverPayApprovalService`가 메서드 참조(`this::pgCancel`)로 구현하므로, PG가 NaverPay 하나인 현 시점에 최소 경계를 만든다. PG가 둘 이상 추가될 때는 approve와 cancel을 포괄하는 PG-agnostic `PaymentGateway` port가 자연스럽게 필요해진다. 그 시점에 `PgCanceller`는 `PaymentGateway`의 일부 메서드로 승격되고, `NaverPayApprovalService`의 역할은 gateway 구현체로 재편될 것이다.

### PaymentReference Value Object 도입 (두 Aggregate 협력 키 명시화)

`merchantPayKey`는 `Payment`와 `PaymentAttempt` 두 Aggregate 간 협력 키로 String 원시 타입으로 흐른다. `PaymentReference`와 같은 Value Object로 명시화하면 협력 경계가 타입으로 드러나고 `isCompensationRequired(merchantPayKey)` 시그니처의 의도가 명확해진다. Payment 도메인 분리가 논의될 때 함께 검토할 가치가 있다(payment-compensation-policy 회고에서도 동일하게 제안됨).

### ArchUnit으로 PaymentAttempt 도메인 메서드 가시성 강제 (ADR-014 정책 코드 강제)

`PaymentAttempt.succeed`/`fail` 메서드는 `PaymentApprovalAttemptService`와 `PaymentCancellationAttemptService`를 통해서만 호출해야 한다는 정책이 ADR-014와 JavaDoc으로 명시되어 있으나, 컴파일러나 CI가 직접 강제하지는 않는다. ArchUnit 도입으로 "PaymentAttempt의 도메인 메서드는 PaymentXxxAttemptService를 통해서만 호출 가능" 규칙을 CI에서 검증할 수 있다. payment-compensation-policy 회고와 payment-attempt-service-split 회고 모두 같은 제안을 하므로, 다른 도메인 아키텍처 테스트와 함께 일관된 방식으로 도입하는 것을 권장한다.

---

## 5. 개선 제안

### runPgCancel 단계별 로그 수준 정비

현재 `isCompensationRequired == false`(보상 불필요)와 `cancelAttempt.getStatus() != REQUESTED`(이미 진행된 cancel) 모두 log.warn으로 처리된다. 두 상황의 의미가 다르다. 전자는 정상 race 결과(Payment가 이미 존재)이고, 후자는 cancel attempt가 이미 처리됐음을 의미한다. 전자는 운영 이상 감지를 위한 지표 수집 대상(cancel skip 빈도 모니터링)이고, 후자는 단순 debug 수준으로 충분하다. 로그 수준과 메시지를 분리하면 알람 설정이 명확해진다.

### NaverPayCancelResult.Status → CancelOutcome.Status 매핑 테스트를 NaverPayApprovalServiceTest에 집중

`NaverPayApprovalServiceTest`의 `pgCancel` 변환 케이스에서 `NaverPayCancelResult.Status`별 `CancelOutcome` 매핑을 검증한다. 이 매핑 로직은 NaverPay 어댑터의 책임이므로 해당 테스트에 집중하는 것이 올바른 방향이다. `PaymentApprovalCompensationServiceTest`에서는 `PgCanceller`를 Mock으로 주입하여 변환 세부 시나리오를 재검증하지 않는다. 이 역할 분리를 명시적으로 유지하면 PG 종류가 늘어날 때 각 어댑터 테스트만 변환 케이스를 담당하는 구조가 자연스럽게 유지된다.

### pgCancel swallow 범위 명문화

`runPgCancel`에서 `pgCanceller.cancel` 호출 중 발생하는 `PaymentException`을 swallow한다. 현재 코드 및 docs에 "원래 승인 실패 예외를 가리지 않기 위해 swallow"라는 의도가 기술되어 있으나, swallow 대상 예외 범위(PaymentException만인지, RuntimeException 전체인지)를 `docs/exception-strategy.md`에 명시하면 향후 예외 처리 정책 변경 시 기준이 명확해진다.

### step 설계 시 라인 수 예측 근거 명시

PRD와 architecture.md에 "~330줄 → ~150줄 이하 감소 예상"이 명시됐지만, 실제 결과는 236줄이었다. 삭제 대상 라인 수만 집계하고 남는 유틸 메서드·변환 코드 규모를 함께 추산하지 않은 결과다. 향후 step 설계에서 라인 수 예측을 제시할 때는 "삭제 라인 N줄, 잔여 라인 M줄, 신설 라인 K줄"로 세분화하거나, 예측 자체를 생략하고 책임 이동 여부를 검증 기준으로 삼는 것이 더 신뢰할 수 있는 설계다.
