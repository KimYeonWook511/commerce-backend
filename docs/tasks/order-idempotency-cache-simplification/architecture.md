# 태스크 아키텍처

## 개요

주문 멱등성 캐시의 책임을 *in-flight 차단 전용* 으로 좁힌다. 기존에는 Redis 가 `PROCESSING` / `COMPLETED` 두 상태를 통해 결과 캐싱까지 담당했고, `AFTER_COMMIT` listener 가 commit 후 마커를 `COMPLETED` 로 전이시키는 구조였다. 본 태스크는:

- Redis 마커를 *in-flight 표시* 한 가지로 단순화한다 (`PROCESSING` 의미만).
- `COMPLETED` 캐싱과 `FAILED` 캐싱을 제거한다. 결과의 진실은 DB unique 제약과 `findByMemberIdAndIdempotencyKey` 가 갖는다.
- `Service` 가 `try-finally` 로 마커 정리를 직접 수행한다. listener / event 클래스를 제거한다.
- 같은 키 동시 요청 시 안전망 500 대신 명시적 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답을 반환한다.

## 변경 대상

| 레이어 | 변경 |
| --- | --- |
| Application — port | `OrderIdempotencyStore`: 4 → 2 메서드 (`reserve`, `clear`) |
| Application — event | `OrderIdempotencyCacheEvent` 클래스 제거 |
| Application — service | `OrderCreateService.createOrder` 분기 재구성, `attemptCreateOrder` 흡수 |
| Application — service | `OrderCreateProcessor.execute(command)` — event publish 제거. ttl 인자 제거 |
| Application — exception | `OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS` (HTTP 409) 추가 |
| Infrastructure | `RedisOrderIdempotencyStore`: listener `handle`, `complete`, `getCompletedOrderId`, `pushTraceIdIfMissing` 제거 |
| Infrastructure | `OrderIdempotencyStatus` enum 제거 |
| Configuration | `order.idempotency.ttl-seconds: 600` → `60` |
| Test | `OrderCreateServiceIdempotencyTest` 시나리오 갱신, `RedisOrderIdempotencyStoreTest` 축소, 동시성 통합 테스트 신규 |

## 설계 방향

### 캐시의 책임 단일화

`OrderIdempotencyStore` 의 인터페이스:

```java
public interface OrderIdempotencyStore {
    boolean reserve(Long memberId, String idempotencyKey, Duration ttl);
    void clear(Long memberId, String idempotencyKey);
}
```

- `reserve` — Redis `SETNX` 로 marker (단순 문자열 `"1"`) 박기. 성공 시 true, 이미 존재 시 false.
- `clear` — Redis `DELETE` 로 marker 회수.

`OrderIdempotencyStatus` enum 은 marker 가 한 종류뿐이므로 제거. Redis 에 박히는 값 자체에 의미를 두지 않는다.

### Service 흐름

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public OrderCreateResult createOrder(OrderCreateCommand command) {
    // validation ...

    boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);
    if (!reserved) {
        throw new OrderException(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
    }

    try {
        Optional<Order> existing = orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
        if (existing.isPresent()) {
            return OrderCreateResult.from(existing.get());
        }
        return orderCreateProcessor.execute(command);
    } finally {
        orderIdempotencyStore.clear(memberId, idempotencyKey);
    }
}
```

핵심 포인트:

- **사전 find 가 reserve 뒤에 위치** — 캐시 hit (reserve false) 시 DB 까지 안 가도 됨 (캐시의 DB 도달 전 차단 가치 유지).
- **finally clear** — 정상/실패 무관 마커 정리. `OrderCreateService` 가 `NOT_SUPPORTED` 이므로 finally 가 *commit 이후* 호출됨 (ADR-005 정합).
- **race window 한정 일관성** — INSERT race 가 발생해도 reserve 가 이미 차단했으므로 도달하지 않음. 도달하는 경우는 Redis 일시 장애로 reserve fallback false 가 된 *드문* 경우뿐.

### listener / event 제거 근거

기존 코드는 `OrderCreateProcessor` 가 `applicationEventPublisher.publishEvent(OrderIdempotencyCacheEvent)` 를 발행하고, `RedisOrderIdempotencyStore.handle()` 이 `@TransactionalEventListener(AFTER_COMMIT)` 으로 받아 `complete()` 를 호출하는 구조였다.

새 구조에서는 *clear* 만 호출하면 되고, `OrderCreateService.createOrder` 가 `@Transactional(NOT_SUPPORTED)` 이라 finally clear 가 자동으로 commit 이후에 호출된다. ADR-005 의 *Redis 호출은 commit 이후* 원칙이 listener 없이도 자연스럽게 보장된다.

`AFTER_COMMIT` listener 는 기본 동기 실행이므로 *latency 격리* 효과가 없다. 따라서 listener 유지 시 부가 비용(event 클래스, publish 호출, MDC 전파)만 떠안고 격리 가치는 0. 비동기 listener 도입은 별도 작업 (Redis timeout 영향이 실제 문제가 될 때 재검토).

### PROCESSING TTL 결정 기준

PROCESSING TTL = 60초.

- 정상 처리 latency (p99) 보다 *충분히 길어야* 한다 — 현재 주문 생성 ms 단위, 60초 마진 충분.
- MySQL `innodb_lock_wait_timeout` (기본 50초) 보다 살짝 길다 — lock 대기 후에도 TTL 만료로 자가 회복 가능.
- 비정상 잔존 (서버 crash) 시 사용자 봉인 시간 = 60초. 클라이언트 backoff retry 한두 사이클.

## 데이터 흐름

### 정상 흐름

```
Client → POST /orders { Idempotency-Key: abc-123 }
  ↓
OrderCreateService.createOrder
  ├─ reserve(memberId, abc-123) → true (marker "1" 박힘, TTL 60s)
  ├─ try
  │   ├─ findByMemberIdAndIdempotencyKey → empty
  │   └─ processor.execute(command)
  │       ├─ @Transactional 시작
  │       ├─ stock decrease, order save, cart remove
  │       └─ commit
  └─ finally clear(memberId, abc-123) — marker 삭제
  ↓
200 OK
```

### 같은 키 동시 요청

```
Req A: reserve → true, processor 진행 중
Req B: reserve → false (A 가 박은 marker 있음)
       → throw ORDER_IDEMPOTENCY_IN_PROGRESS
       → 409 응답
Req A: 정상 종료, finally clear
```

Req B 가 backoff 후 retry → reserve → true → 사전 find → A 의 주문 발견 → 200 반환.

### Redis 일시 장애

```
Req A: reserve → DataAccessException → catch → false 반환 (fallback)
       → 409 응답 (잘못된 응답이지만 일시적)
       → 클라이언트 retry
```

Redis 복구 후 정상 흐름 복귀. 멱등성 자체는 DB unique 제약이 최종 보장.

### 비정상 잔존 (서버 crash)

```
Req A: reserve → true, processor 진행 중에 서버 crash
       → commit 못 함, marker 만 남음
60초 후: TTL 만료, marker 사라짐
Req B (TTL 만료 후): reserve → true → 사전 find empty → processor 재시도
```

## 예외 및 실패 처리

| 케이스 | 처리 |
| --- | --- |
| `reserve` 가 false 반환 (동시 요청 또는 Redis fallback) | `OrderException(ORDER_IDEMPOTENCY_IN_PROGRESS)` → 409 |
| `reserve` 의 Redis 호출 실패 (`DataAccessException`) | Infrastructure 에서 catch, false 반환 후 위와 동일 409 |
| `clear` 의 Redis 호출 실패 | Infrastructure 에서 catch, warn 로그만 (마커 잔존 → TTL 만료로 자가 회복) |
| 사전 find empty + `processor.execute()` 비즈니스 예외 | finally clear 후 예외 그대로 throw → GlobalExceptionHandler 가 도메인 코드 처리 |
| `processor.execute()` race 후 unique 위반 (Redis fallback 으로 reserve 가 false 못 막은 경우) | finally clear 후 안전망 500 (ADR-011 정합, race 빈도 매우 낮음) |

## 테스트 포인트

- `reserve` true → 사전 find empty → `processor.execute` 호출 → 200 반환 후 marker 삭제 확인
- `reserve` true → 사전 find 발견 → DB 결과 반환 후 marker 삭제 확인
- `reserve` false → 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답, `processor.execute` 미호출
- `processor.execute` throws → finally clear 호출, 예외 rethrow
- Redis `DataAccessException` 시 `reserve` false 반환, `clear` warn 만 찍고 throw 없음
- 동시성 통합 테스트: 같은 idempotencyKey 로 동시 두 요청 → 한쪽 200, 다른 한쪽 409
- `OrderIdempotencyStatus` / `OrderIdempotencyCacheEvent` / `getCompletedOrderId` 잔존 참조 없음 (grep 검증)
- listener (`handle`) 가 제거되어 `AFTER_COMMIT` 트리거 없음 (코드 부재 확인)
