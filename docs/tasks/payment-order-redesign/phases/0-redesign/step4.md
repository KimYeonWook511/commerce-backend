# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 task 결정과 현재 루트 docs 상태를 파악하라:

- `docs/tasks/payment-order-redesign/prd.md`
- `docs/tasks/payment-order-redesign/architecture.md`
- `docs/tasks/payment-order-redesign/adr.md`
- `docs/tasks/payment-order-redesign/db-schema.md`
- `docs/tasks/payment-order-redesign/api-spec.md`
- `docs/adr.md`
- `docs/architecture.md`
- `docs/db-schema.md`
- `docs/api-spec.md`
- `docs/exception-strategy.md`

## 작업

이번 task 결정을 루트 docs 에 반영한다. **머지된 task 폴더는 건드리지 않는다** (CLAUDE.md 불변 원칙).

### 1. `docs/adr.md`

#### ADR-010 정정 (amount 변경 시 새 Reservation)

- 기존 "amount 변경이 필요하면 새 `merchantPayKey`로 새 요청을 발급하는 게 정상 흐름이다." 정정:
  - "amount 변경 시 새 RESERVED `PaymentReservation` (새 키) 을 발급한다. 기존 Reservation 의 amount UPDATE 는 금지된다."
- 후속 노트 추가: "본 ADR 의 *재요청* 의미는 `payment-order-redesign` task 에서 *PaymentReservation 신규 발급* 으로 명확히 정리됐다. `Reservation.amount` 는 불변이며, amount mismatch 는 *새 Reservation* 으로 표현된다."

#### ADR-014 후속 노트 (구현 갱신)

- 메서드 이름 (`hasCompletedPayment`) 과 정책 (cancel skip 판단) 은 보존
- 후속 노트 추가: "본 ADR 의 *Payment row 존재 = 결제 완료* 사실 조회는 `payment-order-redesign` task 에서 두 테이블 분리 모델로 재정의됐다. 구현은 `existsApproveSucceeded(merchantPayKey)` 로 갱신 — *`tbl_payment` 의 type=APPROVE ∧ status=SUCCEEDED* 인 행 존재. 의미는 동일."

#### ADR-015 후속 노트 (compensateDuplicateApproval 추가)

- 후속 노트 추가: "`payment-order-redesign` 에서 `compensateDuplicateApproval` 보상 dispatcher 가 추가됐다. `uk_payment_approved_order_key` (NULL 트릭 partial unique) 위반 시나리오 — 같은 orderId 에 두 번째 APPROVE 가 성공으로 진입한 경우 — 를 막고 PG cancel 로 환불한다. 책임 위치는 본 ADR 그대로 (`PaymentApprovalCompensationService`)."

#### 신규 ADR 추가 (재설계 결정 요약)

- 새 ADR 번호 (`ADR-XXX`, 본문 마지막 ADR 번호 확인 후 +1)
- 제목: "결제 도메인 재설계 — Order↔Payment 경계 분리 + RESERVE 별도 거주지 (B안)"
- 본문: task `adr.md` 의 10 개 ADR 결정을 *요약* + 본문 링크. *결정 전문을 옮기지 마라.*
- 핵심:
  - 두 테이블 분리 — `tbl_payment_reservation` (RESERVED/USED) + `tbl_payment` (APPROVE/CANCEL append-only)
  - merchantPayKey 책임 Order → PaymentReservation 이동
  - MySQL NULL 트릭으로 partial unique 대체 (`uk_payment_approved_order_key`, `uk_payment_reservation_reserved_key`)
  - 완료 판단 = EXISTS (성공 APPROVE ∧ 무효화 CANCEL 부재)
  - UNKNOWN 마킹 (해소는 후속 task)
  - `/payments/ready` → `/payments/reserve` rename (frontend 미개발이라 호환 깨도 무방)

### 2. `docs/db-schema.md`

#### `tbl_order` 섹션 갱신

- `merchant_pay_key` 컬럼 / `uk_order_merchant_pay_key` 제거 명시
- 컬럼 표 갱신

#### `tbl_payment` 섹션 전면 갱신

- 기존 *성공 결제 1:1* 정의 → *PG 사건 단위 (주문당 N 개, append-only)* 로 재정의
- 컬럼 표:
  - `id`, `order_id` (FK 제약 없음 명시), `merchant_pay_key`, `pg_payment_id` (NOT NULL), `amount`, `provider`, `type` (`APPROVE`/`CANCEL`), `status` (`REQUESTED`/`SUCCEEDED`/`FAILED`/`UNKNOWN`), `fail_code`, `fail_detail`, `approved_order_key`, `responded_at`, `created_at`, `updated_at`
- 인덱스:
  - `uk_payment_merchant_pay_key_provider_pg_payment_id_type` — 같은 시도 중복 차단
  - `uk_payment_approved_order_key` — 이중결제 차단 (NULL 트릭)
  - `idx_payment_order` — 주문별 조회 / UNKNOWN 차단 검사
- 무결성 규칙:
  - NULL 트릭 캡슐화 — `approved_order_key` set 은 status=SUCCEEDED+type=APPROVE 와 같은 UPDATE 에서만. 도메인 메서드 (`succeed`) 안 캡슐화 필수
  - `order_id` 는 FK 제약 없음 (참조용 값)
  - append-only — 행 삭제 금지

#### `tbl_payment_reservation` 섹션 신규 추가

- 정의: *결제창 준비물*. status `{RESERVED, USED}` 의 한 번 전이만 허용
- 컬럼 표:
  - `id`, `order_id`, `member_id`, `provider`, `merchant_pay_key`, `amount`, `status`, `expires_at`, `reserved_key`, `created_at`, `updated_at`
- 인덱스:
  - `uk_payment_reservation_merchant_pay_key` — redirect 역조회 키 unique
  - `uk_payment_reservation_reserved_key` — RESERVED 따닥 차단 (NULL 트릭)
  - `idx_reservation_order` — 주문별 조회 / UNKNOWN 차단 검사
- 무결성 규칙:
  - NULL 트릭 캡슐화 — `reserved_key` set 은 status=RESERVED 와 같은 INSERT 에서만, status=USED 로 가면 같은 UPDATE 에서 NULL. 도메인 메서드 (`createReserved`, `markUsed`) 안 캡슐화 필수
  - `order_id`, `member_id` FK 제약 없음 (참조용 값)
  - amount UPDATE 금지 — 변경 시 새 Reservation
  - EXPIRED 마킹 없음 — 만료는 `expires_at` 필터로만 판단

#### `tbl_payment_attempt` 섹션 제거

- 테이블이 `tbl_payment` 로 rename + 컬럼 정리됐음을 명시 + 섹션 삭제

### 3. `docs/architecture.md`

#### 결제 도메인 흐름 섹션 갱신

- reserve → approve 흐름 다이어그램 / 글 표현 갱신:
  - reserve: PaymentReservation 행 생성/재사용 (Order 안 거침)
  - approve: PaymentReservation 기반 역조회 → memberId 검증 → UNKNOWN 차단 → USED 멱등 흡수 → markUsed + Payment(APPROVE) INSERT → PG approve → succeed
- 서비스 표:
  - `PaymentReadyService` → `ReservePaymentService` (이름 변경 + 책임은 Reservation 발급/재사용)
  - `PaymentApprovalService.completeApprovedPayment` 제거 (의미가 `Payment.succeed()` 에 흡수)
  - `PaymentApprovalService.findPaymentByMerchantPayKey` 제거
  - `PaymentApprovalService.hasCompletedPayment` 의미 유지, 구현 갱신
  - `PaymentApprovalCompensationService.compensateDuplicateApproval` 신규 추가
  - `OrderQueryService.getOrderByMerchantPayKey*` 제거
- 도메인 경계 표:
  - Order 는 결제 식별자 모름 (merchantPayKey 제거)
  - PaymentReservation 은 *결제창 준비물* 의미
  - Payment 의 *PG 사건 단위* 의미 명시

### 4. `docs/api-spec.md`

#### `/payments/ready` 섹션

- URL 변경: `POST /payments/ready` → `POST /payments/reserve`
- 응답 본문 구조 동일 (DTO class 이름만 rename)
- 비고에 *내부 동작 변경* 표기 (PaymentReservation 행 생성/재사용)

#### `/payments/naverpay/approve` 섹션

- 응답 본문 동일
- 비고에 *역조회 경로 변경* 표기 (Order → PaymentReservation)
- *같은 키 redirect 중복은 멱등 응답 200* 추가 명시

#### 새 응답 코드

- `PAYMENT_RESULT_PENDING` 추가 (HTTP 409) — UNKNOWN 행 있는 주문에 reserve/approve 차단
- `PAYMENT_MEMBER_MISMATCH` 추가 (HTTP 403) — Reservation.memberId 와 SecurityContext memberId 불일치

### 5. `docs/exception-strategy.md`

#### "보상 catch 2차 예외 처리" 섹션 후속

- `compensateDuplicateApproval` 케이스 한 줄 추가 (`DataIntegrityViolationException` catch 의 예외적 허용 + 이유)

#### "결제 결과 UNKNOWN 처리" 새 섹션 추가

- 마킹 정책: PG timeout / 응답 후 DB 반영 실패 → `Payment.markUnknown`
- 차단 정책: UNKNOWN 행 있는 주문에 reserve/approve 진입 → `PAYMENT_RESULT_PENDING` (HTTP 409)
- 해소 정책: 후속 task `PaymentReconciliationService` 신설 issue 링크

#### "결제 redirect 멱등 응답" 새 섹션 추가

- USED PaymentReservation 에 redirect 가 중복 도착 → 차단이 아닌 *기존 결제 결과 200 응답* 흡수
- 근거: PG redirect 본질이 *한 번 = 한 번*, 같은 키 중복은 *동일 결과 재반환*

### 6. commit 분리

이 step 의 변경은 *문서만* — 한 commit 으로 묶거나 docs 파일 단위로 분리 가능:

1. `docs: 결제 도메인 재설계 결정을 루트 docs 에 동기화한다` — 한 묶음으로 충분

## Acceptance Criteria

```bash
./gradlew test
```

(이 step 은 문서 변경이라 빌드 영향 없음. 테스트 통과는 *이전 step 결과의 회귀 없음* 확인용)

## 검증 절차

1. 위 커맨드 통과
2. `docs/adr.md`:
   - ADR-010 본문 정정 + 후속 노트 추가됨
   - ADR-014 후속 노트 추가됨
   - ADR-015 후속 노트 추가됨
   - 새 ADR (재설계 결정 요약, B안 + reserve rename) 추가됨
3. `docs/db-schema.md`:
   - `tbl_order.merchant_pay_key` 언급 0건
   - `tbl_payment_attempt` 언급 0건
   - 새 `tbl_payment` 의 PG 사건 단위 정의 + `uk_payment_approved_order_key` NULL 트릭 명시
   - 새 `tbl_payment_reservation` 섹션 + `uk_payment_reservation_reserved_key` NULL 트릭 명시
4. `docs/architecture.md`:
   - 결제 흐름 다이어그램 / 표 갱신
   - 폐기 메서드명 (`completeApprovedPayment`, `findPaymentByMerchantPayKey`, `getOrderByMerchantPayKey*`) 0건
   - `PaymentReadyService` 0건, `ReservePaymentService` 등장
5. `docs/api-spec.md`:
   - `PAYMENT_RESULT_PENDING` 추가됨
   - `PAYMENT_MEMBER_MISMATCH` 추가됨
   - `/payments/ready` 0건, `/payments/reserve` 등장
6. `docs/exception-strategy.md`:
   - UNKNOWN 처리 섹션 추가됨
   - redirect 멱등 응답 섹션 추가됨
7. 머지된 task 폴더 (`docs/tasks/payment-attempt-*`, `docs/tasks/payment-compensation-*` 등) 가 *수정되지 않음* 확인 (`git diff --stat` 으로 확인)

## 금지사항

- 머지된 task 폴더 (`docs/tasks/payment-attempt-*`, `docs/tasks/payment-compensation-*`, `docs/tasks/payment-jpa-association-decouple/`, `docs/tasks/order-*`, `docs/tasks/cross-aggregate-fk-cleanup/` 등) 의 문서를 수정하지 마라. 이유: CLAUDE.md 의 *완료된 task 불변 원칙*. 변경은 본 step 의 루트 docs 갱신으로만 표현한다.
- `docs/ddd/*.md` 회고 문서를 수정하지 마라. 이유: 역사 기록 불변 원칙.
- workspace 공유 문서 (`commerce-workspace/docs/api-contract.md`, `commerce-workspace/docs/progress.md` 등) 를 수정하지 마라. 이유: backend 세션 책임이 아님. Frontend 세션이 별도로 갱신.
- 새 ADR 본문에 *결정 내용 전체* 를 길게 다시 옮겨 적지 마라. 이유: 상세는 `docs/tasks/payment-order-redesign/adr.md` 에 있다. 루트 ADR 은 *요약 + 본문 링크* 만.
- 폐기 메서드명 (`getOrderByMerchantPayKey`, `completeApprovedPayment`, `findPaymentByMerchantPayKey`) 을 *deprecated* 로 남기지 마라. 이유: 이번 PR 의 의도는 *폐기* 다. 흔적 보존은 git log 와 본 task 문서에 충분히 남는다.
- `tbl_payment_attempt` 잔여 언급을 남기지 마라. 이유: rename 됐고 본 task 의 의도는 *옛 이름 완전 정리*.
