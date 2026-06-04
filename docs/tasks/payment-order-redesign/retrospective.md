# 회고록: payment-order-redesign

## 1. 작업 요약

결제 도메인의 책임 누수를 정리하고 이중결제·따닥·UNKNOWN 의 방어선을 DB 제약으로 끌어올렸다. 핵심은 결제창 준비물(`PaymentReservation`)과 PG 에 실제로 보낸 사건(`Payment`)을 두 테이블로 분리하고, `Order`가 알고 있던 `merchantPayKey` 발급/저장 책임을 `PaymentReservation`으로 옮긴 것이다. MySQL InnoDB의 partial unique index 미지원 한계는 NULL 트릭(`uk_payment_approved_order_key`, `uk_payment_reservation_reserved_key`)으로 우회했다. reserve 흐름은 Reservation 생성/재사용(만료/무효 예약은 `markExpired`로 reservedKey를 회수), approve 흐름은 Reservation 역조회 + USED 멱등 흡수 + `RESERVED → USED` 한 번 전이 + `Payment(APPROVE)` 신규 행 구조로 재배선했다. UNKNOWN 상태는 마킹과 차단까지만 이번 task에 포함하고 해소는 후속으로 분리했다. 외부 API 이름도 의미에 맞게 `/payments/ready` → `/payments/reserve`로 정정했다.

코드 리뷰에서 드러난 결함들도 후속 수정으로 닫았다 — (1) 만료 예약의 reservedKey 미회수로 같은 주문 재예약이 영구 차단되던 것, (2) USED 예약의 미완료 승인(PROCESSING/중단)이 redirect 재시도에서 404로 영구 차단되던 것, (3) PG 네트워크 오류가 UNKNOWN이 아닌 FAILED로 분류돼 이중결제가 열려 있던 것. 세 건의 배경은 아래 발견 섹션에 정리한다.

---

## 2. 설계 결정

자세한 결정 본문은 [task ADR](./adr.md) 참조.

| ADR | 핵심 결정 |
|---|---|
| ADR-1 | `PaymentAttempt` → `Payment`(PG 사건 append-only)로 rename. 기존 `Payment`(성공 1:1) 폐기. `PaymentReservation` 신설. |
| ADR-2 | `merchantPayKey` 발급/저장 책임을 Order → PaymentReservation으로 이동. Order는 결제 식별자 모름. |
| ADR-3 | 이중결제·따닥 최종 방어선을 NULL 트릭 unique 두 개로 구현. `succeed()`/`markUsed()` 안에서 두 필드 동시 set 강제. |
| ADR-4 | 결제 완료 판단 = `EXISTS(성공 APPROVE)`. 마지막 행 기반 판단 금지. |
| ADR-5 | reserve 흐름은 Reservation 생성/재사용. 만료/무효 예약은 reserve 진입 시 `markExpired`로 reservedKey 회수(EXPIRED). 만료 후 늦은 redirect도 승인 차단 안 함. |
| ADR-6 | UNKNOWN은 마킹·차단까지만 이번 task. 해소(`PaymentReconciliationService`)는 후속 분리. |
| ADR-7 | 운영 데이터 없음 가정으로 backfill 없이 단순 schema 변경. |
| ADR-8 | 외부 PG 호출은 트랜잭션 밖, payment + order DB 쓰기는 한 트랜잭션 안. |
| ADR-9 | 존재 보장 = unique 제약, 계산 판단 = PK 단일 행 FOR UPDATE. gap lock 회피. |
| ADR-10 | 초기 A안(단일 테이블 `type ∈ {RESERVE, APPROVE, CANCEL}`)에서 B안(두 테이블 분리)으로 전환. 전환 이유는 4 위화감(아래 발견 섹션 참조). |

---

## 3. 발견

### A→B 전환의 가치는 위화감 4개로 드러났다

처음 설계(A안)는 단일 테이블의 단순함이 매력으로 보였다. 그러나 step 진행 중 위화감 4개가 누적 비용으로 드러났다.

1. **RESERVE와 APPROVE/CANCEL은 본질이 다르다.** APPROVE/CANCEL은 PG에 보낸 불변 사건이고, RESERVE는 결제창 준비물로 임시·만료·상태 변화 영역이다. 한 테이블에 두면 RESERVE가 PG 사건 테이블을 오염시킨다.
2. **A안은 `pg_payment_id`를 NULL 허용으로 완화한다.** RESERVE 행에 pgPaymentId가 없어서다. APPROVE/CANCEL 입장에서는 NOT NULL이 자연스러운 보장인데 RESERVE 때문에 포기하게 된다.
3. **A안은 `status` 컬럼에 두 의미를 섞는다.** `RESERVED/EXPIRED`와 `REQUESTED/SUCCEEDED/FAILED/UNKNOWN`이 한 컬럼에 공존하면 "결제 시도 몇 건?" 같은 단순 조회마다 `type != 'RESERVE'` 필터가 필요해진다.
4. **단일 테이블의 단순함은 착시다.** 테이블 수는 줄지만 NULL 컬럼(`expires_at`, `reserved_key`)이 늘고 status 의미가 혼재하고 쿼리 분기가 생긴다. 테이블 내부와 쿼리의 복잡도는 오히려 올라간다.

B안으로 전환하자 `tbl_payment`의 `pg_payment_id`가 NOT NULL로 돌아오고, `Payment.status`와 `PaymentReservation.status`가 각자 깔끔한 enum으로 분리됐다.

### Reservation의 "한 번 전이" 가 키 추적의 멱등성을 단순화한다

`RESERVED → USED` 한 번만 허용함으로써 *재시도 = 새 Reservation* 정신이 명확해졌다. FAILED 후 재시도가 *같은 merchantPayKey*로 들어오는 모호함이 사라졌다. 같은 키로 redirect가 또 오면 USED Reservation에서 기존 결제 결과를 멱등 응답으로 돌려주는 흐름이 자연스럽게 도출된다.

### 만료 회수는 필터만으로 부족했다 — 성급한 EXPIRED 제거가 정합성 구멍을 만들었다

초기 B안은 EXPIRED 상태를 제거하고 `expires_at` 필터만으로 박제를 자동 복구하려 했다 — "만료 마킹을 두면 누가 언제 마킹할지의 박제 위험이 새로 생긴다"는 판단이었다. 그러나 코드 리뷰에서 결함이 드러났다. 만료된 RESERVED 행은 status가 여전히 RESERVED라 `reserved_key`(`"{orderId}:{provider}"`)를 계속 점유한다. `expires_at` 필터는 *재사용*만 막을 뿐, *새 발급*은 같은 `reserved_key`로 `uk_payment_reservation_reserved_key` 위반을 일으켜 같은 주문이 영구 재예약 불가가 된다. "필터로 충분하다"가 *재사용* 한쪽만 본 결론이었던 것이다.

해법으로 EXPIRED를 재도입하되 *reserve 진입 시 lazy 회수*(`markExpired`: status=EXPIRED + reservedKey=null) 방식을 택했다. reserve를 호출하는 요청이 자기가 쓸 자리를 정리하므로, 초기에 우려했던 "누가 언제 마킹할지"의 별도 배치/스케줄러 박제 위험은 생기지 않는다. amount 변경 같은 무효화도 같은 경로로 회수된다. 단순화를 위해 상태를 성급히 제거한 것이 정합성 구멍을 만든 사례다 — *제거의 영향은 모든 경로(재사용 + 재발급)에서 따져야 한다.*

### USED 멱등 흡수는 "완료된 결제"만 다뤄 미완료를 가뒀다

USED Reservation에 redirect가 다시 오면 기존 결제 결과를 멱등 응답하도록 설계했으나, 초기 구현은 *SUCCEEDED Payment*만 찾아 반환하고 없으면 `PAYMENT_NOT_FOUND`(404)를 던졌다. PG가 PROCESSING을 반환했거나 호출 직전 중단되어 attempt가 REQUESTED로 남은 경우, redirect 재시도가 404로 막혀 결제가 영구 진행 불가가 된다. `findApproveAttempt`로 기존 시도를 찾아 `processApproveAttempt`(REQUESTED→PG 재확인 / SUCCEEDED→멱등 / FAILED→실제 사유)로 재처리하도록 고쳤다. "멱등 = 완료된 것 재반환"이라는 가정이 *진행 중* 상태를 빠뜨린 사례다.

### 결과 불명 오류를 FAILED로 분류하면 이중결제가 열린다

PG 승인 호출의 timeout/네트워크 오류는 `NaverPayClient`가 `NaverPayException(NETWORK)`으로 감싸고, `NaverPayGatewayImpl`이 이를 *FAILED*로 반환하고 있었다(그 아래 `catch (Exception)`의 UNKNOWN 분기는 도달 불가능한 죽은 코드였다). timeout은 *PG가 승인을 처리했는지 불명*인데 FAILED로 기록하면 `existsUnknownByOrderId` 차단이 걸리지 않아 재결제가 허용되고, PG가 이미 승인했다면 *이중결제*가 발생한다. 결과 불명 계열(NETWORK / SERVER_ERROR / INVALID_RESPONSE)을 UNKNOWN으로, 명확한 거절(CLIENT_ERROR / AUTHENTICATION)만 FAILED로 분류하도록 정정했다. 예외를 "실패"로 뭉뚱그리면 *결과 모름*과 *처리 안 됨*이 섞여 정합성이 깨진다 — UNKNOWN이라는 세 번째 상태가 필요한 이유다. (PG 예외 분류의 후속 정비는 #206으로 분리했다.)

### 만료 후 늦은 redirect를 막으면 오히려 더 위험하다

PG가 이미 돈을 빼간 상황에서 우리의 30분 정책으로 승인을 차단하면 *돈은 빠졌는데 주문은 미결제* 상태를 우리가 만드는 셈이다. `expires_at`은 내부 재사용 관리용이지 PG 결과를 거절하는 사유가 될 수 없다.

### NULL 트릭 캡슐화가 깨지면 정합성이 무너진다

`Payment.succeed()`의 `status + approvedOrderKey` 동시 set, `PaymentReservation.markUsed()`의 `status + reservedKey` 동시 set — 이 두 캡슐화가 task의 핵심 보호 수단이다. 도메인 메서드 안에 묶고 도메인 테스트로 단언을 박아뒀다. 우회 setter가 생기는 순간 MySQL NULL 트릭의 정합성 보장이 무너진다.

### Order가 결제 식별자를 모르게 만드는 것이 다중 PG 모델을 자연스럽게 연다

`Order.assignMerchantPayKey()`는 null일 때만 set하는 멱등 setter라 *주문당 키 하나*로 고정됐다. 이 제약을 Order에서 제거하자 같은 주문의 다중 PG 재시도나 amount 변경이 *새 Reservation 발급*으로 자연스럽게 표현된다. ADR과 코드의 불일치(ADR-010 "amount 변경 시 새 키 발급" 명시 vs 실제 코드에 없음)도 해소됐다.

### DB 인덱스 prefix 컨벤션은 초기에 확인해야 한다

task 문서 초안에서 `uq_` prefix를 썼지만 기존 V1~V4 마이그레이션의 `uk_` 컨벤션과 어긋났다. 인지 시점에 일괄 정정했지만, task 문서 작성 단계에서 기존 마이그레이션의 인덱스 naming을 먼저 확인했다면 이후 정정 비용이 없었다.

### frontend 미개발 상태가 호환 깨는 rename을 무비용으로 가능하게 한다

의미가 흐려진 `/payments/ready`를 `/payments/reserve`로 정정할 수 있었던 건 frontend가 미개발이었기 때문이다. 의미가 틀린 이름은 *후속 운영에서 더 비싸지므로* 지금 정정하는 게 옳은 선택이었다.

---

## 4. 미결 과제

| 항목 | 상태 | 승격/결정 조건 |
|---|---|---|
| `PaymentReconciliationService` — UNKNOWN 해소 (NaverPay 단건 조회 / 배치 대사) | 후속 task | 즉시 issue 발행 권장. UNKNOWN 차단만 있는 현재 상태는 *영영 안 풀릴 수 있음* 의 단점 보유 |
| 결제 취소 (`CANCEL` 흐름 실제 구현) | 미구현 | 현재 결제 흐름 검증 후 |
| 부분취소 로직 | 모델만 열어둠 (`Payment.type=CANCEL` 행에 `amount`) | 부분취소 요구 시 |
| 부분취소 도입 시 클라이언트 `idempotencyKey` 컬럼 | 미도입 | 부분취소 도입 시점. 자연 멱등키 부족해지는 시점 |
| `PaymentSummary` 집계 테이블 | 안 만듦 | 부분취소 도입 + 잔액 SUM 부담 커질 때 |
| PG 응답 원문 보관 테이블 (`PgTransactionLog`) | 로그 대체 중 | 분쟁/CS 증가 시 |
| USED/EXPIRED Reservation 물리 정리 batch | 안 만듦 | 테이블 비대 시점 |
| ArchUnit으로 `Payment.approvedOrderKey` / `PaymentReservation.reservedKey` 직접 set 가시성 강제 | 안 함 | 도메인 캡슐화 정책 위반 사고 발생 시 |
| workspace `docs/api-contract.md`의 `/payments/reserve` 반영 | Frontend 세션 책임 | 본 PR 머지 후 Frontend 세션이 갱신 |

---

## 5. 개선 제안

**두 도메인 분리를 다음 결제 작업의 baseline으로 쓴다.** `tbl_payment_reservation` + `tbl_payment` 구조가 이제 정착됐다. 취소·부분취소·대사 흐름은 이 구조 위에서 `Payment` append-only 행을 추가하는 방식으로 확장하면 된다.

**"두 필드 동시 set" 패턴을 다른 도메인에도 적용한다.** `Payment.succeed()` / `PaymentReservation.markUsed()`가 각각 두 필드를 한 메서드에서 묶는 패턴은 다른 도메인에도 유효하다. 예를 들어 `Order.completePayment()`가 `status + paidAt`을 함께 set해야 한다면 같은 방식으로 캡슐화하고 도메인 테스트로 단언을 박는다.

**UNKNOWN 같은 "세 번째 상태"를 다른 외부 연동에도 고려한다.** 성공/실패 외에 *결과 모름* 상태를 남기는 패턴은 PG 연동 외에도 메일 발송, 알림, 포인트 적립 등 외부 연동 어디에서나 재현된다. UNKNOWN 마킹 + 차단 + 후속 대사 구조를 다음 외부 연동 설계 시 참고한다.

**의미가 흐려진 외부 API 이름은 미개발 단계에 정정한다.** `/payments/ready → /payments/reserve` 결정처럼, 외부 이름이 내부 의미와 어긋났다고 느끼는 순간이 정정 비용이 가장 낮은 시점이다. 운영 트래픽이 붙으면 하위 호환 부담이 생기므로 미개발 구간을 활용한다.
