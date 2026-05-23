# Step 1: order-domain-logging

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 기존 패턴을 파악하라:

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/tasks/core-domain-logging/adr.md`
- `docs/logging-conventions.md` (§2 레벨, §3 레이어, §7 메시지 패턴)
- `src/main/java/com/commerce/order/application/OrderCreateService.java` — 멱등 흡수 2분기 구조
- `src/main/java/com/commerce/order/application/OrderCreateProcessor.java` — 신규 주문 생성 진입점
- `src/main/java/com/commerce/order/application/OrderCancelService.java`
- `src/main/java/com/commerce/order/application/OrderConcurrencyService.java` — 공통 헬퍼 + pessimistic-batch
- `src/main/java/com/commerce/order/application/OrderExpirationService.java`
- `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalAttemptService.java` — 이미 `@Slf4j` 적용된 메시지 톤 참고

## 작업

### 1. `OrderCreateService` — 멱등 흡수 INFO

파일: `src/main/java/com/commerce/order/application/OrderCreateService.java`

- 클래스 상단에 `@Slf4j` 부착
- `createOrder()` 안에 두 멱등 흡수 분기 존재:
  - `orderIdempotencyStore.getCompletedOrderId(...).map(...)` 안 — Redis hit 분기
  - `attemptCreateOrder()` 내부 `existing.isPresent()` 분기 — DB existing hit
- 두 분기 모두 `OrderCreateResult` 반환 직전에 동일 메시지:
  ```java
  log.info("주문 멱등 응답 orderId={} memberId={}", order.getId(), memberId);
  ```
- 신규 생성 경로(`orderCreateProcessor.execute(...)`)에는 로그를 추가하지 않는다 (그쪽은 Processor에서 처리)

### 2. `OrderCreateProcessor` — 신규 생성 INFO

파일: `src/main/java/com/commerce/order/application/OrderCreateProcessor.java`

- 클래스 상단에 `@Slf4j` 부착
- `createOrderWithStockDecrease()`의 `orderRepository.save(order)` 직후, `applicationEventPublisher.publishEvent(...)` 전 또는 후에:
  ```java
  log.info("주문 생성 orderId={} memberId={} itemCount={}",
      order.getId(), command.getMemberId(), command.getItems().size());
  ```
- `itemCount`는 `command.getItems().size()` 사용 (병합 전 원본 수량). 영속화된 `order.getOrderItems().size()`도 가능하나 lazy loading 위험이 있어 command 기반 권장.

### 3. `OrderCancelService` — 주문 취소 INFO

파일: `src/main/java/com/commerce/order/application/OrderCancelService.java`

- 클래스 상단에 `@Slf4j` 부착
- `cancelOrder()`의 `return OrderCancelResult.from(order)` 직전:
  ```java
  log.info("주문 취소 orderId={} memberId={} itemCount={}",
      order.getId(), memberId, sortedItems.size());
  ```

### 4. `OrderConcurrencyService` — 동시성 전략 통일 INFO

파일: `src/main/java/com/commerce/order/application/OrderConcurrencyService.java`

- 클래스 상단에 `@Slf4j` 부착
- 공통 헬퍼 `createOrderWithStockDecrease(OrderCreateCommand command, BiConsumer<Long, Integer> stockDecrease)` 시그니처를 다음 중 하나로 확장:
  - **권장**: `createOrderWithStockDecrease(OrderCreateCommand command, BiConsumer<Long, Integer> stockDecrease, String strategy)`로 strategy 매개변수 추가
  - 또는 8개 진입 메서드 각각이 헬퍼 호출 후 자체적으로 `log.info(...)` 호출
- 권장안 적용 시:
  - 헬퍼의 `orderRepository.save(order)` 직후:
    ```java
    log.info("주문 생성 orderId={} memberId={} itemCount={} strategy={}",
        order.getId(), command.getMemberId(), command.getItems().size(), strategy);
    ```
  - 8개 진입 메서드는 헬퍼 호출 시 strategy 라벨 전달:
    | 메서드 | strategy |
    |---|---|
    | `createOrderWithoutLock` | `"without-lock"` |
    | `createOrderWithSynchronized` | `"synchronized"` |
    | `createOrderWithSynchronizedAndTransaction` | `"synchronized-tx"` |
    | `createOrderWithReentrantLockAndTransaction` | `"reentrant-tx"` |
    | `createOrderWithOptimisticLock` | `"optimistic"` |
    | `createOrderWithPessimisticLock` | `"pessimistic"` |
    | `createOrderWithPessimisticLockOrdered` | `"pessimistic-ordered"` |
- `createOrderWithPessimisticLockBatch`는 별도 경로이므로 메서드 내부 `orderRepository.save(order)` 직후 직접:
  ```java
  log.info("주문 생성 orderId={} memberId={} itemCount={} strategy=pessimistic-batch",
      order.getId(), command.getMemberId(), command.getItems().size());
  ```

### 5. `OrderExpirationService` — 주문 만료 INFO

파일: `src/main/java/com/commerce/order/application/OrderExpirationService.java`

- 클래스 상단에 `@Slf4j` 부착
- `expireOrder()`의 `outboxService.createStockRestoreOutboxEvent(...)` 호출 후:
  ```java
  log.info("주문 만료 orderId={} itemCount={}", order.getId(), order.getOrderItems().size());
  ```

### 6. `StockRestoreOutboxCreateService` — Outbox 발행 INFO

파일: `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java`

- 클래스 상단에 `@Slf4j` 부착
- `createOutboxEvent()`의 `outboxEventRepository.save(outboxEvent)` 직후:
  ```java
  log.info("재고 복구 Outbox 발행 orderId={} itemCount={}",
      command.getOrderId(), command.getItems().size());
  ```

## 수정 가능 경로

- `src/main/java/com/commerce/order/application/OrderCreateService.java`
- `src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `src/main/java/com/commerce/order/application/OrderCancelService.java`
- `src/main/java/com/commerce/order/application/OrderConcurrencyService.java`
- `src/main/java/com/commerce/order/application/OrderExpirationService.java`
- `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 실행 → 기존 테스트 모두 PASS
2. 6개 파일에 `@Slf4j` 부착 확인
3. INFO 로그 메시지가 사전 시그니처와 정확히 일치 (메시지 본문, 필드 순서, 식별자명)
4. `OrderConcurrencyService` 8개 strategy 라벨이 사전 합의 목록과 정확히 일치
5. `OrderCreateService` 멱등 흡수 2분기 모두 `주문 멱등 응답` 로그가 들어갔는지 확인
6. 비즈니스 로직·시그니처 변경 없음 확인 (단, `OrderConcurrencyService` 공통 헬퍼 시그니처에 strategy 매개변수 추가는 허용)
7. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/logging-conventions.md` 수정 금지. 이유: 단일 진실의 원천. P0(#127)에서 합의된 정책 변경 권한 없음.
- 단순 조회/위임 서비스(`OrderQueryService`, `OutboxService`)에 `@Slf4j` 부착 금지. 이유: dead code 발생 + 컨벤션 §3 정합성 위반.
- `log.error()`/`log.warn()` 추가 금지. 이유: 본 step 범위는 INFO 도메인 이벤트만. 보상 catch는 본 작업에서 신설하지 않음.
- 비즈니스 로직 변경 금지. 이유: 본 작업은 로그 추가만. 기존 동작이 바뀌면 추가 검증 필요.
- 신규 테스트 추가 금지. 이유: 로그 메시지는 별도 테스트로 검증하지 않음. 기존 테스트가 깨지지 않으면 통과.
- 기존 테스트를 깨뜨리지 마라.
