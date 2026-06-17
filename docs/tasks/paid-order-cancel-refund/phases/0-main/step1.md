# step1 — payment-refund-execution

## 목표

사용자 주도 PAID 취소 환불의 **payment 쪽 실행 토대**를 만든다. (1) 환불 대상 결제를 orderId로
조회하는 경로와, (2) 영속된 CANCEL 결제(REQUESTED)에 대해 PG 취소를 실행하고 결과를 반영하는
**별도 실행 경로**를 추가한다. order 동작을 바꾸지 않으며 단독으로 검증·머지 가능하다.

## 배경·맥락

- 결제 테이블은 append-only 원장이다. 환불은 APPROVE 레코드를 수정하지 않고 별도 CANCEL
  레코드로 표현한다(ADR-L2). 정상 승인된 approve 결제는 FAILED로 만들지 않는다.
- 재사용 대상(기존): `GetOrCreateCancelPaymentService`, `SucceedCancelPaymentService`,
  `FailCancelPaymentService`, `MarkUnknownCancelPaymentService`, `PgCanceller` 콜백,
  `CancelOutcome`.

## 구현 지시

### 1) 환불 대상 조회 추가

- `PaymentRepository`(payment.domain.repository)에 orderId로 **SUCCEEDED 상태의 APPROVE
  결제**를 가져오는 조회를 추가한다. 환불에는 결제 객체(merchantPayKey·provider·pgPaymentId·amount)가
  필요하다(기존 `existsByOrderIdAndTypeAndStatus`는 boolean뿐).
- 주문당 SUCCEEDED APPROVE는 유일하다(`uk_payment_approved_order_key`, NULL 트릭 partial unique).
  따라서 `Optional<Payment>`로 반환한다. 0개일 수 있으므로 호출처가 빈 경우를 처리한다.
- 구현 adapter(`JpaPaymentRepository` / `PaymentRepositoryAdapter`)에 쿼리를 추가한다.

### 2) Payment 엔티티 unique parity (테스트 정합)

- `Payment` 엔티티 `@Table(uniqueConstraints=...)`에 기존 Flyway unique
  `uk_payment_merchant_pay_key_provider_pg_payment_id_type`(`merchant_pay_key, provider,
  pg_payment_id, type`)를 미러링한다. 현재 엔티티엔 `uk_payment_approved_order_key`만 선언돼 있다.
- **스키마 변경이 아니다**: Flyway V6에 이미 있고, prod/local은 `validate`라 영향 없다. test
  프로파일(H2 `create-drop`)이 엔티티에서 스키마를 생성하므로, 이 선언이 있어야 H2 테스트에도
  제약이 생겨 CANCEL 멱등(한 결제당 CANCEL 하나)을 슬라이스/단위 테스트로 검증할 수 있다(ADR-L5).

### 3) 환불 실행 경로 추가 (payment.application, 별도 클래스)

- 입력으로 주어진 **CANCEL 결제(REQUESTED)**에 대해 PG 취소를 호출하고 결과를 반영하는 실행
  컴포넌트를 **새 클래스로** 추가한다(예: `RefundExecutionService`/`…UseCase` 류 — 이름은 구현
  재량, 단 AC 테스트가 겨냥할 수 있게 명명). tx 밖(커밋 이후)에서 호출되는 best-effort 경로다.
- 동작:
  - 입력 CANCEL 결제가 REQUESTED가 아니면 아무 것도 하지 않는다(멱등 — 이미 종착/처리됨).
  - `PgCanceller.cancel(...)` 결과(`CancelOutcome`)에 따라:
    - SUCCESS → `SucceedCancelPaymentService.succeed(...)`
    - PROCESSING → no-op
    - FAILED → `FailCancelPaymentService.fail(...)`
    - UNKNOWN → `MarkUnknownCancelPaymentService.markUnknown(...)` (대사가 재시도, step2)
  - 상태 전이 service가 던지는 충돌·가드 도메인 예외(이미 다른 주체가 종착)는 best-effort에서
    흡수(skip)한다. 흡수는 tx 경계 밖에서만(`CompensateApprovalUseCase`의 SKIPPABLE 패턴 참조).
- approve 결제는 절대 건드리지 않는다.

> 이 경로는 step2(CANCEL 대사)의 재실행과 로직을 공유할 수 있다(주어진 CANCEL → PG → 상태 반영).
> 공유 가능한 형태로 두되, step2가 PG 상태를 먼저 조회해 재시도/확정을 가르는 부분은 step2 책임이다.

## 하지 마라

- approve 결제를 FAILED로 마킹하지 마라. 이유: 정상 승인된 결제의 환불이며 승인 사실은 불변
  원장으로 보존한다(ADR-L2).
- 기존 `runPgCancel`을 쪼개 그 일부를 재사용하지 마라. 이유: `runPgCancel`은 approve fail이 섞여
  있고, 공유 코드를 변경하면 기존 보상 경로에 회귀 위험이 생긴다(M4). 별도 실행 경로로 둔다.
- PG 호출(외부 I/O)을 `@Transactional` 안에 넣지 마라. 이유: 단계별 독립 commit 원칙(ADR-015)과
  충돌하고 외부 호출이 tx를 길게 잡는다.
- CANCEL 결제를 새로 생성(getOrCreate)하지 마라. 이유: 생성은 step3의 취소 tx에서 환불 의도로
  원자 영속화된다(ADR-L1). 여기서 또 만들면 책임이 겹친다.

## 관련 파일

- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/persistence/` (JpaPaymentRepository / adapter)
- `src/main/java/com/commerce/payment/application/usecase/CompensateApprovalUseCase.java` (SKIPPABLE 흡수 패턴 참조)
- `src/main/java/com/commerce/payment/application/service/` (Succeed/Fail/MarkUnknown CancelPaymentService)
- `src/main/java/com/commerce/payment/application/port/PgCanceller.java`, `.../result/CancelOutcome.java`

## Acceptance Criteria

```bash
./gradlew test --tests "*Refund*"
./gradlew test --tests "*PaymentRepository*"
```
