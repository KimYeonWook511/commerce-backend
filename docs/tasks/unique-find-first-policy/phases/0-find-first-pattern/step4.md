# Step 5: order-create-find-first

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/src/main/java/com/commerce/order/application/OrderCreateService.java`
- `/src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `/src/main/java/com/commerce/order/application/port/OrderIdempotencyStore.java`
- `/src/main/java/com/commerce/order/domain/repository/OrderRepository.java`
- `/src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java`

step 2 가 끝나 있어야 한다.

## 작업

`OrderCreateService.attemptCreateOrder` 를 재설계한다. `DuplicateKeyException` catch 를 제거하고, Redis reserve 성공 후 DB `findByMemberIdAndIdempotencyKey` 사전 체크를 추가한다. TTL 만료 후 정당한 멱등 재요청을 흡수하기 위함이다. race 와 ULID 충돌은 모두 안전망 500 으로 도달한다.

### 1. `OrderCreateService.attemptCreateOrder` 재설계

기존 구조 (라인 59-80):

```java
private OrderCreateResult attemptCreateOrder(...) {
    try {
        return orderCreateProcessor.execute(command, ttl);
    } catch (DuplicateKeyException ex) {
        return orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
            .map(order -> {
                orderIdempotencyStore.complete(memberId, idempotencyKey, order.getId(), ttl);
                return OrderCreateResult.from(order);
            })
            .orElseGet(() -> {
                log.error("멱등키 충돌이 아닌 unique 제약 위반 발생. ...", ex);
                throw ex;
            });
    } catch (RuntimeException ex) {
        orderIdempotencyStore.clear(memberId, idempotencyKey);
        throw ex;
    }
}
```

새 구조:

```java
private OrderCreateResult attemptCreateOrder(
    OrderCreateCommand command, Long memberId, String idempotencyKey, Duration ttl
) {
    // Redis 만료 후 정당한 재요청을 멱등 흡수하기 위한 DB 사전 체크.
    // race 충돌(Redis 통과 + DB find empty + insert 시 unique 위반) 과
    // ULID 충돌은 RuntimeException catch 가 Redis 정리 후 rethrow → 안전망 500.
    Optional<OrderCreateResult> existing = orderRepository
        .findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
        .map(order -> {
            // 이후 동일 키 재요청이 Redis에서 바로 처리되도록 complete 상태로 갱신한다.
            orderIdempotencyStore.complete(memberId, idempotencyKey, order.getId(), ttl);
            return OrderCreateResult.from(order);
        });
    if (existing.isPresent()) {
        return existing.get();
    }

    try {
        return orderCreateProcessor.execute(command, ttl);
    } catch (RuntimeException ex) {
        orderIdempotencyStore.clear(memberId, idempotencyKey);
        throw ex;
    }
}
```

- `import org.springframework.dao.DuplicateKeyException;` 임포트 제거.
- `import java.util.Optional;` 임포트 추가 (필요 시).
- `RuntimeException` catch 는 그대로 유지 — Redis 멱등키 release 를 위한 정리 로직이며, race / ULID 충돌 시 안전망 500 도달 경로가 된다.
- 기존 catch 내부의 "Redis complete 상태 갱신" 로직은 사전 find 분기로 그대로 옮겨진다.

### 2. 단위 테스트 갱신 (`OrderCreateServiceIdempotencyTest.java`)

- 라인 77-100 의 `DuplicateKeyException` catch 후 fallback 재조회 케이스를 제거한다.
- 다음 시나리오로 케이스를 교체한다:
  - Redis reserve 실패 + Redis completed orderId 존재 → 기존 order 반환 (기존 케이스 유지)
  - Redis reserve 성공 + DB `findByMemberIdAndIdempotencyKey` empty → `orderCreateProcessor.execute` 호출되어 신규 order 반환
  - Redis reserve 성공 + DB `findByMemberIdAndIdempotencyKey` present → Redis `complete` 호출 후 기존 order 반환 (TTL 만료 후 정당 재요청 케이스)
- `orderCreateProcessor.execute` 가 `RuntimeException` 을 던지는 케이스: `orderIdempotencyStore.clear` 호출 후 예외 rethrow 검증 (기존 케이스 유지)
- "fallback 실패 시 rethrow" 검증 케이스는 제거 (catch 자체가 없음)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `OrderCreateService` 에 `org.springframework.dao.DuplicateKeyException` 임포트가 없는가?
   - Redis reserve 성공 후 DB find 사전 체크가 추가되었고 정상 멱등(present) 분기가 동작하는가?
   - `RuntimeException` catch 가 그대로 유지되어 Redis 정리 후 rethrow 하는가?
   - 단위 테스트가 새 시나리오로 갱신되어 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `RuntimeException` catch 의 `orderIdempotencyStore.clear` 호출을 제거하지 마라. 이유: race 시 멱등키가 Redis 에 남아있으면 후속 재요청이 영구히 실패한다. 정리 로직은 필수.
- `OrderCreateProcessor` 를 수정하지 마라. 이유: 본 step 범위는 `OrderCreateService.attemptCreateOrder` 의 catch 정리뿐. processor 내부 `orderRepository.save(order)` 는 그대로 둔다.
- Redis 사전 체크(`orderIdempotencyStore.reserve` / `getCompletedOrderId`) 분기를 변경하지 마라. 이유: 본 정책의 캐시 레이어 역할이며 기존 흐름을 보존한다.
- 두 unique 제약(`(member_id, idempotency_key)` vs `orderNumber` ULID) 을 구분하는 로직을 추가하지 마라. 이유: 새 정책에서 둘 다 안전망 500 으로 통합 처리된다.
- 기존 테스트를 깨뜨리지 마라.
