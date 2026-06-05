# Step 1: simplify-idempotency-cache

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 설계 의도와 결정 근거를 파악하라:

- `/docs/tasks/order-idempotency-cache-simplification/prd.md`
- `/docs/tasks/order-idempotency-cache-simplification/architecture.md`
- `/docs/tasks/order-idempotency-cache-simplification/adr.md`
- `/docs/tasks/order-idempotency-cache-simplification/api-spec.md`
- `/docs/tasks/order-idempotency-cache-simplification/db-schema.md`

기존 결정 맥락 (의미 비교용):

- `/docs/tasks/order-idempotency/adr.md` (기존 ADR-001/002)
- `/docs/adr.md` ADR-005 (Redis 호출 commit 이후)
- `/docs/adr.md` ADR-011 (find-first 패턴)

수정 대상 코드 (현재 상태 파악):

- `/src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`
- `/src/main/java/com/commerce/order/application/OrderCreateService.java`
- `/src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `/src/main/java/com/commerce/order/application/event/OrderIdempotencyCacheEvent.java`
- `/src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`
- `/src/main/java/com/commerce/order/infrastructure/OrderIdempotencyStatus.java`
- `/src/main/java/com/commerce/order/exception/OrderErrorCode.java`
- `/src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`
- `/src/test/java/com/commerce/order/application/OrderCreateProcessorTest.java`
- `/src/test/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStoreTest.java`
- `/src/main/resources/application*.yml` (TTL 설정 위치)

## 작업

본 step 은 멱등성 캐시 책임을 *in-flight 차단 전용* 으로 좁히는 *사용자 기능 단위* 변경이다. 인터페이스 변경, 구현 변경, Service 분기 재구성, 테스트 갱신을 한 step 안에서 자기완결적으로 처리한다.

### 1. `OrderIdempotencyStore` (port) 인터페이스 단순화

`src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`

다음 시그니처만 남긴다:

```java
public interface OrderIdempotencyStore {
    boolean reserve(Long memberId, String idempotencyKey, Duration ttl);
    void clear(Long memberId, String idempotencyKey);
}
```

- `getCompletedOrderId(...)` 제거
- `complete(...)` 제거

### 2. `OrderIdempotencyStatus` enum 파일 삭제

`src/main/java/com/commerce/order/infrastructure/OrderIdempotencyStatus.java` 파일을 삭제한다. 사용처 grep 으로 확인 후 잔존 import 정리.

### 3. `OrderIdempotencyCacheEvent` 클래스 파일 삭제

`src/main/java/com/commerce/order/application/event/OrderIdempotencyCacheEvent.java` 파일을 삭제한다. 사용처 grep 으로 확인 후 잔존 import 정리.

빈 디렉토리가 되면 `src/main/java/com/commerce/order/application/event/` 디렉토리도 함께 삭제한다.

### 4. `RedisOrderIdempotencyStore` 변경

`src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`

제거할 메서드:

- `getCompletedOrderId(Long, String)`
- `complete(Long, String, Long, Duration)`
- `handle(OrderIdempotencyCacheEvent)` — `@TransactionalEventListener` listener
- `pushTraceIdIfMissing(String)` — listener 부속 private 메서드

`reserve` 가 박는 값을 단순 marker 문자열로 변경한다 (예: `"1"`). `OrderIdempotencyStatus.PROCESSING.value()` 호출 대신 상수 marker 사용.

`clear` 는 그대로 유지 (DELETE 호출, DataAccessException catch + warn log).

불필요해진 import 정리:

- `OrderIdempotencyStatus`
- `OrderIdempotencyCacheEvent`
- `LogContext` (listener 안에서만 쓰던 경우)
- `TransactionalEventListener`, `TransactionPhase`

### 5. `OrderCreateProcessor` 변경

`src/main/java/com/commerce/order/application/OrderCreateProcessor.java`

- `applicationEventPublisher.publishEvent(new OrderIdempotencyCacheEvent(...))` 호출 제거
- `LogContext.getTraceId()` 호출 (event 동봉용) 제거
- `execute(OrderCreateCommand command, Duration ttl)` 시그니처에서 `ttl` 인자 제거. `execute(OrderCreateCommand command)` 로 변경. 이유: event 발행이 없어지므로 ttl 이 processor 책임에서 사라짐.
- `applicationEventPublisher` 의존성 — 클래스 내 다른 publish 호출이 없으면 의존 제거. grep 으로 확인 후 결정.

### 6. `OrderCreateService` 분기 재구성

`src/main/java/com/commerce/order/application/OrderCreateService.java`

`attemptCreateOrder` private 메서드를 흡수하고 `createOrder` 본문을 다음과 같이 재구성한다:

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public OrderCreateResult createOrder(OrderCreateCommand command) {
    if (!StringUtils.hasText(command.getIdempotencyKey())) {
        throw new CommonException(CommonErrorCode.INVALID_REQUEST);
    }

    Long memberId = command.getMemberId();
    String idempotencyKey = command.getIdempotencyKey();
    Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);

    boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);
    if (!reserved) {
        throw new OrderException(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
    }

    try {
        Optional<Order> existing = orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("주문 멱등 응답 orderId={} memberId={} source=db idempotencyKey={}",
                existing.get().getId(), memberId, idempotencyKey);
            return OrderCreateResult.from(existing.get());
        }
        return orderCreateProcessor.execute(command);
    } finally {
        orderIdempotencyStore.clear(memberId, idempotencyKey);
    }
}
```

핵심:

- 사전 find 가 `reserve` 뒤, `processor.execute` 앞에 위치
- finally 가 정상/실패 무관 `clear` 호출
- 기존 `getCompletedOrderId` hit 분기 (Redis source) 제거 — 캐시가 결과를 가지지 않음

기존 로그 메시지의 `source=redis` 분기는 제거. `source=db` 만 남는다.

### 7. `OrderErrorCode` 에 `ORDER_IDEMPOTENCY_IN_PROGRESS` 추가

`src/main/java/com/commerce/order/exception/OrderErrorCode.java`

```java
ORDER_IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "ORDER-409-2", "주문 생성이 이미 처리 중입니다. 잠시 후 다시 시도해주세요.")
```

(에러 코드 명명 규칙 — 기존 `ORDER-409-1` 같은 패턴 확인 후 다음 번호 사용)

### 8. TTL 설정값 변경

다음 설정 파일들에서 `order.idempotency.ttl-seconds` 값을 `60` 으로 변경:

- `src/main/resources/application.yml`
- `src/main/resources/application-local.yml` (있다면)
- `src/main/resources/application-prod.yml` (있다면)
- 테스트용 `application.yml` (있다면, 별도 값으로 둘 수 있음)

`OrderCreateService` 의 `@Value("${order.idempotency.ttl-seconds:600}")` 기본값도 `60` 으로 변경.

### 9. 테스트 갱신

**`OrderCreateServiceIdempotencyTest`** (`src/test/java/com/commerce/order/application/`):

제거할 시나리오:

- COMPLETED Redis hit (캐시에서 orderId 가져와 `findById` 로 반환)
- `getCompletedOrderId` 관련 분기

추가할 시나리오:

- `reserve` false → `OrderException(ORDER_IDEMPOTENCY_IN_PROGRESS)` throw, `processor.execute` 미호출 검증
- `reserve` true → 사전 find 발견 → `processor.execute` 미호출, DB 결과 반환, finally `clear` 호출 검증
- `reserve` true → 사전 find empty → `processor.execute` 호출, 정상 반환 후 finally `clear` 호출 검증
- `reserve` true → `processor.execute` throws → finally `clear` 호출, 예외 rethrow 검증

기존 race window 시나리오 (`processor.execute` 후 unique 위반) 의 단언은 *안전망 500 또는 새 흐름에서 일관된 응답* 으로 정리 (race 자체가 새 흐름에서 거의 발생 안 함).

**`RedisOrderIdempotencyStoreTest`** (`src/test/java/com/commerce/order/infrastructure/`):

제거할 테스트:

- `getCompletedOrderId` 관련
- `complete` 관련
- `handle` listener 관련 (event publish → AFTER_COMMIT 트리거 후 Redis 갱신 검증)

남기는 테스트:

- `reserve` SETNX 동작 (첫 호출 true, 같은 키 두 번째 호출 false)
- `reserve` TTL 적용 검증
- `clear` DELETE 동작
- `DataAccessException` 발생 시 `reserve` false / `clear` warn 만 (예외 전파 없음)

**`OrderCreateProcessorTest`** (`src/test/java/com/commerce/order/application/`):

- `applicationEventPublisher.publishEvent(...)` 검증 mock 제거
- `execute(command, ttl)` → `execute(command)` 시그니처 변경 반영

**신규: `OrderCreateConcurrencyIntegrationTest`** (`src/test/java/com/commerce/order/application/`):

`@Tag("docker")` + Testcontainers (Redis + MySQL) 기반 동시성 통합 테스트.

시나리오: 같은 `memberId` + `idempotencyKey` 로 두 스레드가 동시에 `createOrder` 호출 → 한쪽은 200 (정상 생성), 다른 한쪽은 `OrderException(ORDER_IDEMPOTENCY_IN_PROGRESS)` throw 검증.

검증 방식:

- `CountDownLatch` 로 두 스레드 동시 시작
- 결과: 정상 응답 1건, 예외 1건 (역할 보장 아님, *둘 중 하나만 성공* 패턴)
- DB 에 해당 idempotencyKey 의 order 가 정확히 1건 존재

## Acceptance Criteria

```bash
./gradlew test
./gradlew dockerTest
```

두 명령이 모두 통과한다. 동시성 통합 테스트가 *같은 idempotencyKey 로 동시 두 요청 시 한쪽 200, 다른 한쪽 409 ORDER_IDEMPOTENCY_IN_PROGRESS* 를 검증한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.

2. 잔존 참조 grep 검증:

   ```bash
   rg "OrderIdempotencyCacheEvent" src/main/ src/test/
   rg "OrderIdempotencyStatus" src/main/ src/test/
   rg "getCompletedOrderId" src/main/ src/test/
   ```

   세 명령 모두 결과를 반환하지 않아야 한다.

3. 신규 에러 코드 위치 확인:

   ```bash
   rg "ORDER_IDEMPOTENCY_IN_PROGRESS" src/main/
   ```

   `OrderErrorCode.java` 와 `OrderCreateService.java` 두 곳에서 결과 반환.

4. listener 부재 확인:

   ```bash
   rg "TransactionalEventListener" src/main/java/com/commerce/order/
   ```

   결과 없음.

5. 다음을 확인한다:
   - `OrderCreateService.createOrder` 가 `try-finally` 안에서 `clear` 호출하는가
   - `OrderCreateProcessor.execute(command)` 가 ttl 인자 없이 호출되는가
   - PROCESSING TTL 이 60 인가
   - 회고 문서를 만지지 않았는가

6. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `OrderIdempotencyStatus` enum 을 보존하지 마라. 이유: 모든 사용처가 사라져 *unused* 상태가 됨. CLAUDE.md "불필요한 추상화 피한다" 위반.
- `OrderIdempotencyCacheEvent` 클래스를 보존하지 마라. 이유: 발행처·구독처 모두 사라지므로 *unused*.
- `applicationEventPublisher` 의존성을 다른 사용처 확인 없이 일괄 제거하지 마라. 이유: 같은 클래스에 다른 publish 가 있으면 컴파일 에러. grep 확인 후 결정.
- `clear()` 호출을 `processor.execute()` 안에서 하지 마라. 이유: `processor.execute()` 는 `@Transactional` 이라 commit 전 호출됨. ADR-005 *Redis 호출은 commit 이후* 원칙 위반.
- `attemptCreateOrder` 의 기존 try-catch (RuntimeException) 패턴을 try-finally 로 *단순 변환만* 하지 마라. 이유: 새 흐름은 사전 find 가 try 안으로 들어오고, catch → clear → rethrow 가 finally 단일 clear 로 *재구성됨*. 분기 자체 변경이므로 흐름 다이어그램을 보고 작성.
- TTL 값을 코드에 하드코딩하지 마라. 이유: 기존 `@Value("${order.idempotency.ttl-seconds:600}")` 패턴 유지. 값만 60 으로 변경.
- 회고 문서 (`docs/tasks/order-idempotency/retrospective.md` 등) 를 수정하지 마라. 이유: 회고는 시점 기록이며 사후 수정 금지 (사용자 메모리 규칙).
- 루트 docs (`docs/adr.md`, `docs/api-spec.md`, `docs/architecture.md` 등) 를 수정하지 마라. 이유: step 2 의 책임.
- `attemptCreateOrder` 의 기존 race window 단언을 유지하지 마라. 이유: 새 흐름은 race 가 reserve 단계에서 차단됨. 기존 안전망 500 단언은 새 흐름과 맞지 않음.
- 기존 테스트를 깨뜨리지 마라.
