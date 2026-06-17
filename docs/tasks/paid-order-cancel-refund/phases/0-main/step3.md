# step3 — paid-order-cancel-orchestration

## 목표

PAID 주문 취소 흐름을 통합한다. `Order.cancel()`을 PAID 허용으로 확장하고, 주문 취소·재고
복구·환불 의도 영속화를 한 tx로 묶는 조율 service와, 커밋 후 best-effort PG 환불을 실행하는
조율 usecase를 만들고, 컨트롤러가 이 흐름으로 라우팅하게 한다. step1(인라인 실행)·step2(안전망)
위에 얹는다.

## 배경·맥락

- 단일 RDB tx에 `CANCEL 결제 REQUESTED(환불 의도) + order.cancel() + 재고 복구`를 원자적으로
  커밋한다(ADR-L1). PG 호출은 tx 밖 best-effort, 최종 보장은 step2의 CANCEL 대사가 진다.
- 현재 사용자 취소는 `CancelOrderService`(order.application.service, `@Transactional`)가
  `order.cancel()` + 동기 재고 복구(`IncreaseStockService`)를 하며 INIT만 처리한다.
- 재고 차감은 주문 생성 시점에 일어난다. 취소 시 동기 재고 복구는 기존 사용자 취소 경로와 동일
  방식(만료 배치의 outbox 비동기와 다름 — 사용자 경로는 동기 유지).

## 구현 지시

### 1) 도메인: Order.cancel() PAID 허용

- `Order.cancel()`이 INIT에 더해 PAID 전이를 허용하도록 확장한다(PAID→CANCELED).
- 취소 허용 상태(INIT·PAID) 외에는 거부한다. 이미 CANCELED인 주문 처리(멱등 no-op 또는 명시적
  거부)를 도메인에서 규정하고 테스트로 고정한다 — 중복 취소에 환불이 1회만 일어나는 근거다.

### 2) 조율 service (order.application.service, @Transactional)

- PAID 취소의 원자적 단위작업. 한 tx 안에서:
  1. 주문을 행 잠금으로 조회(FOR UPDATE)하고 본인 주문·상태를 검증.
  2. step1 조회로 환불 대상(SUCCEEDED APPROVE 결제)을 가져온다.
  3. 그 결제 정보로 CANCEL 결제(REQUESTED)를 `GetOrCreateCancelPaymentService`로 영속화
     (환불 의도). **cancelAmount는 조회한 approve 결제의 amount를 그대로 쓴다**(전액). 금액 출처를
     approve.amount로 고정해 멱등 재호출 시 금액 불일치 예외를 피한다.
  4. `order.cancel()`로 CANCELED 전이.
  5. 재고를 전량 복구(`IncreaseStockService`, productId 정렬 순서 유지).
- CANCEL 생성이 order 잠금 안에서 일어나 동시·중복 취소를 직렬화한다(ADR-L5).
- **UNKNOWN APPROVE 가드**: 해당 주문에 미확정(UNKNOWN/REQUESTED) APPROVE가 떠 있으면 환불을
  진행하지 않는다. PG 상태가 불확실한데 환불을 보내면 안 된다(기존 `existsUnknownByOrderId` 정책과
  정합). 이 경우의 처리(거부/보류)를 명확히 한다.
- **SUCCEEDED APPROVE 부재**: PAID인데 SUCCEEDED APPROVE가 없으면 정합성 오류다(정상 흐름에선
  PAID ⟺ SUCCEEDED APPROVE 존재). 취소를 강행하지 말고 명확한 도메인 예외로 처리한다.
- INIT 취소는 기존 동작(환불 없이 재고만 복구)을 보존한다. INIT·PAID 분기를 명확히 한다.

### 3) 조율 usecase (order.application.usecase, …UseCase, tx 없음)

- 흐름 조립: 조율 service(tx) 호출 → 커밋 후 step1 환불 실행 경로로 best-effort PG 취소 → 결과를
  응답 모델에 반영.
- 응답은 취소 접수(커밋) 기준 반환. happy path는 환불 결과까지, UNKNOWN/실패는 "환불 처리중"으로
  표현(ADR-L3). `OrderCancelResult`에 환불 진행 상태 필드를 추가한다.

### 4) 컨트롤러 라우팅

- `OrderController`의 `POST /orders/{orderId}/cancel`이 조율 usecase를 호출하도록 전환한다(기존
  `CancelOrderService` 직접 호출 → usecase 경유). INIT 동작은 보존.

## 동시성·예외 주의

- order는 FOR UPDATE로 직렬화한다. 같은 tx에서 잠그지 않는 stock·payment row의 낙관 락(@Version)
  충돌은 tx 경계 밖에서 전파/처리하는 기존 정책(`docs/optimistic-lock-design.md`)을 따른다 —
  tx 안에서 삼키지 않는다.

## 하지 마라

- PG 호출을 조율 service의 tx 안에 넣지 마라. 이유: 외부 I/O를 tx에 묶으면 ADR-015 위반·tx 장기화.
- 환불 의도(CANCEL REQUESTED) 영속화를 주문 취소와 별도 tx로 분리하지 마라. 이유: 둘 사이 중단 시
  환불 유실(ADR-L1). 반드시 같은 tx.
- approve 결제를 FAILED로 만들지 마라(ADR-L2).
- 만료 배치(`ExpireOrderService`)·INIT 동작을 바꾸지 마라. 이번 범위는 PAID 추가다.
- 부분취소·포인트 복구를 넣지 마라. 범위 밖이다.

## 관련 파일

- `src/main/java/com/commerce/order/domain/Order.java` (cancel)
- `src/main/java/com/commerce/order/application/service/CancelOrderService.java` (기존 사용자 취소)
- `src/main/java/com/commerce/order/application/usecase/` (조율 usecase 신설 위치)
- `src/main/java/com/commerce/order/presentation/http/OrderController.java`
- `src/main/java/com/commerce/order/application/dto/OrderCancelResult.java`
- step1 환불 실행 경로·`findApprovedByOrderId`, `GetOrCreateCancelPaymentService`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`existsUnknownByOrderId`)
- `src/main/java/com/commerce/stock/application/service/IncreaseStockService.java`

## Acceptance Criteria

```bash
./gradlew test --tests "*CancelPaidOrder*"
./gradlew test --tests "*OrderCancel*"
./gradlew test --tests "*OrderTest*"
```
