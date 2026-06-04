# 태스크 ADR

## ADR-1: 결제 도메인을 두 테이블 (Reservation + Payment) 로 분리하고 Payment 는 시도 단위 append-only 로 재정의한다

- **결정**:
  - 신규 `PaymentReservation` 도메인 — 결제창 준비물. status `{RESERVED, USED, EXPIRED}`. RESERVED 에서 USED (승인 시작) 또는 EXPIRED (만료/무효 회수) 로의 한 번 전이만 허용.
  - 현재 `PaymentAttempt` 를 `Payment` 로 rename. type ∈ `{APPROVE, CANCEL}` (RESERVE 없음), status ∈ `{REQUESTED, SUCCEEDED, FAILED, UNKNOWN}`. *PG 에 실제로 보낸 요청 사건* 의미. append-only.
  - 기존 `Payment` (성공 결제 1:1 단위) 폐기.
- **배경**: 현재 `Payment` 가 들고 있던 모든 사실 (`order_id`, `pgPaymentId`, `amount`, `approvedAt`, `provider`) 은 성공한 APPROVE PaymentAttempt 행이 이미 갖고 있다. 별도 테이블은 *성공 시도의 복사본* 일 뿐이다. 두 의미를 같은 이름 (`Payment`) 으로 충돌시키면 모델 의미가 흐려지므로 한쪽은 폐기, 한쪽은 의미 명확화. RESERVE 의 임시성·만료·상태 변화·대량 발생 성격은 PG 사건 (APPROVE/CANCEL) 과 본질이 달라 별도 테이블이 필요하다 (자세한 동기는 ADR-10).
- **근거**:
  - PG 사건은 한번 일어나면 바뀌지 않는 사실이다. 외부 PG 연동에서는 *결과 모름 (UNKNOWN)* 도 남겨야 하므로 시도 단위 기록이 필수.
  - Reservation 은 *결제창을 띄우기 위한 준비물* 이라 PG 요청 사건이 아니다. 라이프사이클이 짧고 상태가 변하며 따닥/이탈로 대량 생성.
  - 별도 *완료 상태* 테이블은 부분취소 같은 *여러 시도 집계 필요한 경우* 에만 정당. 지금 단순 1:1 완료 의미는 *성공 APPROVE 행 존재* 로 충분히 표현.
- **결과**: 모델 단순화. 부분취소·다중 PG 재시도·UNKNOWN 모델이 자연스럽게 표현 가능. ADR-014 의 "Payment row 존재 = 결제 완료" 의미는 보존되고 구현만 `existsByMerchantPayKeyAndTypeAndStatus(merchantPayKey, APPROVE, SUCCEEDED)` 로 갱신.

## ADR-2: merchantPayKey 발급/저장 책임을 Order → PaymentReservation 으로 이동한다

- **결정**: `Order.merchantPayKey` 컬럼/필드/메서드/repository 조회 메서드를 모두 제거한다. merchantPayKey 는 *서버가 reserve 단계에서* 발급해 `PaymentReservation` 행에 저장한다. 이 키가 PG redirect 의 역조회 entry 가 된다.
- **배경**: 현재 `Order.assignMerchantPayKey()` 는 null 일 때만 set 하는 멱등 setter 라 *주문당 키 하나* 로 고정. 가변 값 (재시도/amount 변경 시 새 키 필요) 을 불변 엔티티 (Order) 에 박는 부정합. ADR-010 본문이 "amount 변경 시 새 키" 라고 적었지만 코드는 새 키 발급 경로가 없어 ADR 과 코드가 불일치.
- **근거**:
  - Order 는 *무엇을 얼마에 산다* 의 책임. 결제 수단 식별자는 *그 돈을 어떻게 받는다* 의 책임이라 도메인이 다름.
  - merchantPayKey 의 unique 보장과 redirect 역조회는 결제 도메인 책임으로 옮기는 게 단일 책임에 맞다. 그 주인은 결제창 발급 entry 인 `PaymentReservation`.
- **결과**: Order 가 결제 수단을 모르게 됨. 같은 주문에 여러 결제 시도 (재시도/다중 PG) 가 자연스럽게 표현 가능. ADR-010 본문은 "amount 변경 → 새 Reservation 발급" 으로 정정 (이번 task 의 sync-root-docs 에서 처리).

## ADR-3: 이중결제와 reserve 따닥의 최종 방어선을 NULL 트릭 unique 로 구현하고 도메인 메서드 안에서 두 필드 동시 set 을 강제한다

- **결정**: MySQL InnoDB 가 partial unique index 를 미지원하므로 *조건 만족 시에만 값, 아니면 NULL* 컬럼 + 일반 unique 로 대체한다.
  - `tbl_payment.approved_order_key BIGINT NULL` + `UNIQUE uk_payment_approved_order_key (approved_order_key)` — APPROVE+SUCCEEDED 일 때만 `order_id`, 그 외 NULL
  - `tbl_payment_reservation.reserved_key VARCHAR(96) NULL` + `UNIQUE uk_payment_reservation_reserved_key (reserved_key)` — RESERVED 일 때만 `"{order_id}:{provider}"`, USED/EXPIRED 면 NULL
- **배경**: 다중 PG 이중결제는 merchantPayKey unique (PG 안에서의 중복) 로는 못 막는다. *주문 단위* 멱등을 끌어올려야 함. 동시에 reserve 단계의 동시 따닥도 *(주문, 수단) 당 RESERVED 1 개* 로 제약 필요. PostgreSQL 이라면 `WHERE` 조건 partial unique 로 깔끔하지만 InnoDB 미지원.
- **근거**:
  - InnoDB unique 는 NULL 을 중복으로 치지 않음 → 조건 안 맞는 행은 NULL 로 두면 제약 빠짐 → partial unique 와 동일 효과.
  - **캡슐화 강제** (두 도메인 모두에 적용):
    - `Payment.succeed()` 메서드 안에서 `status=SUCCEEDED` 와 `approvedOrderKey=orderId` *같은 UPDATE* 에 묶음.
    - `PaymentReservation.markUsed()` / `markExpired()` 메서드 안에서 `status` (USED / EXPIRED) 와 `reservedKey=NULL` *같은 UPDATE* 에 묶음.
    - 둘 다 우회 setter 금지. 도메인 테스트로 "한 메서드 호출 시 두 필드 동시 set" 을 단언 박아둠. 이 캡슐화가 깨지면 정합성 무너짐.
- **결과**: 이중결제 / reserve 따닥의 race condition 이 DB 레벨에서 차단. 효과는 partial unique 와 동일. NULL 트릭 자체는 SQL 마법이라 코드 캡슐화의 명확성 (한 메서드에서 두 필드를 함께 변경) 이 보장돼야 의미가 살아남.

## ADR-4: 결제 완료 판단은 마지막 행이 아니라 조건의 존재 (EXISTS) 로 한다

- **결정**: 결제 완료 = `(성공 APPROVE 존재) AND (그것을 무효화한 성공 CANCEL 부재)`. *마지막 행* 기반 판단 금지.
- **배경**: append-only 모델에서 *마지막 행* 은 마지막 시도 결과 (예: 취소 실패) 일 수 있어 *현재 상태* 와 다르다. 예: `#1 APPROVE SUCCEEDED → #2 CANCEL FAILED` (마지막) → 마지막만 보면 오판하지만 실제론 PAID.
- **근거**:
  - 결정 7 표:
    | 성공 APPROVE | 성공 CANCEL | 현재 상태 |
    |---|---|---|
    | 없음 | - | 미결제 |
    | 있음 | 없음 | **PAID** |
    | 있음 | 있음(전액) | 취소됨 |
    | 있음 | 있음(부분) | 부분취소 |
  - ADR-014 의 `hasCompletedPayment` 는 *완료 사실 조회* 의 단순 케이스 (전액 취소 부재 검증은 부분취소 도입 시 확장).
- **결과**: 부분취소 도입 시 모델 수술 없이 검증 확장. 현재는 `existsByMerchantPayKeyAndTypeAndStatus(merchantPayKey, APPROVE, SUCCEEDED)` 로 단순 구현. ADR-014 본문에 후속 노트 추가 (구현 갱신).

## ADR-5: reserve 흐름은 PaymentReservation 을 생성/재사용하고 만료/무효 예약은 진입 시 lazy 회수한다

- **결정**: `ReservePaymentService.reserve` (구 `PaymentReadyService.ready`) 는 `PaymentReservation` 행을 생성/재사용 + 반환한다. 진입 시 같은 `(orderId, provider)` 의 RESERVED 행을 만료 무관하게 조회 (`findReserved`) 한 뒤 도메인 `isReusableFor` (`status=RESERVED ∧ expiresAt>now ∧ provider 일치 ∧ memberId 일치 ∧ amount 일치`) 로 판정한다. 유효하면 재사용, 만료/무효 (금액 변경 등) 면 `markExpired` 로 `reservedKey` 를 회수 (status=EXPIRED + reservedKey=NULL) 한 뒤 새 행을 발급한다. 만료된 Reservation 에 redirect 가 늦게 도착해도 *승인 진행은 차단하지 않는다.*
- **배경**: 현재 코드 문제 (1) merchantPayKey 를 Order 에 저장해 키 고정 (2) Reservation 행 부재로 redirect 역조회 불가 (3) expiresAt/provider 기반 재사용/만료 부재.
- **근거**:
  - **redirect 역조회**: 네이버가 redirect 로 `merchantPayKey`, `pgPaymentId` 만 줌. 키를 저장해둔 적이 없으면 주문을 못 찾는다 → Reservation 생성이 *역조회 entry point* 의 전제.
  - **박제 (stale RESERVED) 자동 복구**: 판단에 expiresAt 을 넣어 *유효 RESERVED = status=RESERVED ∧ expiresAt>now*. 박제된 행은 만료되는 순간 재사용 대상에서 빠진다. 다만 RESERVED 상태인 동안 `reservedKey` (`"{orderId}:{provider}"`) 를 계속 점유하므로, 단순 필터만으로는 *새 발급* 이 `uk_payment_reservation_reserved_key` 위반으로 막힌다. 따라서 reserve 진입 시 만료 행을 `markExpired` 로 회수 (reservedKey=NULL) 한 뒤 새로 발급한다. 이 회수는 *reserve 를 호출하는 요청이 자기가 쓸 자리를 정리하는 lazy 방식* 이라 별도 배치/스케줄러의 박제 위험이 없다. (초기 B안은 EXPIRED 상태를 제거하고 필터만으로 두려 했으나, 만료 후 reservedKey 가 회수되지 않아 같은 주문의 재예약이 영구 차단되는 결함이 발견되어 EXPIRED + lazy 회수로 정정했다.)
  - **provider/memberId/amount 일치 조건**: 네이버로 띄웠다 카카오 선택한 사용자에게 네이버 결제창이 뜨는 사고 방지. amount 가 바뀌면 새 Reservation (Reservation 의 amount UPDATE 금지) — 같은 키로 다른 금액의 결제 시도를 추적 불가하게 만드는 모순 방지.
  - **만료 후 늦은 redirect 진행**: 돈은 PG 가 빼간 것. 우리 30m 정책으로 막으면 *돈은 빠졌는데 주문은 미결제* 박제 발생. expires_at 은 *우리 내부 재사용 관리* 용일 뿐 *PG 결과 거절* 사유가 될 수 없다. 단, 승인 시 amount 대조는 그대로.
  - **NaverPay reserve API 호출 없음** — 우리 서버가 키 발급 + 결제창 정보 (clientId/chainId/returnUrl) 만 만들어 프론트에 반환.
  - **같은 키 redirect 중복 → 멱등 응답**: USED Reservation 발견 시 *이미 결제됨* 차단이 아니라 기존 결제 결과 200 응답으로 흡수 (PG 의 redirect 멱등 정신).
- **결과**: reserve 따닥은 `uk_payment_reservation_reserved_key` + expiresAt 재사용으로 차단. 박제 자동 복구. 다중 PG 자연 지원. status enum 은 `{RESERVED, USED, EXPIRED}`. 만료/무효 행은 reserve 진입 시 EXPIRED 로 회수되어 reservedKey 점유를 푼다. 재사용 판정은 JPQL 이 아니라 도메인 `isReusableFor` 가 담당한다 (규칙 단일화).

## ADR-6: UNKNOWN 상태는 *마킹* 까지만 이번 task 에 포함하고 *해소 (대사)* 는 후속 task 로 분리한다

- **결정**: 이번 task 는 (a) `PaymentStatus.UNKNOWN` enum 추가 (b) PG 호출 timeout / 응답 후 DB 반영 실패 시 `Payment.markUnknown` 마킹 (c) UNKNOWN 행 있는 주문에 reserve/approve 차단 + "확인 중" 응답까지 처리한다. NaverPay 단건 대사 호출 / 주문내역 read 경로의 trigger / 배치 대사는 후속 task 로 분리한다.
- **배경**: UNKNOWN 처리는 *시도 마킹* (방어선 보강) 과 *해소* (별도 service 책임) 의 두 단계. 한 PR 에 욱여넣으면 review 비용이 커지고 책임 경계가 흐려진다.
- **근거**:
  - 후속이 안 들어와도 시스템은 안전 — 사용자 안내 + 재시도 차단으로 추가 사고 없음. 단점은 UNKNOWN 행이 영영 안 풀릴 수 있음.
  - 해소는 *읽기 경로 단건 대사* / 배치 대사라는 별도 책임 (`PaymentReconciliationService`) 이 생김. 이번 PR 의 핵심인 *모델 갈아엎기 + 흐름 재배선* 과 책임이 다름.
- **결과**: 후속 task 로 `PaymentReconciliationService` 신설 issue 발행. 이번 PR 은 모델 정합성만 보장.

## ADR-7: Order.merchantPayKey 데이터 마이그레이션은 backfill 없이 단순 schema 변경으로 처리한다

- **결정**: 운영 데이터가 없음을 전제로 Flyway V6 에서 `tbl_order.merchant_pay_key` 컬럼과 `uk_order_merchant_pay_key` 를 직접 DROP. 기존 데이터를 새 구조로 옮기는 backfill SQL 은 추가하지 않는다.
- **배경**: 학습/포트폴리오 프로젝트 단계. 운영 사용자 결제 이력 보존 요구 없음.
- **근거**: backfill SQL 추가 비용 > 데이터 보존 가치. 단순 schema 변경이 마이그레이션 위험을 낮춤.
- **결과**: 만약 운영 단계로 진입한 후 같은 패턴의 변경이 필요하면 별도 backfill task 가 필요. 본 ADR 은 *현재 단계 한정* 정책.

## ADR-8: 외부 PG 호출은 트랜잭션 밖, payment + order DB 쓰기는 한 트랜잭션 안

- **결정**: 승인/취소 API 호출은 트랜잭션 밖에서 수행하고, 결과를 받아 DB 반영 (`payment` 상태 전이 + `order` 상태 전이) 은 *하나의 짧은 트랜잭션* 안에서 처리한다. 또한 *Reservation.markUsed + Payment(REQUESTED) INSERT* 도 한 트랜잭션 안에서 묶어 처리한다 (PG 호출 전 단계).
- **배경**: 외부 호출을 트랜잭션 안에 두면 커넥션을 오래 점유. 두 쓰기를 별도 트랜잭션으로 나누면 *결제됐는데 주문 미결제* 불일치 위험.
- **근거**: 박제 문제 (승인 성공 후 DB 반영 실패) 도 이 경계에서 발생 → UNKNOWN 흔적 (ADR-6) 으로 처리.
- **결과**: 현재 코드의 정책이 이미 이렇게 동작. 새 모델에서도 동일 원칙 유지. `NaverPayApprovalService.completeVerifiedApproval` 의 트랜잭션 경계는 유지하되 내부 호출이 새 Payment / Reservation 메서드로 갱신.

## ADR-9: 동시성 — 존재 보장은 unique 제약, 계산 판단은 PK 단일 행 FOR UPDATE

- **결정**:
  - *존재 보장* (없으면 INSERT, 있으면 막기): `uk_payment_approved_order_key` / `uk_payment_reservation_reserved_key` unique 제약. lock 아님.
  - *계산 기반 판단* (여러 행 합산 후 결정, 예: 부분취소 과다취소 검증): Order PK 단일 행 FOR UPDATE.
  - *피할 것*: "없는 행 조회 FOR UPDATE → 없으면 INSERT" 패턴. InnoDB gap lock 으로 옆 범위 INSERT 까지 막는다.
- **배경**: 결제 도메인의 동시성은 *남의 결제* 까지 막지 않아야 한다. 단 정합성은 반드시 보장.
- **근거**: InnoDB 의 row lock 은 그 행에만 영향. gap lock 은 *존재하지 않는 행을 조건으로 잠그거나 범위로 잠그면* 빈 구간까지 잠가 다른 INSERT 를 막는다. 적절한 인덱스 없으면 더 넓게 잠긴다. unique 는 INSERT 시도 후 충돌 시 거부 → gap 을 미리 안 잠그므로 다른 결제에 영향 없음.
- **결과**: 이번 task 는 unique 제약 두 개 + (부분취소 도입 시 적용할) Order PK lock 패턴만 모델에 열어둠. 부분취소 로직은 후속.

## ADR-10: RESERVE 거주지를 Payment 단일 테이블에서 PaymentReservation 별도 테이블로 분리한다 (A안 → B안 전환)

- **결정**: 초기안 (A안) 은 *단일 `tbl_payment` 테이블에 `type ∈ {RESERVE, APPROVE, CANCEL}` 을 모두 담는* 구조였다. 본 task 에서 이를 **두 테이블 분리 (B안)** — `tbl_payment_reservation` (RESERVED/USED/EXPIRED) + `tbl_payment` (APPROVE/CANCEL append-only) — 로 전환한다.
- **배경 (A안에서 발견된 4 위화감)**:
  1. **RESERVE 와 APPROVE/CANCEL 은 본질이 다르다.** APPROVE/CANCEL 은 PG 에 보낸 사건이고 결과 확정 후 불변. RESERVE 는 결제창 준비물이고 임시·만료·따닥 대량 발생·상태 변화 영역. 한 테이블에 두면 RESERVE 가 APPROVE/CANCEL 테이블을 오염시킨다.
  2. **A안은 `pg_payment_id` 를 NULL 허용으로 완화시킨다.** RESERVE 에 pgPaymentId 가 없어서다. APPROVE/CANCEL 만 보면 NOT NULL 이 자연스러운데 RESERVE 때문에 보장을 포기.
  3. **A안은 `status` 컬럼에 두 의미를 섞는다.** RESERVE 의 `RESERVED/EXPIRED` 와 APPROVE/CANCEL 의 `REQUESTED/SUCCEEDED/FAILED/UNKNOWN` 이 한 컬럼에 공존. "결제 시도 몇 번?" 같은 조회마다 `type != 'RESERVE'` 필터 필요.
  4. **단일 테이블의 단순함은 착시.** 테이블 수는 줄지만 각 행에 NULL 컬럼 (`expires_at`, `reserved_key`) 이 늘고 status 의미가 혼재하고 조회 분기가 생긴다. 테이블 내부 복잡도와 쿼리 복잡도가 올라간다.
- **근거 (B안의 이득)**:
  - Reservation 의 "상태 변화 + 임시 + 만료 + 대량" 성격을 Reservation 테이블이 자기 책임으로 가져가면, `tbl_payment` 는 "PG 사건" 만 담는 순수한 append-only 테이블이 되고 `pg_payment_id` 도 NOT NULL 로 돌아온다.
  - `Reservation.status` enum 이 `{RESERVED, USED, EXPIRED}` 로 정리됨 (A안의 status 의미 혼재 제거). EXPIRED 는 만료/무효 예약의 reservedKey 회수용으로, reserve 진입 시 lazy 처리된다 (ADR-5 참조).
  - `Payment.status` enum 도 `{REQUESTED, SUCCEEDED, FAILED, UNKNOWN}` 4개로 정리되어 의미가 한 결.
- **트레이드오프**: 테이블 수 +1. 그러나 의미·스키마 단순화 + 쿼리 명확성 + NOT NULL 회복의 이득이 크다.
- **불변 결정 (A안→B안에서도 그대로)**:
  - 시도 단위 append-only (Payment 행 불변, 사건은 새 행)
  - merchantPayKey 책임 Order → 결제 도메인 (이제 Reservation 이 owner)
  - 완료 판단 = EXISTS
  - 이중결제 최종 방어선 = `uk_payment_approved_order_key` NULL 트릭
  - UNKNOWN 마킹까지, 해소는 후속
  - 외부 PG 호출 트랜잭션 밖, DB 쓰기 한 트랜잭션 안
  - 존재 보장 = unique, 계산 판단 = PK FOR UPDATE, gap lock 회피
  - backfill 없이 단순 schema 변경
- **결과**: ADR-1 / ADR-3 / ADR-5 본문이 B안 기준으로 갱신됨. `tbl_payment_reservation` 신규 테이블. Reservation 의 `markUsed` NULL 트릭 캡슐화가 ADR-3 에 명시. amount mismatch → 새 Reservation 정책이 ADR-5 에 명시. 만료 후 늦은 redirect 진행 정책이 ADR-5 에 명시. 같은 키 redirect 중복은 멱등 응답 흡수 정책이 ADR-5 에 명시.
