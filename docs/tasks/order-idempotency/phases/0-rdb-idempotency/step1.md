# Step 1: idempotency-logic-transition

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/features/order-idempotency/prd.md`
- `docs/features/order-idempotency/architecture.md`
- `docs/features/order-idempotency/adr.md`
- `docs/commit-conventions.md`
- `src/main/java/com/commerce/order/application/OrderCreateService.java`
- `src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`
- `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`
- `src/main/java/com/commerce/order/infrastructure/OrderIdempotencyStatus.java`
- `src/main/java/com/commerce/order/exception/OrderErrorCode.java`
- `src/main/java/com/commerce/order/domain/Order.java`
- `src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`
- `src/test/java/com/commerce/order/application/OrderApplicationServiceTest.java`
- `src/test/java/com/commerce/order/application/OrderApplicationServiceIntegrationTest.java`

루트 문서:
- `docs/ADR.md` — ADR-005 AFTER_COMMIT 패턴 확인

## 작업

주문 생성 멱등성 처리 로직을 Redis 단독에서 Redis(1차) + RDB(최종) 이중 구조로 전환한다.

### 1. `OrderIdempotencyCacheEvent.java` 신규 추가

패키지: `com.commerce.order.application.event`

AFTER_COMMIT 이벤트 클래스. 필드: `memberId`, `idempotencyKey`, `orderId`, `ttl(Duration)`.

### 2. `OrderCreateProcessor.java` 신규 추가

패키지: `com.commerce.order.application`

`@Component` + `@Transactional(readOnly = true)` 클래스.
`execute(OrderCreateCommand command)` 메서드에 `@Transactional` 적용.

현재 `OrderCreateService.createOrderWithPessimisticLockOrdered()` 로직을 이동한다.
성공 시 `ApplicationEventPublisher`로 `OrderIdempotencyCacheEvent`를 발행한다.

의존성: `MemberRepository`, `ProductRepository`, `OrderRepository`, `StockInventoryService`, `ApplicationEventPublisher`

### 3. `OrderCreateService.java` 변경

`@Transactional` 제거 (클래스 레벨 `readOnly = true` 유지 가능, 메서드 레벨 `@Transactional` 제거).

변경된 `createOrder()` 흐름:
```
1. reserve() → true → orderCreateProcessor.execute(command) (2번으로)
   reserve() → false
     ├─ getCompletedOrderId() hit → orderRepository.findById() → 기존 주문 반환
     └─ getCompletedOrderId() miss → orderCreateProcessor.execute(command) (2번으로)

2. orderCreateProcessor.execute(command)
   └─ 성공 → OrderCreateResult 반환
   └─ DataIntegrityViolationException
       → orderIdempotencyStore.clear() → orderRepository.findByMemberIdAndIdempotencyKey() → 기존 주문 반환
   └─ RuntimeException
       → orderIdempotencyStore.clear() → 예외 재발생
```

`complete()` 직접 호출 제거. Redis 저장은 AFTER_COMMIT 이벤트 리스너가 담당한다.
`idempotencyTtlSeconds` 설정값은 이벤트 생성 시 사용한다.

### 4. `RedisOrderIdempotencyStore.java` 변경

**Redis 장애 fallback 추가**:

`reserve()`:
```java
try {
    return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(...));
} catch (RedisException e) {
    log.warn("Redis reserve 실패, DB fallback으로 전환: {}", e.getMessage());
    return false;
}
```

`getCompletedOrderId()`:
```java
try {
    // 기존 로직
} catch (RedisException e) {
    log.warn("Redis 조회 실패, DB fallback으로 전환: {}", e.getMessage());
    return Optional.empty();
}
```

**AFTER_COMMIT 이벤트 리스너 추가**:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(OrderIdempotencyCacheEvent event) {
    complete(event.getMemberId(), event.getIdempotencyKey(), event.getOrderId(), event.getTtl());
}
```

### 5. `OrderErrorCode.java` 변경

`ORDER_IDEMPOTENCY_IN_PROGRESS` 제거. 이 에러 코드가 발생하는 경로가 더 이상 없다.

### 6. 테스트 변경

**`OrderCreateServiceIdempotencyTest.java`**:
- `@DynamicPropertySource` Redis 설정 유지 (Redis 보조 캐시 역할 유지)
- `orderIdempotencyStore` 직접 주입 제거 (reserve() 더 이상 직접 호출 불필요)
- 기존 테스트 유지:
  - `createOrder_whenSameIdempotencyKey_returnSameOrder` → Redis COMPLETED hit 경로
- 기존 테스트 제거:
  - `createOrder_whenIdempotencyProcessing_throwException` → ORDER_IDEMPOTENCY_IN_PROGRESS 경로가 없어졌으므로 제거
- 신규 테스트 추가:
  - TTL 만료 후 재요청: Redis TTL을 1ms로 설정 후 만료 대기 → 재요청 → unique 위반 → 기존 주문 반환
  - 동시 요청: `CyclicBarrier` + `ExecutorService`로 동시 요청 → 하나는 unique 위반 → 두 요청 모두 같은 orderId 반환

**`OrderApplicationServiceTest.java`**:
- `stubForIdempotencyReserved()` 제거
- `createOrder_*` 테스트에서 `orderIdempotencyStore.reserve()` stub 제거
- `orderIdempotencyStore.complete()` 직접 호출 검증 제거 (이벤트 발행으로 변경)
- `ApplicationEventPublisher` mock 주입 추가 (필요 시)

**`OrderApplicationServiceIntegrationTest.java`**:
- 새 흐름(Redis MISS → INSERT 시도)에 맞게 수정

## 수정 가능 경로

- `src/main/java/com/commerce/order/application/**`
- `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`
- `src/main/java/com/commerce/order/infrastructure/OrderIdempotencyStatus.java`
- `src/main/java/com/commerce/order/exception/OrderErrorCode.java`
- `src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`
- `src/test/java/com/commerce/order/application/OrderApplicationServiceTest.java`
- `src/test/java/com/commerce/order/application/OrderApplicationServiceIntegrationTest.java`
- `docs/features/order-idempotency/**`

## Acceptance Criteria

```bash
./gradlew test && ./gradlew dockerTest
```

단위 테스트와 Docker 기반 통합 테스트(Testcontainers MySQL + Redis)를 모두 통과해야 한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. `ORDER_IDEMPOTENCY_IN_PROGRESS` 참조가 완전히 제거됐는지 확인한다:
   ```bash
   grep -rn "ORDER_IDEMPOTENCY_IN_PROGRESS" src/
   ```
3. `complete()` 직접 호출이 `OrderCreateService`에서 제거됐는지 확인한다:
   ```bash
   grep -n "complete(" src/main/java/com/commerce/order/application/OrderCreateService.java
   ```
4. architecture.md 디렉토리 구조를 따르는가?
5. ADR 기술 스택을 벗어나지 않았는가?
6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `OrderIdempotencyStore` Port 인터페이스(`reserve()`, `clear()`, `getCompletedOrderId()`, `complete()`)를 변경하지 마라. 이유: Port 인터페이스 변경은 이번 step 범위가 아니며 다른 테스트에 영향을 준다.
- `OrderConcurrencyService`를 수정하지 마라. 이유: 동시성 실험용 서비스로 멱등성 처리 대상이 아니다.
- `complete()` 직접 호출을 `@Transactional` 안에 남기지 마라. 이유: RDB rollback 시 Redis에 COMPLETED가 저장되는 원자성 문제가 재발한다.
- 기존 테스트를 깨뜨리지 마라.
