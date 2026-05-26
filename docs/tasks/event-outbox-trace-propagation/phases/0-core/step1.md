# Step 1: transactional-event-trace

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/tasks/event-outbox-trace-propagation/prd.md`
- `docs/tasks/event-outbox-trace-propagation/architecture.md`
- `docs/tasks/event-outbox-trace-propagation/adr.md`
- `src/main/java/com/commerce/common/log/LogContext.java` — MDC 키와 유효성 검증 API 참조
- `src/main/java/com/commerce/order/application/OrderCreateProcessor.java` — publisher 수정 대상
- `src/main/java/com/commerce/order/application/event/OrderIdempotencyCacheEvent.java` — 이벤트 객체 수정 대상
- `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java` — listener 수정 대상

태스크 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `docs/logging-conventions.md` §8 비동기·이벤트 경계의 traceId 전파

## 작업

`@TransactionalEventListener(AFTER_COMMIT)` 경계에 traceId를 전파한다. 이벤트 객체에 traceId 필드를 추가하고 listener에서 MDC에 복원하는 방식을 채택한다.

### 수정 파일 1: `OrderIdempotencyCacheEvent`

`src/main/java/com/commerce/order/application/event/OrderIdempotencyCacheEvent.java`

- `traceId` 필드 추가 (String, nullable)
- 기존 `@Getter @RequiredArgsConstructor` 패턴 유지. 모든 필드를 생성자 파라미터로 받는다.
- 필드 순서: `memberId`, `idempotencyKey`, `orderId`, `ttl`, `traceId`

### 수정 파일 2: `OrderCreateProcessor`

`src/main/java/com/commerce/order/application/OrderCreateProcessor.java`

- `publishEvent` 호출 부분에서 `LogContext.getTraceId()`를 읽어 이벤트에 동봉
- 호출 시점: `applicationEventPublisher.publishEvent(new OrderIdempotencyCacheEvent(memberId, key, orderId, ttl, LogContext.getTraceId()))`
- 기존 로직, 로그, 주석은 변경하지 않는다.

### 수정 파일 3: `RedisOrderIdempotencyStore`

`src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`

- `handle(OrderIdempotencyCacheEvent event)` 메서드 진입 시 traceId가 유효하면 MDC에 push
- finally 블록에서 MDC 정리
- 유효성 검증: `LogContext.isValidTraceId(event.getTraceId())`
- 정리는 finally에서 항상 수행. 다만 push하지 않은 경우 remove도 안전하게 호출 가능(MDC.remove는 idempotent)
- 기존 try-catch 구조와 로그는 유지. 다만 try-finally 바깥 try-catch에 영향이 가지 않도록 try-finally를 내부에 중첩하거나 기존 try 블록 시작 직전에 MDC put, 기존 try-catch 종료 직후 finally를 추가하는 방식 중 가독성이 좋은 쪽을 선택한다.
- 기존 주석(`// RDB 커밋 이후에만 ...`) 유지

권장 구조:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(OrderIdempotencyCacheEvent event) {
    boolean traceIdPushed = pushTraceIdIfValid(event.getTraceId());
    try {
        try {
            complete(event.getMemberId(), event.getIdempotencyKey(), event.getOrderId(), event.getTtl());
        } catch (DataAccessException e) {
            log.warn("Redis AFTER_COMMIT 캐싱 실패 (무시): {}", e.getMessage());
        }
    } finally {
        if (traceIdPushed) {
            LogContext.removeTraceId();
        }
    }
}

private boolean pushTraceIdIfValid(String traceId) {
    if (LogContext.isValidTraceId(traceId)) {
        LogContext.putTraceId(traceId);
        return true;
    }
    return false;
}
```

### 신규 또는 보강 테스트

다음 두 가지 시나리오를 검증하는 테스트를 작성하거나 기존 테스트를 보강한다.

- `OrderCreateProcessor`가 현재 MDC traceId를 이벤트에 동봉하는가
  - 단위 테스트로 충분. MDC에 traceId를 set한 뒤 `execute()` 호출, 발행된 이벤트 객체의 traceId 확인.
- `RedisOrderIdempotencyStore.handle()`이 traceId를 MDC에 복원하고 종료 시 정리하는가
  - 단위 테스트 또는 통합 테스트. 이벤트의 traceId를 임의 값으로 지정 후 handle 호출, 호출 중 MDC 값 확인은 `complete()` 호출을 spy/verify하여 그 시점에 MDC 값을 캡처하는 방식 가능.
  - 또는 통합 테스트(`@SpringBootTest` + Testcontainers)에서 HTTP 요청 → 이벤트 발행 → AFTER_COMMIT listener까지 traceId가 동일하게 흐르는지 확인.
- traceId가 null이거나 유효하지 않을 때 MDC에 push되지 않고, finally에서 remove도 호출하지 않는지 확인.

테스트 위치는 기존 테스트 파일을 따른다.

- `src/test/java/com/commerce/order/application/OrderCreateProcessorTest.java`
- `src/test/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStoreTest.java` (없으면 신규 생성)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 아래를 확인한다.
   - `OrderIdempotencyCacheEvent`에 `traceId` 필드가 추가되었는가
   - `OrderCreateProcessor`가 `LogContext.getTraceId()`를 이벤트에 전달하는가
   - `RedisOrderIdempotencyStore.handle()`이 유효 traceId만 MDC에 push하는가
   - `RedisOrderIdempotencyStore.handle()`이 finally에서 MDC를 정리하는가
   - 기존 테스트가 모두 통과하는가
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `MDC.clear()` 사용 금지. 이유: 다른 MDC 키(memberId 등)를 함께 제거할 위험이 있다. `LogContext.removeTraceId()`만 사용한다.
- 기존 주석 삭제 금지. 이유: 회귀 위험 표시와 설계 근거가 포함되어 있다.
- `ApplicationEventMulticaster` wrapping 도입 금지. 이유: ADR 결정 1에 따라 본 태스크에서는 이벤트 객체 방식으로 통일한다.
- `OrderCreateProcessor`의 트랜잭션 범위 변경 금지. 이유: 기존 트랜잭션 정책을 건드리는 변경은 본 태스크 범위 밖이다.
- 기존 테스트를 깨뜨리지 마라.
