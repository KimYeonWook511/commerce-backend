# 태스크 ADR

## ADR-1: 멱등성 캐시 책임을 in-flight 차단 전용으로 좁힌다

### 배경

기존 결정 (`docs/tasks/order-idempotency/adr.md` ADR-001/002) 은 Redis 를 *1차 방어선 + 결과 캐시*, RDB unique 제약을 *최종 보장* 으로 두는 이중 구조였다. `PROCESSING` / `COMPLETED` 두 상태 enum 과 `AFTER_COMMIT` listener 로 상태 전이를 관리했다.

이후 ADR-011 (find-first 패턴) 도입으로 정상 멱등 흡수는 사전 DB find 가 담당하게 됐고, race window 충돌은 안전망 500 위임으로 처리됐다. 그러나 issue #171 이 지적한대로 *같은 키 동시 요청* 은 race window 의 흔한 트리거이며, 안전망 500 후 retry 로 회복은 되지만 클라이언트는 *처리 중인지 실제 장애인지* 구분할 수 없다.

또한 issue #172 가 `FAILED` enum 이 placeholder 로만 존재함을 지적했다. 결과 캐싱(COMPLETED / FAILED) 의 효용을 재검토하면:

- 같은 키 재요청은 *짧은 시간 내* (네트워크 retry, 모바일 background retry) 에 일어남. 결과 캐싱 TTL 600초의 효용 작음.
- COMPLETED hit 시에도 `findById(orderId)` 로 DB 조회 발생. *DB 조회 자체* 가 줄지 않음 (PK lookup vs unique index lookup 차이 ms 미만).
- 결과 캐싱은 *캐시-DB 정합성* 위험을 만듦 (listener 실패 시 불일치). #173 이 비동기 전환 시 이 위험을 증폭시킨다고 지적.

### 결정 내용

다음으로 결정한다:

1. **Redis 마커는 *in-flight 표시* 만 한다.** `PROCESSING` 의미 한 가지. enum 자체 제거.
2. **`COMPLETED` / `FAILED` 캐싱 제거.** DB 가 결과의 진실 단일 원천.
3. **같은 키 동시 요청 응답을 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 로 명시화.** 안전망 500 위임은 *Redis fallback 후 도달하는 race* 한 곳에만 남는다 (ADR-011 정합 유지).
4. **`listener` / `event` 구조 제거.** `OrderCreateService` 가 `try-finally` 로 `clear()` 를 직접 호출.
5. **PROCESSING TTL 60초** (기존 600초). lock wait timeout + α.
6. **Redis 장애 시 도메인 예외 매핑 + application fallback.** infra adapter 가 `DataAccessException` 을 catch 해 `OrderIdempotencyStoreUnavailableException` 으로 변환, `OrderCreateService` 가 catch 해 DB unique 안전망 경로(`findOrExecute`)로 진행. `reserve` 의 boolean 반환 시맨틱은 *진짜 예약 여부* 만 표현. fallback 경로는 marker 미생성이므로 `clear` 호출하지 않는다.

### 근거

- **캐시의 본질은 in-flight 차단.** 결과 캐싱은 부수 효과였고, DB 조회와의 latency 차이가 ms 미만이라 효용이 작다. 그 부가 책임을 제거하면 인터페이스가 2개 메서드로 단순해진다.
- **listener 의 가치가 의문스럽다.** `@TransactionalEventListener(AFTER_COMMIT)` 의 기본 동작이 동기라 *latency 격리 효과가 0* 이다. publisher 패턴이 유효한 시점은 *진짜 비동기 분리* 또는 *다중 후처리* 가 필요할 때이며, 이번 책임 (마커 정리 한 줄) 은 둘 다 해당하지 않는다.
- **`OrderCreateService` 가 `NOT_SUPPORTED` 이므로 finally clear 가 자동으로 commit 이후 호출된다.** ADR-005 의 *Redis 호출은 RDB commit 이후* 원칙이 listener 없이도 자연스럽게 보장된다.
- **사전 find 를 reserve 뒤에 둠으로써 캐시의 *DB 도달 전 차단* 가치를 보존한다.** reserve false (= 동시 요청 차단) 인 경우 DB find 자체가 발생하지 않는다.
- **race window 응답 일관성.** 사용자 입장에서 *동시 요청 = 409* 의 의도된 응답을 받게 된다. ADR-011 의 안전망 500 위임은 사라지지 않고 (Redis fallback 후의 진짜 race), 빈도가 매우 낮은 케이스로 한정된다.
- **Redis 장애 시 boolean 시맨틱 정직성.** *예약됨* 과 *저장소 사용 불가* 를 boolean false 한 신호로 합치면 application 이 *정상 차단* 과 *시스템 사정* 을 구별 못 한다. 도메인 예외로 분리하면 application 이 명시적으로 분기 가능. port 시그니처에 Spring `DataAccessException` 이 노출되지 않아 port 추상화도 보존된다.

### 결과

**기대 효과**

- `OrderIdempotencyStore` 인터페이스 4 → 2 메서드, enum 1개 제거, event 클래스 1개 제거, listener 메서드 1개 제거. 코드 감소 + 책임 명확.
- 같은 키 동시 요청에 명시적 409 응답. 클라이언트가 *처리 중* 임을 인지하고 backoff 재시도 가능.
- `#173` (AFTER_COMMIT listener 비동기 전환) 자동 close — listener 자체가 없다.
- 캐시-DB 정합성 위험 제거 (Redis 는 in-flight 마커만 가짐, 결과는 DB 만).
- Redis 메모리 절약 — 정상 완료된 키가 즉시 사라짐 (TTL 만료 대기 없음).

**감수할 trade-off**

- 같은 `idempotencyKey` 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (예: 첫 시도 `ProductNotFound`, 재시도 시점 상품 등록됨 → 200). *완벽한 응답 일관성* 측면에서는 약하지만, *DB 상태 기준 멱등성* 으로 본다면 정상.
- Redis timeout (수 ms ~ 수 초) 시 응답 latency 에 그대로 영향. 비동기 listener 도입 시 분리 가능하지만 이번 작업 범위 밖. timeout 빈도가 실제 문제로 드러나면 재검토.
- Redis 장애로 fallback 경로 (도메인 예외 catch 후 DB 직접 진행) 에 동시 요청이 양쪽 동시 진입한 경우, 후속 race 는 여전히 안전망 500 도달 가능 (ADR-011 정합). 빈도 매우 낮다는 전제 유지.

## ADR-2: PROCESSING TTL 을 60초로 설정한다

### 배경

기존 TTL 600초는 `PROCESSING` 마커가 *결과 캐싱(COMPLETED)* 까지 겸하던 시절의 값이다. 결과 캐싱이 사라지면서 PROCESSING 마커의 의미는 *처리 진행 중* 한 가지로 좁아진다.

TTL 의 두 가지 trade-off:

- 너무 짧으면 정상 처리 시간이 TTL 을 넘을 때 race 발생 (lock 대기, 트랜잭션 timeout 등).
- 너무 길면 비정상 잔존 (서버 crash) 시 사용자 봉인 시간이 길어진다.

### 결정 내용

PROCESSING TTL = 60초.

### 근거

| 지표 | 값 | 60초 적정성 |
| --- | --- | --- |
| 주문 생성 p99 latency | ~100ms 추정 | ✅ 600배 마진 |
| MySQL `innodb_lock_wait_timeout` (기본) | 50초 | ✅ 60초가 살짝 김. lock wait 만료 직후 TTL 도 만료로 자가 회복 |
| 트랜잭션 timeout | 별도 설정 없음 (DB statement 기본) | ✅ 일반 처리는 안전 |
| 비정상 시 사용자 지연 | — | ✅ 클라이언트 backoff 한두 사이클 |

미래에 결제 PG 호출 같은 외부 I/O 가 트랜잭션 안으로 들어오면 latency 변동이 커지므로 TTL 재검토 필요.

### 결과

**기대 효과**

- 비정상 잔존 시 자가 회복 시간이 짧아짐.
- 정상 처리는 ms 단위라 TTL 만료 race 발생 거의 불가능.

**감수할 trade-off**

- 트랜잭션 안에서 lock 대기 또는 외부 호출로 60초를 넘는 경우 race 가능성. 현재 코드에선 발생 안 함. 미래 결제 통합 시 ADR 재검토 트리거.

## ADR-3: 마커 정리는 Service finally 가 직접 호출한다

### 배경

마커 정리 책임을 두는 두 가지 방식이 있다:

- (A) `try-finally` 로 Service 가 직접 `clear()` 호출. 동기.
- (B) `applicationEventPublisher` + `@TransactionalEventListener(AFTER_COMPLETION)` 으로 listener 가 `clear()` 호출.

기존 코드는 (B) 의 변형 (`AFTER_COMMIT` 만, `complete()` 호출) 이었다.

### 결정 내용

(A) 채택. `OrderCreateService.createOrder` 가 `try-finally` 안에서 `clear()` 직접 호출. event / listener 모두 제거.

### 근거

- **`AFTER_COMMIT` listener 는 기본 동기 실행.** *latency 격리 효과가 0* 이다. (B) 를 유지하면서 동기 listener 로 가면 event 클래스 · publish 호출 · MDC 전파 같은 부가 비용만 떠안고 얻는 게 없다.
- **`OrderCreateService` 가 `NOT_SUPPORTED` 이므로 finally clear 가 자동으로 commit 이후 호출된다.** ADR-005 의 *Redis 호출은 commit 이후* 원칙이 listener 없이도 자연스럽게 보장된다.
- **사전 find 분기 처리가 단순해진다.** (B) 로 가면 사전 find 분기 (트랜잭션 없는 위치) 에서 event 못 씀 → 분기마다 패턴 혼재 → 결국 직접 호출 fallback 필요. (A) 는 단일 패턴.
- **`AFTER_COMPLETION` 으로 변경해 rollback 시에도 트리거되도록 할 수 있지만, 그 효과가 finally 와 동일하므로 event 도입 가치 부재.**

### 결과

**기대 효과**

- listener / event 클래스 / publish 호출 / MDC 동봉 모두 제거. 코드 감소.
- 흐름 추적이 직관적 — *"여기서 clear 호출됨"* 이 한 곳에 보인다.
- `#173` 자동 close.

**감수할 trade-off**

- *진짜 비동기 분리* 가 필요해질 때 (Redis timeout 잦아짐, 또는 다른 후처리 추가) 다시 listener 로 추출해야 함. YAGNI 원칙으로 그 시점에 추출.
- 도메인 이벤트 (예: PaymentCompleted) 같은 *다중 후처리 publish* 가 필요한 경우는 Spring Event 자체가 적합하지만, 본 책임 (마커 정리) 은 *단일 책임* 이라 publisher 패턴이 과함.
