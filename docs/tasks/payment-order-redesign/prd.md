# 태스크 PRD

## 태스크명

- `payment-order-redesign`

## 배경

- 현재 결제 식별자 `merchantPayKey` 가 Order 엔티티에 박혀있다 (`Order.assignMerchantPayKey`, `uk_order_merchant_pay_key`). Order 가 결제 수단 식별자를 모르는 것이 자연스러운데 책임 누수가 누적돼 있다.
- `Order.assignMerchantPayKey` 는 null 일 때만 set 하는 멱등 setter 라 *한 주문 = 한 결제 키* 로 고정된다. 같은 주문에서 다른 PG 로 재시도 / amount 변경 시 새 결제 시도를 표현할 모델이 없다.
- ADR-010 본문이 "amount 변경 시 새 merchantPayKey 발급" 이라고 명시하지만 실제 코드는 새 키 발급 경로가 없어 ADR 과 코드가 불일치한다.
- 현재 `Payment` (성공한 결제 1:1) 는 *성공한 APPROVE 시도 행이 들고 있는 사실의 복사본* 이다. 별도 테이블로 유지할 정보 이득이 없다.
- 결제 *시도 단위* 와 *현재 상태* 를 분리해 표현할 모델이 부재해 부분취소·이중결제·UNKNOWN 같은 운영 시나리오를 깔끔하게 다루지 못한다.
- 외부 API 의 *ready* 는 의미가 모호하다. 실제로는 *결제창 발급을 위한 예약* 인데 이름이 그 의미를 가리지 않는다.

## 목표

- 결제 도메인을 두 테이블로 분리한다:
  - **`PaymentReservation`** — 결제창 준비물 (임시·만료·따닥 대량 발생). `RESERVED → USED` 한 번 전이.
  - **`Payment`** — PG 에 실제로 보낸 요청 사건 (APPROVE / CANCEL). append-only.
- merchantPayKey 발급/저장 책임을 결제 도메인 (`PaymentReservation`) 으로 옮기고 Order 에서 제거한다.
- 이중결제 / reserve 따닥 / UNKNOWN 의 *최종 방어선* 을 DB 제약으로 박는다 (1차 필터 + 최종 방어선 이중 구조).
- 다중 PG 재시도 / amount 변경 / 부분취소 모델을 *지금 구현하지 않더라도 모델은 열어둔다.*
- 외부 API 의 *ready* 라는 부정확한 이름을 *reserve* 로 통일한다 (frontend 미개발이라 호환 깨도 무방).

## 범위

### 포함 범위

- 결제 도메인 분리
  - **신규 `PaymentReservation`** 도메인 + Repository + JpaPaymentReservationRepository + Adapter
  - 현재 `PaymentAttempt` → `Payment` rename + 의미 정리 (type ∈ {APPROVE, CANCEL}, RESERVE 빠짐)
  - 현재 `Payment` (성공 결제 1:1) 폐기
- Order 정리 — `merchant_pay_key` 컬럼 / `uk_order_merchant_pay_key` / `assignMerchantPayKey()` / `findByMerchantPayKey*` 제거
- Flyway V6 마이그레이션 (테이블 분리 기준)
- **`ReservePaymentService`** 신설 (구 `PaymentReadyService` rename + B안 흐름)
- `NaverPayApprovalService` 재배선 — Reservation 기반 역조회 + `markUsed` + Payment(APPROVE) 신규 행 + `uk_payment_approved_order_key` 위반 보상 path
- UNKNOWN 상태 *마킹* — PG 호출 timeout / DB 반영 실패 시 흔적 보존 + UNKNOWN 행 있는 주문 차단
- 같은 `merchantPayKey` 의 redirect 중복 → 멱등 응답 흡수 (USED Reservation 발견 시 기존 결제 결과 200 반환)
- 외부 API rename: `POST /payments/ready` → `POST /payments/reserve`
- 영향 받는 application/infrastructure 갱신 (`PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `PaymentApprovalCompensationService`, `OrderQueryService` 제거)
- 루트 docs 동기화 (ADR / db-schema / architecture / api-spec / exception-strategy)

### 제외 범위

- UNKNOWN 해소 대사 service (`PaymentReconciliationService`) — 후속 task
- 결제 취소 (`CANCEL` 흐름의 실제 구현) — 후속
- 부분취소 로직 — 모델만 열어둠 (Payment.type=CANCEL 에 amount)
- 클라이언트 idempotencyKey — order 단위 unique 로 충분
- PG 응답 원문 보관 테이블 (`PgTransactionLog` 등) — 후속
- `PaymentSummary` 집계 테이블 — 후속
- 마이그레이션 backfill — 운영 데이터 없음 가정
- 만료된 Reservation 물리 정리 (배치) — 우선순위 낮음, 후속
- workspace `docs/api-contract.md` 갱신 — frontend 세션 책임

## 주요 시나리오

### 1. 결제 reserve (구 ready)

1. 사용자가 결제 시작 → `POST /payments/reserve`
2. Order 조회 + `checkPayable()`
3. UNKNOWN 차단 검사 — 해당 orderId 에 UNKNOWN Payment 행 있으면 `PAYMENT_RESULT_PENDING` 응답
4. `paymentReservationRepository.findReusable(orderId, memberId, provider, amount, now())` — `(status=RESERVED ∧ expiresAt>now ∧ provider 일치 ∧ memberId 일치 ∧ amount 일치)`
5. 있으면 그 Reservation 의 `merchantPayKey` 재사용. 없으면 새 RESERVED 행 INSERT (`status=RESERVED`, `expiresAt=now+30m`, `reservedKey="{orderId}:{provider}"`)
6. 결제창 호출 정보 반환 (`merchantPayKey` 포함, `returnUrl` 에 키 박힘)
7. 동시 따닥은 Reservation 의 `uk_payment_reservation_reserved_key` 가 두 번째 INSERT 차단

### 2. 결제 승인 (approve)

1. PG redirect → `POST /payments/naverpay/approve` (`merchantPayKey`, `pgPaymentId`)
2. `paymentReservationRepository.findByMerchantPayKey(merchantPayKey)` → Reservation 확보 (Order 안 거침)
3. memberId 검증 — Reservation 의 `memberId` 와 SecurityContext `memberId` 일치 확인
4. UNKNOWN 차단 검사 — 해당 orderId 에 UNKNOWN Payment 행 있으면 `PAYMENT_RESULT_PENDING` 응답
5. **이미 USED Reservation** 발견 시: `paymentRepository.findApproveSucceeded(merchantPayKey).orElseThrow(...)` 로 기존 결제 결과 조회 후 *멱등 응답 (200)*
6. Reservation 이 RESERVED 면 같은 트랜잭션 안에서: `reservation.markUsed()` (status=USED + reservedKey=NULL 동시) + `Payment(type=APPROVE, status=REQUESTED, pgPaymentId)` 신규 행 INSERT
7. [트랜잭션 밖] NaverPay approve API 호출
8. [트랜잭션 안] 결과 분기
   - SUCCESS: `Payment.succeed(approvedAt)` — `status=SUCCEEDED` + `approved_order_key=orderId` *같은 UPDATE 안에서 set* + Order PAID 전이. `uk_payment_approved_order_key` 가 주문당 1개 보장
   - FAIL: `Payment.fail(failCode, failDetail, respondedAt)`
   - TIMEOUT/UNKNOWN: `Payment.markUnknown(failDetail, respondedAt)`
9. `uk_payment_approved_order_key` 위반 (이미 결제된 주문) → 보상 CANCEL path

### 3. UNKNOWN 차단

1. UNKNOWN 행 있는 주문에 reserve/approve 요청 → 차단 + "확인 중" 응답
2. 해소는 후속 task (대사 service)

### 4. 만료 후 늦은 redirect

1. Reservation `expires_at` 지난 후 redirect 가 도착해도 승인 진행 가능
2. `expires_at` 은 *reserve 재사용 판단* 에만 쓰임. 승인 차단 사유 아님
3. 근거: 돈은 PG 가 빼간 것. 우리 30m 정책으로 막으면 *돈은 빠졌는데 주문은 미결제* 박제 발생

## 요구사항

- merchantPayKey 는 서버가 reserve 단계에서 발급한다. 클라이언트 발급 금지
- Order 는 `id`, `orderNumber`, `memberId`, items, totalPrice, orderStatus 만 가진다. 결제 식별자 모름
- "결제 완료" 판단 = `(성공 APPROVE 존재) AND (그것을 무효화한 성공 CANCEL 부재)`. 마지막 행 기반 판단 금지
- 외부 PG 호출은 트랜잭션 밖, payment+order DB 쓰기는 한 트랜잭션 안
- 존재 보장 (없으면 INSERT) 은 unique 제약으로. gap lock 회피
- 계산 기반 판단 (잔액 SUM 등) 은 order PK 단일 행 FOR UPDATE 로
- 동일 (orderId, provider) RESERVED 동시 INSERT 는 Reservation 의 `uk_payment_reservation_reserved_key` 가 차단
- 동일 orderId 성공 APPROVE 동시 INSERT 는 Payment 의 `uk_payment_approved_order_key` 가 차단
- UNKNOWN 상태가 발생하면 사용자 재시도를 차단하고 "확인 중" 안내
- 같은 merchantPayKey 의 redirect 중복은 *차단이 아닌* 멱등 응답으로 흡수 (USED Reservation 발견 시 기존 결제 결과 반환)
- amount 변경 시 새 Reservation 발급 (Reservation 의 amount UPDATE 금지)
- expires_at 은 ready 재사용 판단에만 쓰임. 만료된 Reservation 에 늦은 redirect 가 도착해도 승인 진행 차단 사유 아님
- Reservation 의 status 는 `{RESERVED, USED}` 2개. EXPIRED 별도 마킹 없음 (필터로만 처리)

## 제약사항

- DB 는 MySQL 8 (InnoDB). PostgreSQL 의 partial unique index 미지원 → NULL 트릭 (`approved_order_key`, `reserved_key`) 으로 우회
- 운영 데이터 없음 — backfill 없이 단순 schema 변경
- ADR-014 의 "Payment row 존재 = 결제 완료" 의미 보존, 구현은 새 모델 (`existsByMerchantPayKeyAndTypeAndStatus(merchantPayKey, APPROVE, SUCCEEDED)`) 로 갱신
- 머지된 task 폴더 (`docs/tasks/payment-attempt-*` 등) 문서는 *수정하지 않음*. 변경은 루트 docs 갱신으로만 표현
- 결제 도메인 패키지 격리 (PG-agnostic 코어 + naver 패키지에 PG 전용 코드) 는 ADR-015 기조 유지
- frontend 가 미개발이라 외부 endpoint 의 호환 깨는 rename (ready → reserve) 가능. workspace `docs/api-contract.md` 갱신은 frontend 세션 책임
