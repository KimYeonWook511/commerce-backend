# 태스크 아키텍처

## 개요

- 결제 도메인을 **두 테이블로 분리**한다.
  - `PaymentReservation` — 결제창 준비물. 상태 변화 + 임시 + 만료 + 따닥 대량 발생.
  - `Payment` — PG 에 실제로 보낸 요청 사건. append-only.
- 현재 `PaymentAttempt` 는 새 `Payment` 의 그릇이 되고 (RESERVE 빠짐), 현재 `Payment` (성공 결제 1:1) 는 폐기.
- merchantPayKey 발급/저장 책임이 Order → PaymentReservation 으로 이동.
- 이중결제 / reserve 따닥 / UNKNOWN 의 *최종 방어선* 을 NULL 트릭 unique 두 개 + 상태 마킹으로 박는다.
- 외부 API 명을 `ready` → `reserve` 로 통일 (frontend 미개발이라 호환 깨도 무방).

## 변경 대상

### 도메인 계층

#### 신규: `PaymentReservation`

- 패키지: `com.commerce.payment.domain`
- 필드:
  - `id`, `orderId`, `memberId`, `provider`, `merchantPayKey`, `amount`, `status` (`RESERVED|USED|EXPIRED`), `expiresAt`, `reservedKey` (`RESERVED` 일 때만 `"{orderId}:{provider}"`, 그 외 NULL)
- 도메인 메서드:
  - `createReserved(orderId, memberId, provider, amount, ttl)` — 정적 팩토리. status=RESERVED, reservedKey set, expiresAt=now+ttl
  - `isReusableFor(memberId, provider, amount, now)` — `(status=RESERVED ∧ expiresAt>now ∧ provider 일치 ∧ memberId 일치 ∧ amount 일치)`
  - `markUsed()` — status=USED + **reservedKey=NULL** *같은 UPDATE 안에서* set (NULL 트릭 캡슐화)
  - `markExpired()` — status=EXPIRED + **reservedKey=NULL** *같은 UPDATE 안에서* set. 만료/무효 예약의 reservedKey 점유를 풀어 재예약 허용
- 부수: `PaymentReservationStatus` enum (`RESERVED, USED, EXPIRED`)
- Repository: `PaymentReservationRepository` (`findReserved(orderId, provider)` — 만료 무관 RESERVED 조회) + `JpaPaymentReservationRepository` + `PaymentReservationRepositoryAdapter`

#### Rename + 확장: `PaymentAttempt` → `Payment`

- 기존 `com.commerce.payment.domain.Payment` (성공 결제 1:1) 와 `PaymentStatus` (`COMPLETED|CANCELED`), `com.commerce.payment.domain.repository.PaymentRepository` 및 어댑터/JPA repo **삭제**
- 현재 `PaymentAttempt` 가 `Payment` 의 이름을 차지. 의미는 *PG 에 보낸 실제 요청 사건* (append-only)
- `PaymentAttemptType` → `PaymentType` — 값 `{APPROVE, CANCEL}` (RESERVE 없음)
- `PaymentAttemptStatus` → `PaymentStatus` — 값 `{REQUESTED, SUCCEEDED, FAILED, UNKNOWN}` (RESERVED/EXPIRED 없음)
- `PaymentAttemptFailCode` → `PaymentFailCode`
- `PaymentAttemptRepository` → `PaymentRepository` (이름 재활용)
- 새 필드 (`Payment`):
  - `orderId BIGINT NOT NULL` — Order PK 값으로 소속 표현. DB FK 제약 없음
  - `approvedOrderKey BIGINT NULL` — 성공 APPROVE 일 때만 `orderId`, 그 외 NULL. `uk_payment_approved_order_key` 용
- 기존 필드 의미 변경:
  - `pgPaymentId VARCHAR(64) NOT NULL` — *NOT NULL 복원*. RESERVE 가 빠졌으므로 항상 존재
- 새 도메인 메서드:
  - `createRequested(reservation, type, pgPaymentId)` — 정적 팩토리. status=REQUESTED, amount/orderId/merchantPayKey/provider 는 Reservation 에서 복사
  - `succeed(respondedAt)` — `status=SUCCEEDED` + (type=APPROVE 면 `approvedOrderKey=orderId`) *같은 UPDATE 안에서* set
  - `fail(failCode, failDetail, respondedAt)`
  - `markUnknown(failDetail, respondedAt)`
- 기존 `verifyApprovedResponse(...)` 유지

#### Order 정리

- `Order.merchantPayKey` 필드 제거
- `Order.assignMerchantPayKey()` 메서드 제거
- `OrderRepository.findByMerchantPayKey*` 3 개 메서드 제거
- `uk_order_merchant_pay_key` 제약 제거
- 관련 테스트/주석 정리

### Application 계층

- **`ReservePaymentService`** (구 `PaymentReadyService` rename) — Reservation 생성/재사용/UNKNOWN 차단 흐름. 메서드 `reserve(orderId, memberId, provider)`
- `NaverPayApprovalService` 재배선:
  - Reservation 역조회 entry — `paymentReservationRepository.findByMerchantPayKey(merchantPayKey)`
  - memberId 검증 — Reservation.memberId vs SecurityContext memberId
  - UNKNOWN 차단 검사
  - 멱등 응답 흡수 — USED Reservation 발견 시 `paymentRepository.findApproveSucceeded(...)` 로 기존 결제 결과 200 반환
  - APPROVE 행 INSERT 흐름 — `reservation.markUsed()` + `Payment.createRequested(reservation, APPROVE, pgPaymentId)` (같은 트랜잭션)
  - `uk_payment_approved_order_key` 위반 보상 path
- `PaymentApprovalService`:
  - 폐기: `completeApprovedPayment()`, `findPaymentByMerchantPayKey()`
  - 유지: `hasCompletedPayment(merchantPayKey)` — 의미 보존, 구현 `paymentRepository.existsByMerchantPayKeyAndTypeAndStatus(merchantPayKey, APPROVE, SUCCEEDED)` 로 갱신
- `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService` — 이름 유지, 내부 entity 갱신
- `PaymentApprovalCompensationService` — `compensateDuplicateApproval(attempt, pgCanceller)` 신설
- `OrderQueryService.getOrderByMerchantPayKey*` 폐기 (역조회 경로가 Reservation 으로 이동). 메서드 없어지면 OrderQueryService 자체가 비는지 확인 후 처리

### Presentation 계층

- Controller URL: `POST /payments/ready` → **`POST /payments/reserve`**
- Controller class: `PaymentReadyController` (있다면) → `ReservePaymentController`
- DTO rename: `PaymentReadyRequest` → `ReservePaymentRequest`, `PaymentReadyResponse` → `ReservePaymentResponse` (응답 본문 구조는 동일)
- `NaverPayController.approve` 엔드포인트 시그니처 동일 (`merchantPayKey`, `pgPaymentId` 그대로 받음). 내부 흐름만 변경
- UNKNOWN 상태에서 차단 시 응답 코드 1 개 신규 추가 (`PAYMENT_RESULT_PENDING`)

### DB

- Flyway V6 `V6__redesign_payment_to_reservation_and_attempt.sql`
  - `tbl_payment` (현 성공 결제 1:1) DROP — rename 충돌 회피
  - `tbl_payment_attempt` → `tbl_payment` RENAME
  - `tbl_payment` 컬럼 정리:
    - ADD COLUMN `order_id BIGINT NOT NULL`
    - ADD COLUMN `approved_order_key BIGINT NULL`
    - `pg_payment_id` NOT NULL 유지 (RESERVE 가 빠지므로 완화 불필요)
  - `tbl_payment` 인덱스:
    - ADD UNIQUE `uk_payment_approved_order_key (approved_order_key)`
    - 기존 unique 이름 변경 → `uk_payment_merchant_pay_key_provider_pg_payment_id_type`
  - CREATE TABLE `tbl_payment_reservation` (스키마는 `db-schema.md`)
  - `tbl_order` 에서 `merchant_pay_key` 컬럼 + `uk_order_merchant_pay_key` DROP

## 설계 방향

### 원칙

1. **Order ↔ Payment 도메인 분리** — Order 는 결제 식별자 모름
2. **PaymentReservation 과 Payment 분리** — 임시/상태 변화/대량 발생 (Reservation) vs 영구/append-only/PG 사건 (Payment)
3. **시도 = 불변 이벤트, 상태 = 도출** — append-only Payment 행 + EXISTS 기반 완료 판단
4. **1차 필터 + 최종 방어선** — 앱/Redis 레벨은 noise 감소용, 정합성은 DB unique 가 최종 보장
5. **존재 보장 = unique (gap lock 회피)**, **계산 판단 = PK 단일 행 lock**
6. **외부 호출은 트랜잭션 밖, DB 쓰기는 한 트랜잭션 안**

### NULL 트릭 unique (MySQL 우회)

PostgreSQL 의 partial unique 인덱스가 없어 NULL 트릭 사용:

```
tbl_payment_reservation.reserved_key   = RESERVED 일 때만 "{order_id}:{provider}", USED 면 NULL
tbl_payment.approved_order_key         = APPROVE+SUCCEEDED 일 때만 order_id, 그 외 NULL
```

NULL 은 unique 중복 허용 — 실패/취소/USED 행은 제약 받지 않음. 조건 충족 행만 unique 강제. 효과는 partial unique 와 동일.

**캡슐화 강제**:
- `Payment.succeed()` 메서드 안에서 `status=SUCCEEDED` 와 `approvedOrderKey=orderId` *같은 UPDATE* 에 묶음.
- `PaymentReservation.markUsed()` 메서드 안에서 `status=USED` 와 `reservedKey=NULL` *같은 UPDATE* 에 묶음.
- 두 캡슐화 모두 도메인 테스트로 단언 박아둠. 우회 setter 금지.

### 두 테이블의 관계

- 연결 고리는 `merchantPayKey` 값 (FK 제약 없음, 참조용 값)
- `Reservation 1 : N Payment` — 한 예약에서 여러 Payment 행 (APPROVE 시도 + CANCEL 시도) 비롯됨
- 단, 본 task 범위에선 `1 : 1 (APPROVE)` 까지만 실현. CANCEL 은 후속

### Reservation 의 한 번 전이

- `RESERVED → USED` 는 *첫 APPROVE 시도 시* 한 번 일어남
- 같은 Reservation 으로 재시도 불가 — FAILED 후 재시도는 *새 결제 시도* 이므로 *새 Reservation (새 키)* 발급
- 같은 merchantPayKey 로 redirect 가 또 오면 → USED Reservation 발견 → 기존 결제 결과 멱등 응답 (차단 아님)

### Reservation 의 만료

- `expires_at` 은 *재사용 판단* 에만 쓰임 (`isReusableFor` 필터)
- 만료/무효 (금액 변경 등) Reservation 은 reserve 진입 시 `markExpired` 로 *lazy 회수* (status=EXPIRED + reservedKey=NULL). reservedKey 점유를 풀어야 같은 (order, provider) 재예약이 `uk_payment_reservation_reserved_key` 위반 없이 가능
- lazy 회수라 별도 배치/스케줄러의 박제 위험 없음 (reserve 호출 요청이 자기 자리를 정리)
- 만료된 Reservation 에 redirect 가 늦게 와도 승인 진행 차단 안 함 (PG 가 SUCCESS 줬으면 우리 정책으로 막을 이유 없음)
- 물리 정리(EXPIRED 행 삭제)는 batch sweep 으로 (후속, 우선순위 낮음)

## 데이터 흐름

### Reserve (구 ready)

```
[ReservePaymentService.reserve(memberId, orderId, provider)]
  ├─ order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
  ├─ order.checkPayable()
  ├─ paymentRepository.existsUnknownByOrderId(orderId) → true 면 PAYMENT_RESULT_PENDING
  ├─ existing = paymentReservationRepository.findReserved(orderId, provider)   // RESERVED, 만료 무관
  ├─ if existing 유효(isReusableFor): reservation = existing                    // 재사용
  ├─ else if existing 만료/무효: existing.markExpired() + save → 새 발급          // reservedKey 회수
  ├─ if 없음:
  │     reservation = paymentReservationRepository.save(
  │         PaymentReservation.createReserved(orderId, memberId, provider, order.totalPrice, ttl=30m)
  │     )
  │     // uk_payment_reservation_reserved_key 가 동시 따닥 차단
  └─ return ReservePaymentResult(clientId, chainId, reservation.merchantPayKey, productName, totalPayAmount, returnUrl, ...)
```

### Approve

```
[NaverPayApprovalService.approve(memberId, merchantPayKey, pgPaymentId)]
  ├─ reservation = paymentReservationRepository.findByMerchantPayKey(merchantPayKey)
  │     └─ 없음: PAYMENT_NOT_FOUND
  ├─ reservation.memberId 검증 vs memberId
  ├─ order = orderRepository.findByIdAndMemberId(reservation.orderId, memberId)
  ├─ paymentRepository.existsUnknownByOrderId(reservation.orderId) → true 면 PAYMENT_RESULT_PENDING
  ├─ if reservation.status == USED:
  │     // 멱등 응답
  │     approved = paymentRepository.findApproveSucceeded(merchantPayKey).orElseThrow(...)
  │     return toResponse(approved)
  ├─ [트랜잭션 안]
  │     reservation.markUsed()                           // status=USED + reservedKey=NULL 동시
  │     attempt = paymentApprovalAttemptService.create(
  │         reservation, pgPaymentId
  │     )                                                 // type=APPROVE, status=REQUESTED
  ├─ [트랜잭션 밖] result = naverPayGateway.approve(pgPaymentId)
  ├─ switch result:
  │     SUCCESS  → completeVerifiedApproval(attempt, ...)
  │                  [트랜잭션 안] attempt.verifyApprovedResponse(...)
  │                                attempt.succeed(now)   // approvedOrderKey=orderId 같은 UPDATE
  │                                order.completePayment()
  │                                // uk_payment_approved_order_key 위반 → compensateDuplicateApproval
  │     FAILED   → attempt.fail(failCode, failDetail, now)
  │     UNKNOWN  → attempt.markUnknown(detail, now) → PAYMENT_RESULT_PENDING
  │     PROCESSING → 응답 PROCESSING
  └─ return
```

## 예외 및 실패 처리

| 상황 | 처리 |
|---|---|
| `uk_payment_reservation_reserved_key` 위반 (동시 reserve 따닥) | application 안전망 — find-first 패턴 (ADR-011). race 후속 요청은 find 단계에서 흡수, 진짜 race window 만 500 |
| `uk_payment_approved_order_key` 위반 (이미 결제된 주문에 새 APPROVE 성공) | `PaymentApprovalCompensationService.compensateDuplicateApproval` — PG cancel 실행해 보상 |
| PG approve 호출 timeout / 네트워크 단절 | `Payment.markUnknown(failDetail, now)` — UNKNOWN 흔적 |
| PG approve 응답 OK + DB 반영 실패 | UNKNOWN 흔적 (가능한 경우). 박제 RESERVED 방지 |
| UNKNOWN 행 있는 주문에 reserve/approve 재요청 | `PAYMENT_RESULT_PENDING` 응답 + 차단 |
| Reservation 박제 (status=RESERVED 인 채로 expiresAt 초과) | 다음 reserve 호출 시 `markExpired` 로 회수 (reservedKey=NULL) 후 새 RESERVED 발급 |
| 만료된 Reservation 에 redirect 도착 | 승인 진행 OK (expires_at 은 reserve 재사용 판단에만 쓰임) |
| 같은 merchantPayKey 의 redirect 중복 (USED Reservation) | 멱등 응답 — 기존 결제 결과 200 반환 (차단 아님) |
| ADR-010 amount mismatch | 새 Reservation 발급 (기존 Reservation amount UPDATE 금지) + `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` (409) |
| ADR-014 보상 cancel 진행 여부 | 기존 정책 유지 — 의미 보존, 구현 메서드만 새 모델로 갱신 |

## 테스트 포인트

### 도메인 (`PaymentReservation`)

- `createReserved(...)` → status=RESERVED, reservedKey="{orderId}:{provider}", expiresAt set
- `markUsed()` → status=USED + reservedKey=NULL (한 호출에서 모두 변경)
- `isReusableFor(memberId, provider, amount, now)` 분기 — provider/memberId/amount/expiresAt 불일치 시 false
- `markUsed()` after USED → 부적합 (이미 USED 인 행 재마킹 금지)

### 도메인 (`Payment`)

- `createRequested(reservation, type=APPROVE, pgPaymentId)` → status=REQUESTED, merchantPayKey/orderId/amount/provider 복사
- `succeed(now)` on type=APPROVE → status=SUCCEEDED + approvedOrderKey=orderId (한 호출에서 모두 변경)
- `succeed(now)` on type=CANCEL → status=SUCCEEDED + approvedOrderKey=null
- `fail(code, detail, now)` → status=FAILED + failCode/failDetail set
- `markUnknown(detail, now)` → status=UNKNOWN + failDetail set
- `verifyApprovedResponse` 검증 동일

### 통합 (Testcontainers MySQL)

- `uk_payment_reservation_reserved_key` — 같은 (orderId, provider) 로 두 RESERVED 동시 생성 → 두 번째 unique 위반
- RESERVED → USED 전이 시 reservedKey NULL 갱신, 새 Reservation 발급 가능
- `uk_payment_approved_order_key` — 같은 orderId 로 두 SUCCEEDED APPROVE 시도 → 두 번째 unique 위반

### Service

- `ReservePaymentService` — 재사용/만료/provider 불일치/memberId 불일치/amount 불일치 분기, UNKNOWN 차단
- `NaverPayApprovalService` — Reservation 기반 역조회, memberId 검증, USED 멱등 응답 흡수, 보상 path 4 종 (mismatch/duplicate/unexpected + `compensateDuplicateApproval`), UNKNOWN 마킹
- `PaymentApprovalService.hasCompletedPayment` — 새 구현이 ADR-014 의미 보존
- UNKNOWN 행 있는 주문에 reserve/approve 차단

### 동시성 (수동)

- 같은 (orderId, provider) RESERVED 동시 reserve
- 같은 orderId 에 두 APPROVE 동시 SUCCEEDED (다른 PG 시뮬레이션)
