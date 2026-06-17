# Architecture — PAID 주문 취소·환불

## 구성 요소와 책임

이 작업은 주문 취소가 결제 환불을 엮는 cross-aggregate 흐름이다. 결합은 조율 지점 한 곳에
격리하고, 각 도메인은 자기 일만 한다.

### 조율 (order.application)

- **취소 조율 usecase** (`…UseCase`, tx 없음): 사용자 취소 흐름을 조립한다.
  주문 상태에 따라 INIT(기존: 재고만 복구)과 PAID(환불 포함) 경로를 선택하고,
  tx 단위작업(아래 조율 service)을 호출한 뒤, 커밋 이후 PG 환불을 실행한다.
- **취소 조율 service** (`…Service`, `@Transactional`): PAID 취소의 원자적 단위작업.
  주문 행을 잠그고(FOR UPDATE) 상태를 검증한 뒤, **한 tx 안에서** order·payment·stock에 걸쳐
  (1) 환불 대상 결제(SUCCEEDED APPROVE) 식별, (2) 환불 의도(CANCEL 결제 REQUESTED) 영속화,
  (3) `order.cancel()` 전이, (4) 재고 복구를 함께 커밋한다. PG 외부 호출은 이 tx에 넣지 않는다.
  CANCEL 생성 멱등은 기존 unique `uk_payment_merchant_pay_key_provider_pg_payment_id_type`가 하드로
  보장하고(ADR-L5), order 잠금은 보조 직렬화다.

> cross-aggregate 단일 tx 쓰기·application 간 의존은 ArchUnit(`ArchitectureRulesTest`)이 막지
> 않으며, 기존 `CancelOrderService`가 `stock.application` service에 의존하는 선례와 동일하다.
> `@Transactional`은 service에만, usecase에는 없다(ADR-055 정합).

### 주문 (order.domain)

- `Order.cancel()`이 PAID 전이를 허용하도록 확장한다. 취소 가능 상태(INIT·PAID)와
  그 외 거부, 이미 CANCELED인 경우의 처리(멱등/거부)를 도메인이 규정한다.

### 결제 (payment.application / payment.domain)

- **환불 대상 조회**: orderId로 SUCCEEDED APPROVE 결제를 가져오는 조회를 추가한다(기존엔 존재
  여부 boolean만 있었다). 주문당 SUCCEEDED APPROVE는 유일하므로 `Optional`로 반환한다.
- **환불 실행 경로**: 영속된 CANCEL 결제(REQUESTED)에 대해 PG 취소를 호출하고 결과
  (SUCCEEDED/FAILED/UNKNOWN)를 반영한다. 기존 CANCEL 상태전이 service들과 `PgCanceller` 콜백을
  재사용하되, `runPgCancel`을 쪼개 쓰지 않고 별도 경로로 둔다(approve 불변, ADR-L2).
- **CANCEL 대사**(신설, ADR-L4): stale standalone CANCEL(REQUESTED/UNKNOWN)을 스캔해 PG 재조회·
  재실행으로 종착시킨다. 환불 의도가 PG 호출 전/중 중단으로 남으면 이 대사가 마무리한다.

### CANCEL 대사 — 죽은 정책의 live화

기존 `ReconcilePaymentUseCase`는 APPROVE만 스캔하고 `CANCEL_RECONCILE`을 SKIP한다. 정책 뼈대
(`PaymentPostProcessTargetPolicy`의 CANCEL 분기, `PaymentPostProcessFlowPolicy`의 CANCEL 매트릭스)는
이미 존재하나 배선이 죽어 있다. 신설하는 것은 두 가지뿐이다.

- **스캔 쿼리**: `findStaleCancelPaymentsForReconciliation` (type=CANCEL, REQUESTED/UNKNOWN, cutoff).
  APPROVE 스캔과 동일 cutoff·페이징 정책.
- **reconcile 루프의 CANCEL 처리 분기**: 후보를 `resolvePostProcessTarget(approve=null, cancel=p)`
  → `CANCEL_RECONCILE` → `getApprovalHistory`로 PG 상태 조회 → flow 매트릭스로 분기:
  PG 취소됨 → CANCEL SUCCEEDED / PG 승인 유지 → 취소 재시도 / PENDING·NOT_FOUND → KEEP_WAITING.
  6시간 초과는 escalation 통지(APPROVE와 동일).

## 데이터 흐름 (PAID 취소)

```
[조율 service · 단일 tx]
  order FOR UPDATE → PAID 검증
  → 환불 대상(APPROVE SUCCEEDED) 조회
  → CANCEL 결제 REQUESTED 영속화 (환불 의도, order 잠금 안에서 — 멱등)
  → order.cancel() (PAID→CANCELED)
  → 재고 복구
  commit
        │
        ▼
[조율 usecase · tx 밖]
  best-effort PG 취소 실행 (환불 실행 경로)
    SUCCESS  → CANCEL SUCCEEDED (환불 완료)
    FAILED   → CANCEL FAILED
    UNKNOWN  → CANCEL UNKNOWN ── 안전망 ──┐
    (호출 전 중단) → CANCEL REQUESTED 잔존 ─┤
                                          ▼
                          [CANCEL 대사 · @Scheduled]
                          stale CANCEL 스캔 → PG 재조회 → 재시도/확정
```

## 경계 결정

- 환불 의도(CANCEL REQUESTED)를 주문 취소와 한 RDB tx에 묶어 원자성을 확보한다(ADR-L1).
  단일 DB 조건을 활용해 이벤트/Outbox 없이 cross-aggregate 정합을 보장한다.
- 환불의 최종 보장은 인라인 best-effort가 아니라 **CANCEL 대사**(ADR-L4)가 진다. 인라인은
  happy path 지연 단축용이다.
- 조율 usecase·service가 application 계층에서 order·stock·payment service에 의존한다.
  기존 order→stock application 의존과 동일한 패턴이다.
