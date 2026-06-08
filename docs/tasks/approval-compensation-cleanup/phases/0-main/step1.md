# Step 1: duplicate-detection-adapter-mapping

## 읽어야 할 파일

먼저 아래를 읽고 설계 의도를 파악하라:

- `/docs/tasks/approval-compensation-cleanup/prd.md`
- `/docs/tasks/approval-compensation-cleanup/adr.md` (특히 ADR-L2)
- `/docs/tasks/approval-compensation-cleanup/architecture.md`
- 변경 대상 코드:
  - `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` (`completeVerifiedApproval`, line 153~210)
  - `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (`compensateDuplicateApproval`, `compensateDuplicatePayment`)
  - `src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval`)
  - `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
  - `src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- 도메인·제약 사실:
  - `src/main/java/com/commerce/payment/domain/Payment.java` (`succeed`: REQUESTED→SUCCEEDED + `approvedOrderKey=orderId` set, `@UniqueConstraint(name = "uk_payment_approved_order_key", ...)`)
  - `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java` (`PAYMENT_DUPLICATE`)
  - `src/main/java/com/commerce/common/jpa/JpaConfig.java` (unique 위반이 `DataIntegrityViolationException`으로 올라온다는 주석)
- 기존 테스트:
  - `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java` (line 661 근처 "uk_payment_approved_order_key 위반..." 테스트)
  - `src/test/java/com/commerce/payment/application/PaymentApprovalCompensationServiceTest.java`
  - `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryDuplicatePaymentTest.java` (`@Tag("docker")`, MySQL Testcontainers)
  - `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryApprovedConcurrencyTest.java`
  - `src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalServiceConcurrencyTest.java`
  - `src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java`

공통 맥락이 부족하면 `/docs/adr.md`의 ADR-011(DB unique 위반 처리 + try-save-catch carve-out), ADR-026/027을 추가로 읽는다.

## 작업

이중결제 탐지를 application의 raw `DataIntegrityViolationException` catch(ADR-011 위반)에서 **adapter 도메인 예외 매핑**으로 전환하고, 갈라진 이중결제 보상을 fail-first 단일 경로로 통일한다.

### 1) `PaymentRepository`에 succeed-approve 전용 저장 메서드 추가

- `Payment saveApproved(Payment payment)`를 인터페이스에 추가한다.
- 의미: APPROVE 승인 완료(`succeed`로 `approvedOrderKey`가 채워지는) 저장 경로 전용. 이 경로에서만 `uk_payment_approved_order_key` 위반을 도메인 예외로 번역한다.

### 2) `PaymentRepositoryAdapter.saveApproved` 구현 — constraint name 한정 매핑

- 내부적으로 `jpaPaymentRepository.saveAndFlush(payment)`를 호출한다. **`saveAndFlush`(즉시 flush)를 유지**한다 — flush가 트랜잭션 경계 전에 위반을 이 메서드 호출 안에서 확정하는 load-bearing 의존성이다.
- `saveAndFlush`가 던지는 `DataIntegrityViolationException`을 catch한다.
- cause 체인에서 Hibernate `org.hibernate.exception.ConstraintViolationException`을 찾아 그 constraint name이 `uk_payment_approved_order_key`인 경우에만 `new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE)`로 바꿔 던진다.
- constraint name을 확정할 수 없거나(추출 불가/null) 다른 constraint이면 **원 `DataIntegrityViolationException`을 그대로 다시 던진다**(매핑하지 않는다). 이로써 FK/NOT NULL/타 unique 위반은 안전망 500으로 위임된다(ADR-011).
- 범용 `save()`는 기존대로 두고 매핑하지 않는다.
- 참고: Hibernate 버전에 따라 constraint name 추출 API가 다를 수 있다. constraint name을 신뢰성 있게 얻는 방법으로 구현하되, 얻지 못하면 매핑하지 않고 전파하는 보수적 원칙을 지킨다.

### 3) `PaymentApprovalService.succeedApproval`이 전용 경로를 타도록 교체

- payment 저장 호출 `paymentRepository.save(current)`를 `paymentRepository.saveApproved(current)`로 교체한다. (order 저장 `orderRepository.save(order)`는 그대로 둔다)
- 멱등 흡수(`current.getStatus() == SUCCEEDED`) 등 나머지 로직은 보존한다.

### 4) `NaverPayApprovalService.completeVerifiedApproval`에서 raw catch 제거

- `catch (DataIntegrityViolationException ex)` 블록(line 163~168 근처)을 제거한다. 그 안의 `compensateDuplicateApproval` 호출도 함께 사라진다.
- `import org.springframework.dao.DataIntegrityViolationException;`를 제거한다.
- 이제 이중결제는 `succeedApproval` 안에서 `PaymentException(PAYMENT_DUPLICATE)`로 올라오므로 기존 `catch (PaymentException ex)`의 `case PAYMENT_DUPLICATE`(현재 dead → live)가 `compensateDuplicatePayment(payment, ex, this::pgCancel)`로 처리한다. 이 switch 분기는 그대로 둔다.

### 5) `PaymentApprovalCompensationService` 보상 단일화

- `compensateDuplicateApproval`(cancel-first) 메서드를 제거한다.
- `compensateDuplicatePayment`(fail-first, `runPgCancel` 기반)는 live 경로로 유지한다.

### 6) 테스트 갱신

- `NaverPayApprovalServiceTest`: 이중결제 테스트를 "`succeedApproval`이 `PaymentException(PAYMENT_DUPLICATE)`를 던지면 `compensateDuplicatePayment`를 호출하고 `PAYMENT_DUPLICATE`를 전파한다"로 갱신한다. (mock이 `DataIntegrityViolationException`을 던지던 것을 `PaymentException(PAYMENT_DUPLICATE)`로 바꾸고, 검증 대상을 `compensateDuplicatePayment`로 변경)
- `PaymentApprovalCompensationServiceTest`: `compensateDuplicateApproval` 테스트를 제거하고, `compensateDuplicatePayment`의 fail-first 동작(approve FAILED(DUPLICATE_PAYMENT) 마킹 후 PG cancel) 검증을 유지·보강한다.
- adapter 매핑 통합 테스트(MySQL): `saveApproved`가 `uk_payment_approved_order_key` 위반 시 `PaymentException(PAYMENT_DUPLICATE)`로 매핑하고, 다른 무결성 위반(예: `uk_payment_merchant_pay_key_provider_pg_payment_id_type`)은 `DataIntegrityViolationException`으로 그대로 전파함을 검증한다. `PaymentRepositoryDuplicatePaymentTest`와 같은 `@Tag("docker")` 슬라이스 방식을 따른다.
- 동시성 테스트(`PaymentApprovalServiceConcurrencyTest`, `NaverPayServiceConcurrencyTest`, `PaymentRepositoryApprovedConcurrencyTest`): `DataIntegrityViolationException`을 직접 기대하던 부분을, 전용 경로에서는 `PaymentException(PAYMENT_DUPLICATE)`로 올라오도록 갱신한다. "동시 두 승인 시 하나만 SUCCEEDED, 나머지는 `PAYMENT_DUPLICATE` 보상"이 성립함을 유지한다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

repository 포트 메서드 추가·공통 예외 흐름·보상 경로 변경이 포함되므로 단위/슬라이스(`test`)에 더해, adapter의 `uk_payment_approved_order_key` 매핑 검증(MySQL Testcontainers = `integrationTest`)과 동시 두 승인 보상 검증(`concurrencyTest`)까지 재실행한다. `integrationTest`/`concurrencyTest`는 Docker daemon이 필요하다(`verifyDockerDaemon`).

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 모두 실행한다. `integrationTest`/`concurrencyTest`는 Docker daemon이 떠 있어야 한다 — Docker 미가용으로 태스크가 실패하면 자동 우회하지 말고 step을 `blocked`로 두고 사용자에게 보고한다.
2. 아래를 확인한다.
   - application(`NaverPayApprovalService`)에서 `DataIntegrityViolationException` import·catch가 사라졌는가? (`rg "DataIntegrityViolationException" src/main/java/com/commerce/payment/naverpay`로 없음 확인)
   - 매핑이 `saveApproved` + `uk_payment_approved_order_key`로 한정됐는가? 범용 `save()`는 매핑하지 않는가?
   - `compensateDuplicateApproval`이 제거되고 `compensateDuplicatePayment` 단일 경로인가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `saveApproved`에서 `saveAndFlush`를 일반 `save`로 바꾸거나 flush를 미루지 마라. 이유: 조기 flush가 위반을 adapter 호출 안에서 확정하는 load-bearing 의존성이다(payment-naming-cleanup 회고, ADR-L2).
- constraint name 확인 없이 `DataIntegrityViolationException`을 무조건 `PAYMENT_DUPLICATE`로 매핑하지 마라. 이유: FK/NOT NULL/타 unique 위반을 이중결제로 오매핑한다(검증 기준).
- application 계층에서 `DataIntegrityViolationException`을 다시 catch하지 마라. 이유: ADR-011 위반. 인프라 예외 번역은 adapter 책임이다.
- 범용 `save()`에 매핑 로직을 넣지 마라. 이유: succeed-approve 외 경로의 무결성 위반까지 오매핑한다.
- 기존 테스트를 깨뜨리지 마라.
