# 회고록: order-idempotency-cache-simplification

## 1. 배경

### Issue #171 — PROCESSING 동시 요청 안전망 500 문제

같은 `idempotencyKey` 로 동시에 들어온 두 요청 중 한쪽이 `PROCESSING` 마커가 Redis 에 박힌 상태에서 INSERT race window 에 진입하면, unique 위반 후 안전망 500 응답이 발생한다. 클라이언트 입장에서는 *처리 중인지 실제 서버 장애인지* 구분할 수 없다. 또한 race catch 의 `clear()` 가 다른 요청의 마커까지 지워 연쇄 충돌의 시작점이 될 수 있었다.

### Issue #172 — FAILED enum placeholder, 실패 캐싱 정책 미정

`OrderIdempotencyStatus.FAILED` enum 값이 어디서도 set 되지 않는 placeholder 로만 존재했다. 실패 캐싱을 *언제*, *어떻게* 할 것인지 정책이 결정되지 않은 채 enum 만 코드 의도를 모호하게 만들고 있었다.

### 두 이슈를 멱등성 상태 머신 전체 재설계로 묶은 이유

`#171` 의 동시 요청 문제와 `#172` 의 `FAILED` 정책 공백을 별도로 처리하면 서로 다른 방향의 패치가 생긴다. 두 이슈를 함께 보면 *Redis 가 PROCESSING / COMPLETED / FAILED 세 상태를 관리한다* 는 가정 자체를 재검토할 필요가 있었다. 결과 캐싱(COMPLETED / FAILED)의 효용을 DB find 와 비교했을 때 ms 미만의 차이라는 사실이 드러나면서, Redis 의 책임을 *in-flight 차단 전용* 으로 좁히는 방향으로 전체 재설계로 이어졌다.

---

## 2. 결정 과정 요약

### sealed interface 도입 → 거부

멱등성 상태를 타입 안전하게 표현하기 위해 sealed interface 도입이 검토됐다. 그러나 마커가 *처리 중* 한 가지 의미만 갖게 되므로 타입 분기 자체가 불필요해진다. 기존 코드베이스가 enum 패턴을 사용하는 컨벤션이고, 단순한 경우에 sealed interface 를 도입하면 불필요한 추상화가 된다. enum 으로도 충분하다는 판단 이후, 최종적으로 상태 enum 자체를 제거하는 방향으로 결정됐다.

### COMPLETED 캐싱 제거

`COMPLETED` 캐시 hit 시에도 결국 `findById(orderId)` 로 DB 를 조회해야 한다. unique index find (`findByMemberIdAndIdempotencyKey`) 와의 latency 차이가 ms 미만이라 캐시의 실질적 이점이 없다. 반면 캐시-DB 정합성 위험(listener 실패 시 COMPLETED 가 없는 상태)만 추가된다. COMPLETED 캐싱 제거.

### FAILED 캐싱 제거

재시도 시 같은 비즈니스 검증을 DB 에서 다시 거치므로 같은 실패 결과를 얻는다. 일시 실패(네트워크 등)는 retry 로 회복된다. 실패 결과를 캐싱해도 재시도 의미가 없어진다는 부작용이 있다. FAILED 캐싱 불필요.

### clear 위치 검토

마커 정리 위치를 여러 단계로 검토했다:

1. **catch 블록에서 clear** — 비즈니스 예외(4xx)는 정리되지만 RuntimeException(5xx) 에서 누락.
2. **finally + success flag** — 성공 시에만 clear. 실패 시 TTL 만료 대기. 불필요한 복잡도.
3. **finally 무조건 clear** — 성공/실패 무관 즉시 정리. 재시도 가능. 최종 채택.

finally 무조건 clear 가 가장 단순하고 일관성이 있다. 비정상 잔존(서버 crash)은 TTL 60초 만료로 자가 회복.

### publisher 패턴 검토 후 listener 제거

기존에는 `OrderCreateProcessor` 가 `applicationEventPublisher.publishEvent(OrderIdempotencyCacheEvent)` 를 발행하고 `RedisOrderIdempotencyStore.handle()` 이 `@TransactionalEventListener(AFTER_COMMIT)` 으로 받아 `complete()` 를 호출했다.

핵심 문제: `@TransactionalEventListener(AFTER_COMMIT)` 의 **기본 동작이 동기** 이다. latency 격리 효과가 0 이다. publisher 패턴이 유효한 시점은 *진짜 비동기 분리* 또는 *다중 후처리* 가 필요할 때인데, 마커 정리 한 줄이라는 단일 책임에는 둘 다 해당하지 않는다.

`OrderCreateService` 가 `@Transactional(NOT_SUPPORTED)` 이므로 finally clear 가 **자동으로 commit 이후** 에 호출된다. ADR-005 (`Redis 호출은 RDB commit 이후`) 원칙이 listener 없이도 자연스럽게 보장된다. listener / event 클래스 전부 제거.

### 사전 find 위치: reserve 뒤

사전 DB find 를 reserve 앞에 두면 캐시가 *DB 도달 전 차단* 역할을 할 수 없게 된다. 동시 요청이 모두 DB find 를 통과한 뒤 race window 에 진입할 수 있다. reserve 뒤에 find 를 둠으로써 reserve false (= 다른 요청 처리 중) 인 경우 DB find 자체가 발생하지 않는다. 캐시의 DB 도달 전 차단 가치가 보존된다.

### Redis 장애 응답 정책 (코드 리뷰 피드백 반영)

코드 리뷰(Gemini high, Codex P2)에서 *`reserve()` 의 `DataAccessException` catch 시 `false` 반환은 시스템 마비를 일으킨다* 는 결함을 지적받았다. `OrderCreateService` 는 `reserve` false 를 *in-flight 충돌* 로 해석해 409 를 던지는데, *Redis 장애* 도 같은 false 신호로 합쳐져 단독 주문조차 409 로 차단되었다.

처리 방향으로 세 가지를 비교했다.

1. **`return false` → `return true` 한 줄 패치** — boolean 시맨틱이 *예약 성공* 이라고 거짓말. `reserve` 가 "in-flight 차단을 적용해야 하는가" 라는 *암묵적 의미* 로 바뀌어 후속 독자에게 혼란.
2. **application 에서 직접 `DataAccessException` catch** — Auth 도메인 (`AuthTokenIssueService`) 의 기존 컨벤션과 일치. 다만 application 이 Spring DAO 예외에 직접 의존 → port 추상화 의미 약해짐.
3. **도메인 예외 매핑 (채택)** — infra adapter 가 `DataAccessException` catch → `OrderIdempotencyStoreUnavailableException` 으로 변환. application 이 catch → fallback 분기. boolean 시맨틱은 *진짜 예약 여부* 만 표현. port 시그니처에 Spring 예외 노출 없음.

3안 채택. 이유는 본 PR 의 본질이 *캐시 책임 명확화* 이므로 port 추상화 강화 방향이 PR 정신과 일치한다는 점. Auth 의 1안 패턴 통일은 별도 issue 로 후속 검토.

예외 클래스 위치는 *기존 컨벤션* (모든 도메인이 `<domain>/exception/`) 에 맞춰 `com.commerce.order.exception.OrderIdempotencyStoreUnavailableException`. 부모는 `RuntimeException` 직접 상속 — `CustomException` 상속 시 `GlobalExceptionHandler.handleCustomException` 가 자동 응답 매핑되어 application catch 의도가 우회됨.

---

## 3. 핵심 트레이드오프

| 항목 | 채택 | 거부 | 이유 |
| --- | --- | --- | --- |
| 캐시 책임 | in-flight 차단만 | + 결과 캐싱 | 결과 캐싱 효용 작음, 정합성 위험 추가 |
| 마커 정리 방식 | Service finally 직접 호출 | publisher + AFTER_COMMIT listener | 동기 listener 는 latency 격리 0, 부가 비용만 |
| 동시 요청 응답 | 409 IN_PROGRESS | 안전망 500 (기존) | 사용자 일관성, race window 명시화 |
| Redis 마커 표현 | 단순 marker `"1"` | enum (PROCESSING/COMPLETED/FAILED) | 상태가 한 종류뿐 |
| PROCESSING TTL | 60초 | 600초 (기존) | 비정상 잔존 자가 회복 시간 단축 |
| 사전 find 위치 | reserve 뒤 | reserve 앞 | 캐시의 DB 도달 전 차단 가치 보존 |
| Redis 장애 처리 | infra 매핑 + application fallback | boolean false 반환 / boolean true 반환 / application 직접 catch | boolean 시맨틱 정직성, port 추상화 강화 |

---

## 4. 받아들인 한계

- 같은 `idempotencyKey` 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있다. 예: 첫 시도 `PRODUCT_NOT_FOUND`, 재시도 시점 상품 등록됨 → 200. 멱등성을 *DB 상태 기준* 으로 본다면 정상이지만 *완벽한 응답 일관성* 측면에서는 약하다.
- Redis timeout 시 응답 latency 에 그대로 영향이 간다. 비동기 listener 도입으로 분리 가능하나 이번 작업 범위 밖.
- Redis 장애 fallback 경로 (`DataAccessException` → `OrderIdempotencyStoreUnavailableException` 변환, application catch 후 DB 직접 진행) 에 동시 요청이 양쪽 진입한 경우 후속 race 는 여전히 안전망 500 에 도달할 수 있다 (ADR-011 정합 유지). 빈도가 매우 낮다는 전제 위에 정책이 성립한다.

---

## 5. 변경 범위

| 영역 | 변경 |
| --- | --- |
| `OrderIdempotencyStore` (port) | 4 → 2 메서드 (`reserve`, `clear`) |
| `RedisOrderIdempotencyStore` | listener `handle`, `complete`, `getCompletedOrderId`, `pushTraceIdIfMissing` 제거 |
| `OrderIdempotencyStatus` enum | 파일 삭제 |
| `OrderIdempotencyCacheEvent` | 파일 삭제 |
| `OrderCreateProcessor` | event publish 제거, ttl 인자 제거 |
| `OrderCreateService` | `attemptCreateOrder` 흡수, try-finally 단일 패턴, 409 throw 추가 |
| `OrderErrorCode` | `ORDER_IDEMPOTENCY_IN_PROGRESS` (HTTP 409) 추가 |
| `OrderIdempotencyStoreUnavailableException` | Redis 장애 도메인 예외 신규 (RuntimeException 직접 상속) |
| `application*.yml` | PROCESSING TTL 600 → 60 |
| 테스트 | `OrderCreateServiceIdempotencyTest` 시나리오 재구성, `RedisOrderIdempotencyStoreTest` 축소, `OrderCreateConcurrencyIntegrationTest` 신규 |
| 루트 docs | ADR (4개) / api-spec / architecture / logging-conventions / testing-conventions 동기화 |
| 기존 task adr | order-idempotency / event-outbox-trace-propagation / unique-find-first-policy 상단 cross-reference 한 줄 |

---

## 6. 부수 효과 (이슈 close)

- **#171 PROCESSING 동시 요청 안전망 500 → 409**: 명시적 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답 도입으로 해결. 클라이언트가 *처리 중* 임을 인지하고 backoff 재시도 가능.
- **#172 FAILED 캐싱 정책 결정**: *"캐싱 안 함"* 으로 결정. `OrderIdempotencyStatus` enum 자체 제거로 placeholder 소멸.
- **#173 AFTER_COMMIT listener 비동기 전환**: listener 자체가 제거되어 자동 close.

---

## 7. 미래 결정 시점

- **Redis timeout 이 실제 문제가 되는 빈도로 잦아지는 경우**: 비동기 listener 재도입 검토. 현재 동기 finally 가 Redis latency 를 응답 latency 에 직결시키고 있다.
- **외부 시스템 후처리 도입 (알림, 정산 등) 이 필요해지는 경우**: outbox 패턴 적용 (기존 Kafka outbox 사례와 동일 방향).
- **도메인 이벤트 (PaymentCompleted 등) 다중 후처리 패턴이 확산되는 경우**: Spring Event 부활 검토. *단일 책임 후처리* 가 아닌 *다중 후처리* 가 되는 시점에 publisher 패턴이 가치를 갖는다.
- **결제 PG 통합으로 트랜잭션 내부 외부 I/O 가 추가되는 경우**: PROCESSING TTL 재검토. 현재 60초 는 p99 latency ms 단위 + MySQL lock_wait_timeout 50초 마진으로 산정됐다.

---

## 8. 배운 점

- *캐시의 책임* 을 명확히 정의하면 인터페이스가 단순해진다. *부수 효과(결과 캐싱)* 까지 끌어안으면 정합성 책임이 늘어난다. 기능 추가 전에 "캐시가 이것도 책임지는 게 맞나?" 를 먼저 물어야 한다.
- 과한 추상화(sealed interface 등) 도입 전, *기존 단순 구조로 충분한지* 검토해야 한다. YAGNI 는 인터페이스 설계에도 그대로 적용된다.
- `@TransactionalEventListener(AFTER_COMMIT)` 은 *동기 실행* 임을 정확히 이해하지 않으면 *"event 분리로 비동기 격리가 됐다"* 는 착각이 생긴다. publisher 패턴의 가치는 *비동기 분리* 또는 *다중 후처리* 에 있지, 단순히 코드 분리에 있지 않다.
- publisher 패턴은 *진짜 비동기 분리* 또는 *다중 후처리* 가 필요할 때만 가치가 있다. *단일 책임 후처리* 에는 finally 직접 호출이 더 단순하고 명확하다.
- 영향 범위 grep 을 미리 해두면 step 분해가 명확해진다. step 2 에서 16곳 문서 수정이 필요했던 것처럼, 코드 1곳 변경의 실제 파급이 사전에 파악돼야 작업 규모 예측이 가능하다.
- *boolean 반환 시맨틱* 은 *정상 차단* 과 *시스템 사정* 을 동시에 표현하면 거짓말이 된다. 의미가 두 가지 필요하면 *예외로 분리* 하는 게 정직. infra 에서 도메인 예외로 매핑하면 port 시그니처에 기술 예외가 노출되지 않아 추상화도 함께 보존된다. 코드 리뷰 피드백을 단순 한 줄 패치로 받으면 시맨틱 부채가 누적된다는 사례.
