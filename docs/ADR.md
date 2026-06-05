# 아키텍처 결정 기록 (Architecture Decision Records)

## Task ADR 색인

본 ADR.md는 프로젝트의 **유일한 결정 타임라인**이다. 채택된 모든 결정(ADR-XXXX)이 여기 누적되며, 기존 결정은 수정하지 않고 갱신/supersede 마커로 이력을 보존한다. 아래 색인은 task adr 위치를 가리키는 메타 인덱스로, task가 늘어남에 따른 검색 비용을 줄이기 위한 것이다.

task adr(`docs/tasks/<task>/adr.md`)의 역할은 harness 도입을 기점으로 전환됐다.

- **이전**: task adr가 그 task의 도메인-specific 결정을 누적 관리하고, cross-cutting 결정만 본 ADR.md 본문에 두는 방식이었다. 아래 색인의 과거 task adr들은 이 방식으로 작성됐다.
- **이후(harness)**: task adr는 그 task에서 **새로 채택된 결정의 staging**이다(임시 번호 L1·L2…). harness Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 본 ADR.md에 append된다. task adr는 영구 보관소가 아니라 루트로 승격되기 전 대기 영역이며, 탐색만 하고 버린 안은 채택된 결정의 "고려한 대안"에 적고 별도 레코드로 만들지 않는다.

분류가 모호한 cross-cutting 결정은 본 ADR.md 본문으로 승격하고 task adr에 cross-reference를 남긴다.

| Task | adr 파일 | 주요 결정 키워드 |
|---|---|---|
| auth-redis-timing | [`docs/tasks/auth-redis-timing/adr.md`](tasks/auth-redis-timing/adr.md) | `Propagation.NOT_SUPPORTED`로 DB commit 후 Redis 저장 보장 (ADR-008 연계) |
| auth-refresh-token-store-unavailable | [`docs/tasks/auth-refresh-token-store-unavailable/adr.md`](tasks/auth-refresh-token-store-unavailable/adr.md) | refresh token Redis 장애 시 도메인 예외 매핑 + 도메인-specific @RestControllerAdvice 응답 매핑 (외부 캐시 장애 규약 통일) |
| boundary-logging-standardization | [`docs/tasks/boundary-logging-standardization/adr.md`](tasks/boundary-logging-standardization/adr.md) | 외부 시스템 경계 INFO/WARN/ERROR 로깅 표준 |
| cart | [`docs/tasks/cart/adr.md`](tasks/cart/adr.md) | CartItem-only 단일 entity aggregate, ID 참조(ADR-020), 가격 조회 시 재조회, 동시성 처리(@Version + retry + Processor 분리), 응답·엔드포인트 정책(unavailable·정렬·Remove 4xx) |
| core-domain-logging | [`docs/tasks/core-domain-logging/adr.md`](tasks/core-domain-logging/adr.md) | 도메인 이벤트 INFO 로그 적용 범위 |
| cross-aggregate-fk-cleanup | [`docs/tasks/cross-aggregate-fk-cleanup/adr.md`](tasks/cross-aggregate-fk-cleanup/adr.md) | cross-aggregate FK 5건 단일 V4 migration 일괄 제거, UNIQUE·same-aggregate FK 유지, ADR-020 series 완전 종료 (ADR-020 연계) |
| db-constraint-violation-handling | [`docs/tasks/db-constraint-violation-handling/adr.md`](tasks/db-constraint-violation-handling/adr.md) | `DuplicateKeyException` 좁은 catch (폐기, ADR-011로 대체) |
| event-outbox-trace-propagation | [`docs/tasks/event-outbox-trace-propagation/adr.md`](tasks/event-outbox-trace-propagation/adr.md) | 이벤트 객체 traceId 동봉, Outbox `trace_id` 컬럼 (ADR-019 연계) |
| hibernate-enum-jdbc-type-code | [`docs/tasks/hibernate-enum-jdbc-type-code/adr.md`](tasks/hibernate-enum-jdbc-type-code/adr.md) | `@JdbcTypeCode(SqlTypes.VARCHAR)` 적용 (ADR-018 연계) |
| kafka-trace-propagation | [`docs/tasks/kafka-trace-propagation/adr.md`](tasks/kafka-trace-propagation/adr.md) | ProducerInterceptor + RecordInterceptor (ADR-017 연계) |
| logback-setup | [`docs/tasks/logback-setup/adr.md`](tasks/logback-setup/adr.md) | 환경별 appender·encoder·rolling·마스킹 |
| mdc-keys-unification | [`docs/tasks/mdc-keys-unification/adr.md`](tasks/mdc-keys-unification/adr.md) | MDC 키 상수 통합 |
| memberid-mdc-propagation | [`docs/tasks/memberid-mdc-propagation/adr.md`](tasks/memberid-mdc-propagation/adr.md) | request attribute로 memberId MDC 전파 |
| order-idempotency | [`docs/tasks/order-idempotency/adr.md`](tasks/order-idempotency/adr.md) | Redis 1차 + RDB unique 이중 보장 (ADR-002 연계) — *`order-idempotency-cache-simplification` 으로 대체됨* |
| order-idempotency-cache-simplification | [`docs/tasks/order-idempotency-cache-simplification/adr.md`](tasks/order-idempotency-cache-simplification/adr.md) | Redis 는 in-flight 차단 전용, 동시 요청 409 IN_PROGRESS (ADR-002 갱신) |
| order-jpa-association-decouple | [`docs/tasks/order-jpa-association-decouple/adr.md`](tasks/order-jpa-association-decouple/adr.md) | Order·OrderItem JPA cross-aggregate association 해제 (`memberId: Long`, `productId: Long`), same-aggregate 유지, fetch join 대체 사용처별 분석 (same-aggregate 유지 / cross-aggregate 제거 + batch composition), `Order.create(Long memberId)` 시그니처, schema 무변경 원칙 (ADR-020 / 선행 stock sub-PR 연계) |
| order-item-price-snapshot | [`docs/tasks/order-item-price-snapshot/adr.md`](tasks/order-item-price-snapshot/adr.md) | OrderItem.unitPrice 컬럼 신설로 결제 시점 가격 snapshot 보존, V5 migration JOIN backfill, 응답 DTO 노출은 별도 PR (PR #200 / Issue #201 후속) |
| payment-attempt-idempotency | [`docs/tasks/payment-attempt-idempotency/adr.md`](tasks/payment-attempt-idempotency/adr.md) | PaymentAttempt unique 멱등 (ADR-010 연계) |
| payment-attempt-service-split | [`docs/tasks/payment-attempt-service-split/adr.md`](tasks/payment-attempt-service-split/adr.md) | approve/cancel attempt 서비스 분리 |
| payment-attempt-state-transition-policy | [`docs/tasks/payment-attempt-state-transition-policy/adr.md`](tasks/payment-attempt-state-transition-policy/adr.md) | PaymentAttempt 상태 전이 도메인 검증 (ADR-012 연계) |
| payment-compensation-policy | [`docs/tasks/payment-compensation-policy/adr.md`](tasks/payment-compensation-policy/adr.md) | 보상 catch 2차 예외 처리 (ADR-013 연계) |
| payment-compensation-to-domain | [`docs/tasks/payment-compensation-to-domain/adr.md`](tasks/payment-compensation-to-domain/adr.md) | 보상 정책 payment.application 이동, `PgCanceller` 콜백 (ADR-015 연계) |
| payment-jpa-association-decouple | [`docs/tasks/payment-jpa-association-decouple/adr.md`](tasks/payment-jpa-association-decouple/adr.md) | Payment JPA cross-aggregate association 해제 (`@OneToOne Order` → `Long orderId`), `Payment.createCompleted(Long orderId, int amount, ...)` Long ID 시그니처, schema 무변경 원칙, series 완료 (ADR-020 / 선행 sub-PR 연계) |
| payment-order-redesign | [`docs/tasks/payment-order-redesign/adr.md`](tasks/payment-order-redesign/adr.md) | 결제 도메인 두 테이블 분리 (PaymentReservation + Payment append-only), merchantPayKey 책임 Order → Reservation 이동, NULL 트릭 partial unique, UNKNOWN 마킹, `/payments/ready` → `/payments/reserve` rename (ADR-026 연계) |
| product-management | [`docs/tasks/product-management/adr.md`](tasks/product-management/adr.md) | 관리자 command 분리, soft delete, 상태별 공개 조회 |
| product-query | [`docs/tasks/product-query/adr.md`](tasks/product-query/adr.md) | 공개 상품 조회 노출 조건 |
| stock-jpa-association-decouple | [`docs/tasks/stock-jpa-association-decouple/adr.md`](tasks/stock-jpa-association-decouple/adr.md) | Stock·StockHistory JPA cross-aggregate association 해제, application 외부 주입 패턴, schema 무변경 원칙 (ADR-020 연계) |
| stock-management | [`docs/tasks/stock-management/adr.md`](tasks/stock-management/adr.md) | 관리자 재고 변경 이력 (ADR-004 연계) |
| traceid-mdc-filter | [`docs/tasks/traceid-mdc-filter/adr.md`](tasks/traceid-mdc-filter/adr.md) | `TraceIdFilter` MDC 전파 |
| unique-find-first-policy | [`docs/tasks/unique-find-first-policy/adr.md`](tasks/unique-find-first-policy/adr.md) | find-first 패턴 (ADR-011 연계) |

향후 task 추가 시 본 표에 한 줄을 갱신한다. task adr 위치는 모두 `docs/tasks/<task>/adr.md`로 고정한다.

---

### ADR-001: JWT + Redis 기반 인증 유지
- **결정**: Access Token은 JWT로 처리하고 Refresh Token은 Redis에 저장한다.
- **이유**: 토큰 재발급 시 서버 검증과 강제 무효화가 가능하다.
- **트레이드오프**: 완전한 stateless 인증보다 저장소 관리 비용이 늘어난다.

### ADR-002: 주문 생성에 멱등 키 적용 (Redis 1차 방어선 + RDB unique 제약 최종 보장)

> **본 결정은 `order-idempotency-cache-simplification` 으로 갱신됨.** 기존 결정 (Redis 1차 + RDB 최종 이중 보장, AFTER_COMMIT 결과 캐싱) 은 사용처 0건으로 폐기됨. 신규 결정은 아래 참조.

- **결정 (구)**: 주문 생성 요청은 멱등 키를 요구하며, Redis(1차)와 RDB unique 제약(최종)으로 이중 보장한다. `idempotencyKey`는 클라이언트가 생성한 UUID이며 HTTP Header(`Idempotency-Key`)로 전달한다.
- **멱등성 처리 흐름 (구)**: Redis `reserve()` 성공 시 주문 생성 → AFTER_COMMIT 이벤트로 Redis 캐싱 (ADR-005 구현). Redis MISS(TTL 만료 or Redis 장애) 시 바로 INSERT 시도 → `(member_id, idempotency_key)` unique 위반 시 기존 주문을 조회하여 `complete()`로 Redis 갱신 후 반환. 기존 주문을 찾지 못하면 멱등키 외 다른 제약 위반이므로 `log.error` 기록 후 `ORDER_NOT_FOUND` 반환.
- **Redis 장애 처리 (구)**: `reserve()`, `getCompletedOrderId()`, `complete()`, `clear()`, `handle()` 실패 시 모두 Infrastructure 계층에서 예외를 catch. `reserve()`→`false`, `getCompletedOrderId()`→`empty()` fallback으로 주문 생성 경로 진입. 나머지는 warn 로그 후 무시하여 주문 반환에 영향 없음.
- **이유 (구)**: Redis TTL 만료 후 중복 주문 생성 방지 및 Redis 장애 시에도 주문 가능성 보장.
- **트레이드오프 (구)**: TTL 만료 후 재요청 시 재고 차감 → unique 위반 → 롤백이 드물게 발생할 수 있다. 정확성에는 문제 없다.

#### ADR-002 갱신 (`order-idempotency-cache-simplification`): Redis in-flight 차단 + DB unique 제약 최종 보장

- **결정**: 주문 생성 요청은 멱등 키를 요구하며, Redis 는 in-flight 차단 전용, RDB unique 제약이 멱등성 진실의 단일 원천이다. `idempotencyKey` 는 클라이언트가 생성한 UUID 이며 HTTP Header (`Idempotency-Key`) 로 전달한다.
- **흐름**: Redis `reserve()` 성공 시 주문 생성 → finally `clear()` 로 마커 즉시 정리. Redis `reserve()` 실패 (다른 요청 처리 중) 시 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답. 클라이언트는 backoff 재시도.
- **Redis 장애 처리**: `reserve()` 의 `DataAccessException` 은 Infrastructure adapter 에서 `OrderIdempotencyStoreUnavailableException` 으로 변환 (log.error). `OrderCreateService` 가 catch 해 DB unique 제약 안전망 경로(`findOrExecute`)로 fallback 진행 (log.warn, 정상 응답 가능). marker 미생성 경로이므로 `clear()` 호출하지 않는다. `clear()` 의 `DataAccessException` 은 Infrastructure 에서 warn 만 (마커 잔존은 60초 TTL 만료로 자가 회복).
- **PROCESSING TTL**: 60초. MySQL `innodb_lock_wait_timeout` (50초) + α.
- **이유**: 결과 캐싱 (COMPLETED / FAILED) 은 DB unique index find 대비 latency 차이 ms 미만이고, 캐시-DB 정합성 위험만 추가. 캐시 책임을 *in-flight 차단* 한 가지로 좁히면 인터페이스가 2개 메서드로 단순해진다.
- **트레이드오프**: 같은 키 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (멱등성은 DB 상태 기준). Redis timeout 시 응답 latency 영향 (비동기 listener 도입은 별도 작업).

### ADR-003: 재고 차감 기본 전략으로 비관적 락 사용
- **결정**: 주문 경로의 재고 차감은 비관적 락 기반 흐름을 기본으로 사용한다.
- **이유**: 동시 주문 상황에서 재고 정합성을 단순하고 명확하게 보장하기 쉽다.
- **트레이드오프**: 높은 경쟁 상황에서 락 대기와 DB 부담이 커질 수 있다.

### ADR-004: 관리자 재고 관리와 변경 이력 분리
- **결정**: 관리자 초기 재고 생성과 수동 증가/감소는 상품 API와 분리된 재고 API로 제공하고, 관리자 변경 이력은 `tbl_stock_history`에 저장한다.
- **이유**: 상품 등록/수정 책임과 재고 운영 책임을 분리하고, 변경 수량·사유·관리자 member id·시점을 감사 데이터로 보존할 수 있다.
- **트레이드오프**: 상품 생성 후 초기 재고 생성을 별도 호출해야 하며, 첫 버전의 이력 조회는 pagination 없이 상품별 전체 목록을 반환한다.

### ADR-005: Redis 캐싱은 RDB 커밋 이후 실행
- **결정**: Redis 작업은 기본적으로 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 분리하여 RDB 트랜잭션 커밋 이후에 실행한다.
- **이유**: Redis 장애 시 RDB 롤백을 방지한다. 멱등성 캐싱은 정합성이 아닌 편의 목적이므로 RDB 커밋이 완료된 뒤 별도 실행해도 무방하다. `@TransactionalEventListener`는 DDD 레이어 경계를 유지하며 Application이 Infrastructure를 직접 알지 않아도 되므로 `TransactionSynchronizationManager`보다 자연스럽다.
- **트레이드오프**: RDB 커밋 완료 ~ Redis 캐싱 완료 사이의 짧은 gap에서 동일 키 요청이 오면 캐시 MISS로 처리되어 중복 실행 가능성이 있다.
- **기능별 판단 기준**: 기본값은 AFTER_COMMIT 분리다. Redis 장애 시 RDB도 롤백해야 하는 정합성 최우선 상황에서는 동일 트랜잭션을 택하고, 해당 기능 ADR에 이유를 명시한다.
- **주의사항**: AFTER_COMMIT 시점은 트랜잭션이 이미 종료된 이후다. 핸들러 안에서 추가 DB 작업이 필요하다면 `Propagation.REQUIRES_NEW`로 새 트랜잭션을 열어야 한다. Redis만 다루는 경우라면 불필요하다.
- **주문 멱등성 캐시는 본 정책 적용 대상에서 제외** (`order-idempotency-cache-simplification` 결정). `OrderCreateService` 가 `NOT_SUPPORTED` 라 `try-finally` 직접 호출이 자동으로 commit 이후 실행됨. listener 우회 불필요.

### ADR-006: application 계층 클래스명은 Service suffix를 사용한다
- **결정**: 유스케이스 단일 책임 구조를 유지하되, 클래스 suffix는 `UseCase` 대신 `Service`로 명명한다.
- **이유**: Spring 기반 프로젝트 관습과의 일관성을 유지하고, 기존 코드베이스의 네이밍과 통일한다. 구조적으로는 UseCase 패턴과 동일하다 (`CreateOrderService` = `CreateOrderUseCase`).
- **트레이드오프**: DDD 순수론 관점에서 `UseCase`가 더 명확한 의도를 드러내나, 현재는 친숙한 네이밍을 우선한다.

### ADR-007: 인증 토큰 Redis 저장 실패 정책 — strict
- **결정**: Redis 저장/조회 실패 시 `AuthException(INTERNAL_ERROR)`을 던진다. Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다.
- **배경**: Redis 장애 시 soft fail(로깅만, access token은 발급)을 선택하면 클라이언트에 refresh token을 발급했으나 Redis에 없는 상태가 된다. 사용자는 access token 만료 시 재발급을 시도하다가 예상치 못한 "token not found" 에러를 받게 된다. 이는 "동작하는 것처럼 보이지만 실제로는 망가진" 상태로, 더 나쁜 사용자 경험을 유발한다.
- **이유**: refresh token은 Redis가 저장소 자체다. Redis 없이 발급된 refresh token은 반드시 실패한다. 명확한 즉각 실패가 지연된 묵시적 실패보다 사용자 경험이 낫다. 기존 로그인 사용자(유효한 access token 보유)는 Redis 장애에 영향받지 않는다. Redis 장애는 인프라 레벨(HA)에서 해결해야 할 문제다.
- **트레이드오프**: Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다. 기존 세션(유효한 access token)은 영향받지 않는다. 향후 과제: Redis 단일 장애점 해소를 위해 Sentinel 또는 Cluster 구성 필요.

### ADR-008: 회원가입 트랜잭션 분리 — `Propagation.NOT_SUPPORTED`
- **결정**: `AuthSignUpService.signUp()` method-level annotation을 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 교체한다.
- **배경**: `signUp()`이 `@Transactional`로 외부 트랜잭션을 열면 `MemberRegistrationService.register()`가 `REQUIRED` 전파로 합류한다. Spring에서 commit은 트랜잭션을 시작한 메서드가 종료될 때 발생하므로 `register()` 반환 후에도 DB는 미커밋 상태다. 그 사이에 `issue()`가 Redis에 저장하면 DB commit 전 Redis 저장 불일치가 발생한다. 단순히 method-level `@Transactional`을 제거하면 class-level `@Transactional(readOnly = true)`가 적용되어 readOnly 트랜잭션에 합류하며 Hibernate가 flush mode를 MANUAL로 설정하므로 의도와 다르게 동작한다. `@TransactionalEventListener(AFTER_COMMIT)` 방식은 응답 반환 후 이벤트가 실행되므로 Redis 저장 실패를 클라이언트에 전달할 수 없어 ADR-007 strict 정책과 양립 불가하다.
- **이유**: `NOT_SUPPORTED`는 class-level `readOnly = true`를 명시적으로 override한다. `signUp()`이 트랜잭션 없이 실행되면 `register()`가 자체 트랜잭션으로 commit 후 반환한다. 이후 `issue()` 호출 = DB commit 이후 Redis 저장 보장. 기존 `OrderCreateService.createOrder()`가 동일 패턴을 사용한다 (ADR 일관성).
- **트레이드오프**: DB commit 이후 Redis 저장 순서가 보장된다. Redis 저장 실패 시 strict 예외 처리와 결합하면 부분 실패 시나리오가 명확해진다 (member는 DB에 생성됐으나 auth 실패 → 다음 요청에서 DUPLICATE_EMAIL 또는 로그인 성공).

### ADR-009: `RefreshTokenStore.delete()` 제거
- **결정**: `RefreshTokenStore` 인터페이스와 `RedisRefreshTokenStore` 구현체에서 `delete()` 제거.
- **배경**: `RefreshTokenStore` 인터페이스에 `delete(Long memberId)`가 정의되어 있으나, 현재 로그아웃 서비스가 구현되어 있지 않아 어디서도 호출되지 않는다. 사용되지 않는 인터페이스 메서드는 CLAUDE.md 원칙("불필요한 추상화와 과한 설계를 피한다")에 어긋난다.
- **이유**: 호출부가 없는 코드를 유지하는 것은 잠재적 혼란을 유발한다. Git 히스토리가 이 메서드의 존재와 제거 이유를 기록한다. 로그아웃 구현 시 그 PR에서 `delete()`를 재추가하고 Redis 실패 정책을 함께 설계하는 것이 더 안전하다.
- **트레이드오프**: 인터페이스가 실제 사용 범위로 좁혀진다. 향후 과제: 로그아웃 기능 구현 시 `delete()` 재추가 및 Redis 실패 정책 결정 필요. 로그아웃은 보안 목적이므로 strict / soft 정책 선택이 신중히 검토되어야 한다.

### ADR-010: PaymentAttempt 멱등 재요청 amount mismatch는 명시적 예외로 거부
- **결정**: `(merchantPayKey, provider, paymentId, type)` 멱등 키에 대한 재요청이 기존 attempt의 amount와 다르면 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409 Conflict)를 던진다. 기존 attempt 상태(REQUESTED/FAILED/SUCCEEDED)와 무관하게 적용한다.
- **배경**: 기존에는 unique 제약 충돌 시 catch 블록에서 기존 attempt를 그대로 반환했다. amount가 다른 경우에도 침묵 처리되어 호출자 측 산출 오류나 PG 응답 검증/보상 취소 흐름에서 어떤 amount를 기준으로 삼을지 모호해진다. 멱등성 계약("같은 요청 → 같은 결과") 위반이 가시화되지 않는 문제다.
- **이유**: 호출자 측 mismatch(내부 원인)는 PG 응답 mismatch(`PAYMENT_AMOUNT_MISMATCH`, 400, 외부 원인)와 의미·모니터링 기준이 다르다. 별도 코드로 분리하면 알람/대시보드에서 원인 추적이 가능하다. 409 Conflict는 "이미 기록된 상태와 충돌한다"는 의미가 정확하다. amount 변경이 필요하면 새 `merchantPayKey`로 새 요청을 발급하는 게 정상 흐름이다.
- **트레이드오프**: 호출자가 잘못된 amount로 재시도하면 즉시 4xx로 실패한다. 기존에는 침묵 처리되어 후속 흐름에서 뒤늦게 발견될 수 있었다.
- **후속 (payment-order-redesign)**: 본 ADR 의 *재요청* 의미는 `payment-order-redesign` task 에서 *PaymentReservation 신규 발급* 으로 명확히 정리됐다. `Reservation.amount` 는 불변이며, amount mismatch 는 *새 Reservation (새 merchantPayKey)* 으로 표현된다. 기존 "amount 변경이 필요하면 새 `merchantPayKey`로 새 요청을 발급하는 게 정상 흐름이다" 표현은 이 결정의 반영이다.

### ADR-011: DB unique 위반은 안전망 500 으로 위임하고 정상 흐름은 사전 `find` 로 처리한다 (find-first 패턴 통일)
- **결정**: Application 계층 6곳(`MemberRegistrationService`, `PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `OrderCreateService`, `StockRestoreOutboxConsumeService`) 모두 `DB find → 없으면 insert → 충돌 시 500` 본질 흐름으로 통일한다. Application 과 Adapter 어디서도 `DuplicateKeyException` 을 catch 하지 않는다. `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러(`COMMON-500-2`) 를 추가해 DAO 카테고리 fallback 을 stack trace 와 함께 500 으로 처리한다. ADR-002 의 `(member_id, idempotency_key)` unique 위반 fallback 재조회 로직은 본 정책으로 대체되어, 정당한 멱등 재요청은 Redis reserve 성공 후 DB find 사전 체크로 흡수하고 race window 충돌은 안전망 500 으로 위임한다.
- **배경**: PR #106 (`docs/tasks/db-constraint-violation-handling/`) 에서 5곳을 `DuplicateKeyException` 좁은 catch 로 정리했으나 회고에서 "Application 이 인프라 예외 타입에 직접 의존한다" 는 부채가 분리되었다 (Issue #105). 후속 처리 옵션으로 (A) catch 를 Adapter 로 이동, (B) 5곳 모두 find-first 통일, (C) `Exception.class` fallback stack trace 보강만 검토했다. 옵션 A 는 5곳 처리 동작(멱등 흡수 / 도메인 예외 변환 / silent skip) 이 모두 달라 공통 변환 레이어가 의미 없고 도메인 매핑 지식이 Adapter 로 새는 문제가 있었다.
- **결정 근거**: 5곳의 unique 키는 모두 사용자 입력 식별자(email, merchantPayKey) 또는 idempotency key 기반이라 정상 흐름에서 동시 충돌 확률이 매우 낮다. 트랜잭션도 짧아 race window 가 좁다. find-first 패턴은 "트랜잭션 짧음 + 충돌 확률 낮음" 두 조건이 만족될 때 race window 비용이 안전망 500 처리로 충분히 흡수된다. 본 5곳은 이 조건을 만족한다. 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) 에는 본 정책을 적용하지 않고 try-save-catch 패턴이 더 적합하며, 향후 새 unique 제약 도입 시 위 두 조건으로 패턴을 선택한다. `DataAccessException` 부모 핸들러 추가는 운영 모니터링에서 DAO 카테고리 예외를 일반 `Exception` fallback 과 구분 가능하게 한다.
- **결과**: PR #106 정책(`DuplicateKeyException` 좁은 catch + 5곳 도메인 매핑) 은 폐기된다. 행위 변경은 race window 한정이다 — Member 가입 race 와 PaymentApproval race 는 4xx → 500, PaymentAttempt 2곳 race 는 200(멱등 흡수) → 500, StockRestoreOutbox race 는 200(silent skip) → 500, OrderCreate 는 `order-idempotency-cache-simplification` 에서 race window 응답이 500 → 409 `IN_PROGRESS` 로 변경됨 (Redis fallback 후 도달하는 진짜 race 는 여전히 안전망 500). 정상 멱등/중복 흐름은 모두 사전 `find` 분기로 보존된다. Application 이 `org.springframework.dao.*` 패키지에 의존하지 않게 되어 계층 의존 방향 부채가 함께 해소된다. 상세 옵션 비교와 5곳 매핑은 `docs/tasks/unique-find-first-policy/adr.md` 와 `docs/architecture.md` 의 예외 처리 섹션을 참조한다.
- **트레이드오프**: race 발생률이 매우 낮다는 전제 위에 정책이 성립한다. 만약 향후 어느 곳에서 race 가 잦아지면 본 ADR 의 "적용 조건" 이 깨지고 try-save-catch 로의 전환을 재검토해야 한다. `Exception.class` fallback 의 stack trace 로깅 누락은 본 ADR 에서 다루지 않는다 (DAO 카테고리는 부모 핸들러로 해결됐지만 NPE 등 일반 예외는 여전히 message-only 로깅, 별도 개선 과제).

### ADR-012: PaymentAttempt succeed/fail 메서드는 상태 전이를 도메인에서 검증한다
- **결정**: `PaymentAttempt`의 `succeed(respondedAt)` 및 `fail(failCode, detail, respondedAt)` 메서드는 호출 시점에 `status == REQUESTED` 조건을 검증한다. 위반 시 `PaymentException`(`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`, 500)으로 거부. 멱등 자기 전이도 거부.
- **배경**: 기존 mark 메서드 4개(`markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`)는 (1) `status == REQUESTED`, (2) `type`이 메서드 의도와 일치를 동시에 검증했다. 분리된 두 Service(`PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`)가 항상 올바른 type의 attempt만 조회·전달하므로 도메인 내 type 가드는 방어 가치를 잃어 제거됐다. mark 4개는 `succeed`/`fail` 2개로 통합됐다. ADR-012의 핵심 결정("REQUESTED 외 전이 거부 + failCode 보호")은 status 가드만으로 동일하게 보존된다.
- **이유**: 멱등성은 상위 레이어(`PaymentApprovalAttemptService.getOrCreate` + `NaverPayApprovalService.processApproveAttempt` switch)에서 처리되므로 `succeed`/`fail`은 멱등을 책임지지 않는다. Order 도메인의 명시적 선조건 검증 패턴과 일관. 도메인 무결성 위반은 내부 결함 신호라 외부 입력 mismatch(ADR-010, 409)와 구분되도록 500.
- **트레이드오프**: 새 검증 도입 시 catch 블록 안에서 `succeed`/`fail`이 호출되는 호출처(예: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`)는 race window에서 throw해도 보상 트랜잭션이 중단되지 않도록 적절히 보호해야 한다. 보상 catch 2차 예외 처리의 일반 원칙은 ADR-013으로 정의했다(`docs/exception-strategy.md` 참조). 상세는 `docs/tasks/payment-attempt-state-transition-policy/adr.md` 참조.
- **후속 (ADR-014, payment-compensation-policy task)**: ADR-D의 임시 처방(try-catch 보호 한 곳)이 ADR-014(Payment 존재 체크)로 대체됐다. race window에서 `succeed`/`fail`이 throw되는 경로 자체가 줄어들어 ADR-012의 엄격한 검증 원칙은 그대로 유지된다. #117(멱등 자기 전이 허용) close.

### ADR-013: 보상 catch 2차 예외 처리는 1차 예외 ERROR 로깅 + 의도 캡슐화 메서드 패턴을 따른다
- **결정**: 보상 흐름의 catch 블록은 (a) 진입 즉시 1차 예외를 `log.error`로 ERROR 레벨에 남기고, (b) 2차 시도가 던질 가능성이 있는 예외는 가급적 메서드 자체(`...IfRequested` 등)에서 캡슐화해 호출처에서 try-catch 없이 호출하도록 설계하고, (c) 그래도 던지는 경우 중요도에 따라 `log.warn` + 1차 예외 전파(덜 중요) 또는 Composite Exception(`addSuppressed`)으로 둘 다 전파(치명적) 한다. 의사결정 트리와 적용 예는 `docs/exception-strategy.md` "보상 catch 2차 예외 처리" 섹션 참조.
- **배경**: PR #112(ADR-012)에서 `PaymentAttempt` mark 메서드 선조건 검증이 추가되며 보상 흐름이 catch 안에서 mark 호출 시 race window에서 `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`를 만날 수 있게 됐다. 임시로 `failApproveAndCancelApprovedPayment` 안에 `try { failApprove(...) } catch (PaymentException markEx) { log.warn(...) }`를 넣었지만 catch 범위가 너무 넓어 `PAYMENT_ATTEMPT_NOT_FOUND` 같은 의도치 않은 예외까지 삼키는 문제가 있었다. 동시에 `completeVerifiedApproval`의 상위 catch 두 곳(`PaymentException`, `CustomException`)에 1차 예외 `log.error`가 누락돼 운영 원인 추적이 어려웠다.
- **이유**: catch 안에서 호출하는 메서드를 "예외 안 던지는 의도 캡슐화 메서드"로 만들면 호출처에서 try-catch가 사라지고 도메인 상태(예: `status == REQUESTED`) 검사가 application 레이어로 누출되지 않는다. 호출처는 의도(예: "가능하면 실패 처리, 아니면 skip")만 표현하고 도메인 규칙은 서비스 경계 안에 머문다. 1차 예외 ERROR 로깅은 운영 모니터링에서 근본 원인을 항상 보존하는 최소 보장이다. 로그 레벨은 1차 = ERROR, 2차 = WARN으로 구분해 1차 원인을 더 강하게 노출한다.
- **트레이드오프**: "Skip" 의도 캡슐화 메서드(`...IfRequested`)가 늘면 서비스 API 표면이 약간 넓어진다. 다만 호출처마다 try-catch 또는 if-status 검사가 흩어지는 것보다 응집도가 높다. Composite Exception(`addSuppressed`)은 치명적 케이스에서만 사용하고, 일반적으로는 catch 안 메서드를 "예외 안 던지게 설계"하는 쪽을 우선한다.

### ADR-014: 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다
- **결정**: 보상 흐름은 PG cancel 진행 전 `PaymentApprovalService.hasCompletedPayment(merchantPayKey)`로 완료된 Payment row 존재 여부를 확인해 이미 존재하면 cancel을 skip한다.
- **배경**: 기존 구조는 `PaymentAttempt.status`로 보상 진행 여부를 판단했으나 attempt에 lock이 없어 race window에서 SUCCEEDED attempt에 cancel이 호출되는 결함(#114)이 있었다.
- **이유**: Payment는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique 제약이 있고 `completeApprovedPayment`가 Order FOR UPDATE 안에서 저장하므로 race-safe하다. DDD 관점에서 Payment Aggregate의 불변식을 cross-Aggregate 협력으로 활용한다. `hasCompletedPayment`는 Payment 도메인의 사실 조회로 표현되어 미래 Payment 도메인 분리 시 외부 API 경로가 자연스럽게 보존된다.
- **트레이드오프**: Payment 조회 1회 추가되나 인덱스 조회라 성능 영향 미미하다.
- **PaymentAttempt Aggregate 캡슐화**: `PaymentAttempt.succeed`/`fail` 메서드는 `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService` 외부에서 직접 호출하지 않는다. 정책 강제는 코드가 아닌 ADR과 JavaDoc으로만 명시하며, ArchUnit 도입은 별도 후속 작업으로 분리한다.
- **후속 (ADR-015, payment-compensation-to-domain task)**: 보상 owner가 `NaverPayApprovalService.failApproveAndCancelApprovedPayment`에서 payment.application의 `PaymentApprovalCompensationService.runPgCancel`로 이동했다. 정책 자체(Payment 존재 체크 → cancel skip)는 동일하게 유지된다.
- **후속 (#182, 2026-06-02)**: 메서드 이름과 의미를 도메인 사실 조회(`hasCompletedPayment`)로 정리하고, 내부 구현은 `existsByMerchantPayKeyAndStatus(merchantPayKey, COMPLETED)`로 status까지 명시해 의미와 본문을 정확히 일치시켰다. 보상 service의 호출 코드(`if (hasCompletedPayment) skip`)가 정책 적용을 담당한다. "row 존재 = 결제 완료"의 근거(merchantPayKey unique + Order FOR UPDATE 안에서 저장)는 Payment 도메인 소유 지식이므로 사실 조회를 소유자에 박아 두면 Payment 정의 변경 시 한 곳만 갱신하면 된다.
- **후속 (payment-order-redesign)**: 두 테이블 분리 모델에서 *Payment row 존재 = 결제 완료* 사실 조회가 재정의됐다. 구현은 `existsApproveSucceeded(merchantPayKey)` — `tbl_payment` 에서 `type=APPROVE ∧ status=SUCCEEDED` 인 행 존재. 메서드 의미(`hasCompletedPayment`)와 정책(cancel skip 판단)은 동일하게 유지된다. 세부 결정은 `docs/tasks/payment-order-redesign/adr.md` 참조.

### ADR-015: 보상 정책은 payment.application 책임이고, PG 어댑터는 cancel 콜백만 제공한다
- **결정**: `NaverPayApprovalService`에 있던 보상 dispatcher 4개와 공통 골격을 `PaymentApprovalCompensationService`(payment.application)로 이동한다. PG cancel 호출은 `PgCanceller` @FunctionalInterface 콜백으로 위임하고, PG 응답은 `CancelOutcome` record로 변환해 payment.application이 `NaverPayCancelResult`를 직접 import하지 않도록 한다.
- **배경**: 보상 정책(어떤 실패 → cancel 필요/불필요, cancel reason, cancel amount)은 PG-agnostic 결제 도메인 책임이다. PG-specific한 부분은 cancel API 호출과 NaverPayCancelResult 응답 해석뿐이다. NaverPayApprovalService가 보상 정책을 내장하면 레이어 의존이 역전되고 PG 변경 시 정책 코드도 함께 영향받는다.
- **이유**: `PgCanceller` 좁은 콜백은 PaymentGateway port 완전 inversion(PG 둘 이상 추가 시)보다 지금 필요한 최소 구조만 도입한다. NaverPayApprovalService가 메서드 참조(`this::pgCancel`)로 구현하므로 인터페이스 추가 없이 의존 역전이 성립한다.
- **트랜잭션 정책**: `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional` 없음. 보상 흐름의 단계별 독립 commit 을 유지하기 위해 각 단계(`failIfRequested`, `hasCompletedPayment`, `getOrCreate`, `succeed`/`fail`)가 자기 트랜잭션을 가진다. 단일 트랜잭션으로 묶이면 한 단계 실패가 이전 단계 진행까지 롤백시켜 부분 진행 보존이 불가능해진다.
- **트레이드오프**: PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치를 재설계해야 한다. 이때 PaymentGateway port 완전 inversion으로 자연 승격 가능하다.
- **후속 (payment-order-redesign)**: `compensateDuplicateApproval` 보상 dispatcher 가 추가됐다. `uk_payment_approved_order_key` (NULL 트릭 partial unique) 위반 시나리오 — 같은 orderId 에 두 번째 APPROVE 가 성공으로 진입한 경우 — 를 막고 PG cancel 로 환불한다. 책임 위치는 본 ADR 그대로 (`PaymentApprovalCompensationService`). 세부 결정은 `docs/tasks/payment-order-redesign/adr.md` 참조.

### ADR-016: 부하 테스트 도구로 k6 + InfluxDB + Grafana 채택
- **결정**: 부하 테스트는 k6를 사용하고, 메트릭은 InfluxDB(1.8)에 저장해 Grafana로 시각화한다. 로컬 환경에서만 실행한다.
- **배경**: 주요 API의 성능을 정량적으로 측정한 데이터가 부재했고, 부하 시나리오의 정량 검증 수단이 필요했다. 운영 환경 모니터링·CI 통합은 별도 트랙으로 분리한다.
- **이유**: k6는 JavaScript로 시나리오를 표현해 가독성이 높고 `thresholds`로 SLO를 정량 검증할 수 있다. InfluxDB(1.8)는 k6 native output과 호환성이 검증돼 있으며(별도 xk6 빌드 불필요), Grafana 공식 k6 대시보드 템플릿(#2587)을 그대로 활용할 수 있어 시각화 도입 비용이 낮다. 대안 도구(JMeter, Gatling)는 GUI/XML 설정 부담 또는 Scala 학습 비용이 더 크다.
- **트레이드오프**: 부하 테스트 결과는 로컬 환경 사양에 의존하므로 절대 수치보다는 개선 전후의 상대 비교가 주된 활용 방식이다. CI 자동 실행·운영 환경 측정은 본 결정 범위 밖이며 후속 과제로 둔다.

### ADR-017: Kafka traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 구현한다
- **결정**: Kafka producer가 메시지를 발행할 때 `TraceIdKafkaProducerInterceptor`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착하고, consumer가 수신할 때 `TraceIdRecordInterceptor`가 헤더에서 traceId를 추출해 MDC에 push한다.
- **배경**: HTTP 요청 단위 traceId(이슈 #129, traceid-mdc-filter)가 Kafka 경계에서 단절되어 producer-consumer 흐름 추적이 불가능했다. 해결 방법으로 (A) 헤더 직접 부착(producer/consumer 코드 수정), (B) Spring Kafka 표준 확장점(ProducerInterceptor + RecordInterceptor)을 비교했다.
- **이유**: (B)가 producer/consumer 코드 시그니처를 무손상으로 유지하고, 향후 추가되는 producer/consumer에도 자동 적용된다. `DefaultKafkaProducerFactoryCustomizer` Bean 등록 방식은 `application.yml` 프로퍼티 방식 대비 프로파일별 누락 위험이 없다. `RecordInterceptor.afterRecord()` 콜백은 error handler·DLT 발행까지 완료된 이후 호출되므로 MDC 정리 시점이 보장된다.
- **트레이드오프**: outbox relay 스케줄러 → consumer 흐름에서 원 HTTP 요청 traceId와 consumer 로그가 연결되지 않는다. 이 연결은 OutboxEvent에 traceId 컬럼 추가가 필요하며 별도 후속 작업으로 분리된다. 상세는 `docs/tasks/kafka-trace-propagation/adr.md` 참조.

### ADR-018: Hibernate 6.x ENUM 매핑은 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한다
- **결정**: 모든 entity의 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다. 컬럼 길이는 명시하지 않고 Hibernate 기본값(VARCHAR(255))을 사용한다. 신규로 추가되는 entity의 `@Enumerated(EnumType.STRING)` 필드도 동일 패턴을 따른다.
- **배경**: Hibernate 6.x부터 MySQL dialect가 `@Enumerated(EnumType.STRING)`을 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑한다. MySQL ENUM은 INSERT 시 컬럼 생략 시 첫 번째 ENUM 값이 조용히 삽입되며, `ddl-auto: update`로 컬럼 추가 시 기존 row에 첫 번째 값이 자동 채워진다. VARCHAR였다면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM에서는 묻힌다. Hibernate 6.5 공식 마이그레이션 가이드는 `@JdbcTypeCode(SqlTypes.VARCHAR)` 또는 `@Column(columnDefinition = "varchar(N)")` 두 방식을 제시한다.
- **이유**: `@JdbcTypeCode`가 dialect-agnostic하고 선언적이며 `@Column(length=N)`과의 분리가 가능하다. `columnDefinition`은 raw SQL fragment를 박아 dialect 변경에 fragile하고 length 속성과 충돌한다. 또한 향후 native ENUM을 채택할 때 annotation 하나만 제거하면 되는 전환 비용이 낮다. 컬럼 길이를 명시하지 않는 이유는, enum 값은 개발자가 정의한 코드 상수만 저장되어 외부 입력 길이 제한 같은 보안/검증 의미가 없고, length를 명시하면 enum 추가 시 동기화 부담만 발생하기 때문이다.
- **트레이드오프**: JPA 표준에서 벗어나 Hibernate-specific annotation을 도입한다. 다만 entity 코드는 이미 Hibernate에 결합되어 추가 부담은 미미하다. 컬럼 길이 통제권은 약해지나 enum 특성상 통제 가치가 낮다.
- **한계**: Hibernate `ddl-auto: update`는 컬럼 타입 변경(ENUM → VARCHAR)을 보장하지 않는다. 본 코드 변경만으로는 운영 DB의 기존 ENUM 컬럼이 그대로 남을 가능성이 있다. 운영 DB ALTER는 Flyway 도입 시 일괄 마이그레이션 스크립트로 정리한다. ENUM 컬럼 생성 시점부터 본 fix 전까지 "첫 번째 enum 값이 조용히 삽입된" 의심 row 점검은 별도 후속 트랙이다.
- **참고**: Hibernate 6.5 Migration Guide, Hibernate Discourse "String Enum mapping for MySQL only". 상세는 `docs/tasks/hibernate-enum-jdbc-type-code/adr.md` 참조.
- **후속 (ADR-024, 2026-06-02)**: Flyway 도입으로 `ddl-auto`가 `validate`로 전환되어 *기존 row에 첫 번째 enum 값이 묻히는* 사고 경로(MySQL ddl-auto: update가 NOT NULL ENUM 컬럼 추가 시 발생)는 닫혔다. 그러나 본 결정은 (a) test 프로파일(H2 pure mode + ddl-auto: create-drop)과 prod/local(MySQL + Flyway varchar) 사이의 *INSERT 시 NOT NULL silent fill 행위 parity* 보장, (b) Hibernate dialect 변경 안전망의 두 역할로 코드 규칙으로 유지한다. integrationTest로 Hibernate SchemaValidator가 enum vs varchar의 sql type 차이를 strict 비교하지 않음(silent zone)을 확인했다 — 본 매핑이 빠지면 validate도 못 잡는 silent drift가 잠재한다. 테스트 지원 entity(`AsyncTestEntity` 등)도 동일 규칙을 따른다.

### ADR-019: 비동기/이벤트 경계 traceId 전파는 명시적 동봉 방식으로 구현한다
- **결정**: Spring Event 경계는 이벤트 객체에 traceId 필드를 동봉하고, Outbox 경계는 `tbl_outbox_event.trace_id` 컬럼에 저장한 뒤 relay 시 MDC로 복원한다. 두 경계 모두 publisher 시점의 MDC traceId를 명시적으로 전달한다. Outbox 스케줄러 자체에서는 traceId를 발급하지 않고, MDC에 유효한 traceId가 없거나 outbox.trace_id가 NULL이면 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback).
- **배경**: ADR-017(Kafka traceId 전파)로 Kafka 경계는 해결됐으나, `@TransactionalEventListener(AFTER_COMMIT)`과 Outbox relay 경계에서는 여전히 traceId가 단절되어 결제 승인 → outbox 발행 → kafka consume → 재고 복구 흐름과 주문 생성 → Redis 멱등성 캐시 흐름을 단일 traceId로 추적할 수 없었다. Spring Event는 (A) 이벤트 객체에 traceId 동봉, (B) `ApplicationEventMulticaster` wrapping을 비교했다. Outbox는 (A) 스케줄러 진입 시 신규 UUID 발급, (B) DB 컬럼에 원본 traceId 저장, (C) 현행 유지(Kafka 레벨 fallback만)를 비교했다.
- **이유**: Spring Event는 당시 사용처가 `OrderIdempotencyCacheEvent` 한 곳뿐이라 Multicaster wrapping은 한 군데에서만 쓰일 추상화로 과했다. `OrderIdempotencyCacheEvent` 사례는 `order-idempotency-cache-simplification` 에서 제거됨 (listener / event 자체 삭제). 현재 Spring Event `@TransactionalEventListener` 사용처 0건. Outbox `trace_id` 컬럼 결정은 그대로 유효. Outbox는 (A) 스케줄러 단위 발급 시 한 실행에서 여러 독립 거래가 같은 traceId를 공유해 의미가 희석되고, (C) 현행 유지 시 Kafka 레벨에서 새 UUID가 발급되어 원 HTTP 요청과 단절된다. (B) DB 컬럼 저장만이 원본 HTTP 요청의 traceId를 consumer까지 전파한다.
- **트레이드오프**: Outbox 스케줄러 자체 로그는 traceId가 없다(운영 통계 로그 성격이므로 허용). 기존 outbox 데이터 및 MDC에 유효한 traceId가 없는 케이스는 outbox.trace_id를 NULL로 저장하고 relay 시 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback). Spring Event 객체마다 traceId 필드를 추가하는 반복 작업이 향후 필요할 수 있으며, 이벤트가 5개 이상 늘어나는 시점에 Multicaster wrapping으로 재검토한다. DB 스키마 변경(`tbl_outbox_event.trace_id VARCHAR(64) NULL`)이 필요하나 nullable이고 기존 인덱스에 영향이 없어 무중단 적용 가능하다.
- **참고**: 상세는 `docs/tasks/event-outbox-trace-propagation/adr.md` 참조.

### ADR-020: 신규 도메인의 cross-aggregate 참조는 ID로 한다
- **결정**: 본 phase의 `cart` 도메인을 기점으로, 이후 신설되는 모든 도메인은 다른 aggregate를 `Long` ID로만 참조한다. `@ManyToOne`, `@JoinColumn`, cross-aggregate `@OneToOne` 사용을 금지한다. `cart`의 `CartItem`은 `memberId`, `productId`를 원시 `Long`으로 저장하며 다른 aggregate를 객체로 참조하지 않는다.
- **배경**: 기존 도메인은 `Order.member`, `OrderItem.product`, `Stock.product` 등 `@ManyToOne` 객체 참조를 광범위하게 사용한다. 그러나 application 계층은 대부분 `memberId`, `productId` 등 ID 기반으로 흐름을 다루고 있어 도메인 모델과 application 인터페이스 사이에 이중 표현이 발생한다. 이로 인해 N+1 회피와 fetch join 부담, 도메인 결합도 증가, 단위 테스트에서의 객체 그래프 구성 부담, DDD "다른 aggregate는 ID로만 참조" 원칙 위반 등 누적 부채가 있었다. 신설 도메인부터라도 기본값을 ID 참조로 두자는 결정이다.
- **결정 근거**: DDD 정통(Eric Evans, "Reference Other Aggregates Only By Identity") 원칙에 부합한다. (a) 다른 aggregate와의 결합도가 감소해 도메인 변경 영향 반경이 좁아진다. (b) JPA lifecycle 함정(detached entity, cascade, lazy proxy)을 피할 수 있다. (c) 단위 테스트가 원시 ID로 단순화되어 객체 그래프 setup 부담이 사라진다. (d) 향후 마이크로서비스 분리 시 aggregate 경계가 서비스 경계와 자연스럽게 정렬된다. cart 조회 시 `productRepository.findAllById(productIds)`로 명시적으로 Product를 한 번 더 조회해 응답을 조립하는 비용은 PK 기반 인덱스 조회라 무시 가능하다.
- **트레이드오프**: DB 참조 무결성을 FK 제약이 보장하지 않는다. 대신 application 흐름·UNIQUE 제약·삭제 순서 정책이 정합성을 책임진다. 기존 Order/Stock/StockHistory 등의 `@ManyToOne` 참조는 호환성 부담이 크고 본 phase 범위가 아니므로 마이그레이션하지 않고 별도 트랙으로 분리한다.
- **적용 범위**: 본 ADR 이후 신설되는 모든 cross-aggregate 참조에 적용한다. 같은 aggregate 내 root-child 관계(예: `Order ↔ OrderItem` 같이 동일 aggregate 안의 collection)는 본 정책 대상이 아니며 기존대로 객체 참조를 허용한다. 기존 cross-aggregate 객체 참조의 ID 참조로의 마이그레이션은 별도 작업으로 다룬다.
- **참고**: 상세는 `docs/tasks/cart/adr.md` 결정 2 참조.
- **후속 (stock-jpa-association-decouple, 2026-06-03)**: Stock·StockHistory aggregate 에 ADR-020 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@OneToOne`(`Stock.product`) / `@ManyToOne`(`StockHistory.stock`) 을 제거하고 `Long productId` / `Long stockId` 필드로 전환했다. DB schema (컬럼·FK) 변경 없음. 세부 결정 (응답 조립 외부 주입 패턴, schema 무변경 원칙) 은 `docs/tasks/stock-jpa-association-decouple/adr.md` 참조. 후속 트랙: `order-jpa-association-decouple`, `payment-jpa-association-decouple`.
- **후속 (order-jpa-association-decouple, 2026-06-03)**: Order / OrderItem aggregate 에 ADR-020 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@ManyToOne`(`Order.member` → `memberId: Long`, `OrderItem.product` → `productId: Long`) 을 제거하고 `Long` ID 필드로 전환했다. same-aggregate 관계(`Order.orderItems` / `OrderItem.order`)는 객체 참조 유지. DB schema (컬럼·FK) 변경 없음. fetch join 대체 원칙 (same-aggregate 유지 / cross-aggregate 제거) 과 사용처별 패턴 (PaymentReady 는 batch composition + 외부 주입, cancel/expiration 은 컬럼 직접 사용) 을 본 sub-PR 에서 처음 명문화했다. 세부 결정은 `docs/tasks/order-jpa-association-decouple/adr.md` 참조. 후속 트랙: `payment-jpa-association-decouple`.
- **후속 (payment-jpa-association-decouple, 2026-06-03)**: Payment aggregate 에 ADR-020 의 cross-aggregate ID 참조 원칙이 적용됐다. JPA `@OneToOne`(`Payment.order`) 을 제거하고 `Long orderId` 필드로 전환했다. `Payment.createCompleted` 정적 팩토리 시그니처를 `(Long orderId, int amount, ...)` 로 전환해 도메인의 외부 객체 의존을 0으로 만들었다. DB schema (컬럼·FK) 변경 없음. 세부 결정은 `docs/tasks/payment-jpa-association-decouple/adr.md` 참조. **ADR-020 후속 트랙 (Stock / Order / Payment) 완료.** 후속 DB FK 일괄 제거 (fk_stock_product_id, fk_stock_history_stock_id, fk_order_member_id, fk_order_item_product_id, fk_payment_order_id) 는 별도 issue 발행 예정.
- **후속 (cross-aggregate-fk-cleanup, 2026-06-03)**: ADR-020 후속 트랙 series 완전 종료. 단일 Flyway V4 migration (`V4__drop_cross_aggregate_fk_constraints.sql`) 으로 cross-aggregate FK 5건 (`fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id`) 을 일괄 제거했다. UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 과 same-aggregate FK (`fk_order_item_order_id`) 는 유지한다. 코드 + DB schema 정합성이 회복됐다 (코드 차원 cross-aggregate association 0건 + DB cross-aggregate FK 0건). 운영 DB 의 FK 제거 적용 절차는 별도 결정. 세부 결정은 `docs/tasks/cross-aggregate-fk-cleanup/adr.md` 참조.

### ADR-021: 응용 Service의 `@Transactional`은 method-level에만 부착한다
- **결정**: 응용 Service(`com.commerce.<domain>.application.*Service`)에 class-level `@Transactional` 부착을 금지한다. 모든 트랜잭션 경계는 method-level `@Transactional`로만 표현한다. retry loop를 포함하는 outer Service는 어노테이션 없이 두고, 트랜잭션 경계는 별도 Processor 빈의 method-level `@Transactional`이 책임진다(`OrderCreateProcessor` 패턴, 본 cart phase의 `AddCartItemProcessor`/`UpdateCartItemQuantityProcessor` 등).
- **배경**: 기존 코드베이스는 class-level `@Transactional(readOnly = true)` 기본 + method-level `@Transactional` 쓰기 메서드 override 패턴이 광범위하다(`OrderCreateService`, `OrderCancelService`, `AuthLoginService` 등). 본 패턴은 (a) 메서드의 트랜잭션 정책이 한눈에 안 들어와 class 선언으로 시선이 이동해야 하고, (b) 새 메서드를 추가하면서 method-level 어노테이션을 누락하면 의도와 다른 정책(`readOnly`)이 silent로 적용되며, (c) 코드 리뷰 시 누락 여부가 표면에 드러나지 않는다.
- **결정 근거**: method-level만 사용하면 (a) 모든 메서드의 트랜잭션 정책이 코드 표면에 명시되고, (b) 누락은 곧 "트랜잭션 없음"으로 즉시 드러나며, (c) 메서드별 정책 차이가 한눈에 비교 가능하다. class-level "기본값 + override" 구조가 주는 코드 줄 수 절약 가치보다 명시성·실수 방지 가치가 더 크다는 판단이다.
- **트레이드오프**: 메서드 수만큼 어노테이션이 반복된다. 다만 어노테이션이 곧 정책 명세 역할을 하므로 가독성 손실이라기보다 의도 표현이다. 조회 전용 Service에서도 `@Transactional(readOnly = true)`를 메서드마다 부착해야 한다.
- **적용 범위**: 본 ADR 이후 신설되는 응용 Service에 적용한다. 본 cart phase의 4개 Service(`AddCartItemService`, `GetMyCartService`, `UpdateCartItemQuantityService`, `RemoveCartItemService`)에 적용된다. 기존 도메인(Order/Stock/Auth 등)의 class-level `@Transactional` 마이그레이션은 본 ADR의 후속 트랙으로 분리한다.
- **Processor 패턴과의 관계**: retry/멱등 등 트랜잭션 외부에서 처리해야 할 흐름을 가진 Service는 어노테이션 없이 outer 역할만 담당하고, 실제 트랜잭션은 별도 Processor 빈에 method-level `@Transactional`로 둔다. retry attempt마다 빈 경계를 넘어가며 새 트랜잭션·새 persistence context가 시작되고, self-invocation 함정이 회피된다.

### ADR-022: 응용 계층은 영속화 호출을 명시적으로 표현한다
- **결정**: 응용 Service가 도메인 객체의 상태를 변경한 뒤 영속화가 필요한 경우, JPA dirty checking에 묵시적으로 기대지 않고 `repository.save(entity)`를 명시적으로 호출한다. managed entity의 `save()`는 JPA 내부에서 no-op이지만, 응용 코드 표면에 "이 시점에 저장 의도"를 드러내는 것이 본 ADR의 목적이다.
- **배경**: dirty checking은 JPA의 "트랜잭션 종료 시 자동 flush" 동작에 묵시적으로 의존한다. 응용 코드는 "수정만 호출"하지만, 코드 작성자는 머릿속에 "트랜잭션이 끝나면 자동 저장됨"이라는 ORM-specific 동작 모델을 전제로 깔고 작성해야 한다. 이로 인해 (a) 응용 계층의 사고 모델이 JPA에 묶이고, (b) DDD의 "domain은 상태 변경, application은 영속화 조율" 책임 분리가 코드 표면에 드러나지 않는다.
- **결정 근거**: `repository.save(entity)` 명시 호출은 (a) 응용 코드가 어떤 ORM/persistence 메커니즘이든 동일한 사고 모델을 유지하게 하고, (b) 영속화가 application의 명시적 책임이라는 DDD layer 분리를 코드 표면에 드러내며, (c) 코드 리뷰 시 "여기서 저장한다"라는 의도가 즉시 보인다. CLAUDE.md "비즈니스 로직은 Domain/application 계층" 원칙이 영속화 책임에도 적용된 형태다. import 수준의 의존도는 dirty checking과 같지만(둘 다 `Repository` port에만 의존), 인지적·표현적 의존도가 ORM-agnostic으로 떨어진다.
- **트레이드오프**: managed entity에 대한 `save()` 호출이 형식상 no-op이지만 코드 라인이 추가된다. 다만 이 라인은 곧 의도 명세이며, 향후 ORM 변경·JDBC 직접 사용 같은 시나리오에서도 코드 변경 부담을 낮춘다. 또한 같은 분기 안에서 신규 entity(transient)와 기존 entity(managed)를 모두 `save()`로 통일하면 분기 시각적 비대칭이 사라진다.
- **적용 범위**: 본 ADR 이후 신설되는 응용 Service에 적용한다. 본 cart phase의 `AddCartItemProcessor`, `UpdateCartItemQuantityProcessor`에 적용된다. 기존 도메인의 dirty checking 의존 코드 마이그레이션은 별도 트랙으로 분리한다.
- **새 entity insert와의 차이**: transient entity(`id == null`)의 `save()`는 JPA persist 경로라 호출이 없으면 INSERT가 일어나지 않는다. 본 ADR은 그 외 update 경로에도 동일하게 명시 호출하라는 정책이다. detached entity의 `save()`(merge)는 모든 필드를 덮어써 동시 갱신을 깨뜨리는 위험 경로이지만, 본 phase의 흐름에는 detached entity가 등장하지 않는다.

### ADR-023: multi-column unique constraint 대상 컬럼은 `@Column(length=...)`을 명시한다
- **결정**: multi-column `@UniqueConstraint`에 포함되는 String/Enum 컬럼은 `@Column(length=...)`을 명시한다. 합계 바이트가 InnoDB unique key 한도(3072 bytes)를 넘지 않도록 산정한다. 본 결정은 ADR-018("enum length 미명시")의 좁은 예외다. 함께 `hibernate.hbm2ddl.halt_on_error: true`를 `application-local.yml`에만 적용해 schema 회귀를 부팅 시점에 노출시킨다 (test/prod는 적용 제외).
- **배경**: `tbl_payment_attempt`의 4개 컬럼이 `VARCHAR(255)` 기본값으로 생성되어 utf8mb4 환경에서 4080 bytes를 차지, MySQL이 unique key 생성을 거부. Hibernate 기본 핸들러가 silent로 넘어가 schema에 unique가 없는 채 운영됨.
- **이유**: 옵션 A(대상 컬럼만 length 명시)가 ADR-018의 합리성을 일반 영역에서 유지하면서 본 사고만 좁게 해결한다. 옵션 B(전 컬럼 length 명시)는 ADR-018을 폐기해야 한다. `halt_on_error`를 test에 적용하지 않은 이유는 Testcontainer fresh MySQL 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`가 `IF EXISTS` 없이 실행되어 무해 실패가 발생하기 때문이며, test 환경의 회귀 감지는 `NaverPayServiceConcurrencyTest`의 `countAttempts == 1` 데이터 invariant로 대체한다.
- **트레이드오프**: ADR-018과 본 ADR의 좁은 예외가 공존한다. 신규 multi-column unique 도입 시 length를 계산해 명시해야 하는 인지 부담이 있다. `halt_on_error`는 local의 `ddl-auto: update` 전제에 묶이며, local ddl-auto 변경 시 함께 재검토해야 한다 (fragile dependency). 동시성 테스트의 race 발생 자체 가시화 단언(`anyMatch DataIntegrityViolationException`)은 환경 의존성으로 CI flake 위험이 있어 제거하고, `countAttempts == 1` + `assertRaceOrPaymentError` helper 조합으로 안전망을 검증한다. 동시성 테스트는 20 thread + 보상 흐름 수용을 위해 클래스 단위 HikariCP 설정(`maximum-pool-size=30`)을 명시한다.
- **한계**: 본 결정은 multi-column unique 대상 컬럼만 length를 명시한다. 같은 의미 컬럼이 entity별로 다른 length를 갖는 cross-entity 길이 불일치는 본 결정 범위 밖이며, 신규 entity 도입 시 동일 의미 컬럼은 같은 length로 맞추는 것을 가이드로 둔다 (이슈 #178).
- **참고**: 상세는 `docs/tasks/payment-attempt-unique-key-length/adr.md` 참조.
- **후속 (ADR-024, 2026-06-01)**: Flyway 도입으로 `ddl-auto`가 `validate`로 전환되어 Hibernate가 DDL을 실행하지 않게 되었다. `halt_on_error`의 발동 조건(Hibernate DDL 실행 실패)이 사라져 `application-local.yml`에서 제거한다. 스키마 변경 실패 차단 책임은 Flyway가 가져간다 (마이그레이션 SQL 실패 시 Flyway 자체가 부팅 차단).

### ADR-024: DB 스키마 마이그레이션 도구로 Flyway 도입 (ddl-auto: validate 전환)

#### 결정
- 의존성으로 `flyway-core` + `flyway-mysql` 추가 (Spring Boot 3.5 BOM 관리)
- 운영/로컬/integrationTest는 `spring.jpa.hibernate.ddl-auto: validate` + Flyway 활성
- test(H2)는 H2 + create-drop 유지, Flyway 비활성
- 기존 스키마는 현 엔티티 기준 `src/main/resources/db/migration/V1__init.sql`로 단일 베이스라인, baseline-on-migrate 비활성
- Spring Batch 메타테이블은 Flyway 관리 대상에서 제외하고 기존 `initialize-schema: always` 유지
- 운영 안전망으로 `spring.flyway.clean-disabled: true` 명시

#### 배경

**그동안 도입을 미뤄온 입장.** DB는 단일 MySQL 하나뿐이고 다중 DB 운영 계획도 없다. 이 상황에서 Flyway는 "지금 당장 필요하지 않은 운영 복잡성"이라고 판단해 왔다. JPA `ddl-auto: update`로 충분하다는 입장을 유지했고, `application-prod.yml`의 주석 "추후 DB 마이그레이션 학습 후 validate로 변경할 것"은 이 입장의 흔적이다. 도입의 일반적 정당성(스키마 변경 이력, 환경 간 일관성, 위험한 변경 통제)은 이미 알고 있지만, 비용 대비 우선순위가 낮다고 봐 왔다.

**입장을 뒤집은 두 사고.**

**사고 1 — Hibernate 6 dialect 변경에 의한 ENUM silent drift (이슈 #142, ADR-018, 2026-05-26).** Hibernate 6.x부터 `@Enumerated(STRING)`이 MySQL native `ENUM` 타입으로 매핑되도록 동작이 바뀌었다. MySQL ENUM은 NOT NULL 제약을 첫 번째 값 자동 삽입으로 회피한다. `ddl-auto: update`로 NOT NULL ENUM 컬럼이 추가될 때 기존 row가 의도하지 않은 첫 번째 값(예: `OutboxEventStatus.PENDING`)으로 묻혔다. ADR-018 회고 인용: "Hibernate dialect 변경은 '조용한' 결함을 만든다. ENUM 매핑은 NOT NULL 위반을 첫 번째 값 자동 삽입으로 회피하므로, 코드 레벨에서는 정상으로 보이지만 데이터 레벨에서는 의도하지 않은 값이 묻힌다." 코드 변경(`@JdbcTypeCode(SqlTypes.VARCHAR)` 적용)으로 신규 컬럼은 막을 수 있지만, **기존 운영 DB의 ENUM 컬럼이 VARCHAR로 자동 ALTER된다는 보장이 없다.** ADR-018 회고: "본 코드 변경만으로 운영 DB의 기존 ENUM 컬럼이 자동 ALTER되지 않을 가능성이 있다. Hibernate `ddl-auto: update`는 새 컬럼 추가는 자동 수행하지만, 기존 컬럼의 타입 변경(ENUM → VARCHAR)은 보장하지 않기 때문이다."

**사고 2 — multi-column unique constraint silent 미적용 (이슈 #176, PR #179, ADR-023, 2026-05-31).** `NaverPayServiceConcurrencyTest` 8개 중 7개가 `IncorrectResultSizeDataAccessException: 2 results were returned`로 실패. 초기 가설은 race window였으나 단일 테스트 실행 + Hibernate DDL 로그 dump 결과 `Specified key was too long; max key length is 3072 bytes` WARN이 발견. 근본 원인은 `tbl_payment_attempt`의 4-column unique key `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`의 컬럼들이 `@Column(length=...)` 미지정 → 모두 VARCHAR(255) → utf8mb4 환경에서 4080 bytes로 InnoDB 한도 3072 bytes 초과. MySQL이 unique key 생성을 거부했지만 **Hibernate 기본 핸들러는 WARN으로만 로그하고 부팅을 계속해서**, 스키마에 unique가 빠진 채 운영돼 왔다. 동시성 테스트의 우연한 타이밍에서만 발견. payment-attempt-unique-key-length 회고 인용: "`@Column(length=...)`을 명시하지 않으면 multi-column unique constraint에서 silent하게 schema 생성이 실패할 수 있다. ddl-auto의 schema 에러는 기본적으로 silent 처리된다. `halt_on_error`가 없으면 운영 schema 정합성이 깨진 채 계속 작동할 수 있다."

**두 사고의 공통 패턴.** 둘 다 *코드는 정상으로 보이지만 실제 DB schema가 silent하게 어긋난 상태*가 문제의 본질이다. `ddl-auto: update`는 (a) 컬럼 타입 변경 같은 일부 변경을 누락하고, (b) schema 변경 실패를 WARN으로만 처리한다. 단일 DB 운영이라는 단순함은 도입 미루기의 근거였지만, **단일 DB라도 schema drift는 발생한다**는 것을 두 사고가 같은 패턴으로 보여줬다. drift 원인이 외부 시스템 분기가 아니라 Hibernate dialect 변경 / silent fail이라는 코드 내부 요인이라는 점이 결정적이다.

**시점 선택.** 운영 DB 미가동이라는 시간적 우위가 있다. V1 단일 스크립트로 출발하면 baseline-on-migrate, 운영 dump → baseline 작성 → checksum 검증 같은 복잡한 도입 절차가 모두 불필요하다.

#### 이유 (대안 비교)
- **대안 A: `ddl-auto: update` 유지** — 두 사고에서 드러난 silent drift 문제를 그대로 안고 가는 선택. 운영 가동 후 같은 패턴이 또 발생할 가능성이 코드 변화량에 비례한다.
- **대안 B: `ddl-auto: validate`만 적용하고 마이그레이션은 손으로 SQL 관리** — validate는 컬럼/타입 검사를 한다. 사고 1(ENUM 타입 변경 누락)은 부팅 실패로 잡힌다. 그러나 적용 순서/이력 관리가 코드 외부로 새고, 환경 간 일관성은 사람 기억에 의존한다. 사고 2 유형(unique 누락)은 validate가 unique constraint를 검사하지 않으므로 여전히 못 잡는다 — 코드 규칙(ADR-023)에 의존해야 한다.
- **대안 C: Liquibase** — XML/YAML/JSON DSL 추상화. MySQL/JPA 단일 스택의 본 프로젝트에서 추상화 가치 제한적이고, SQL을 그대로 다루는 게 디버깅/리뷰에 유리.
- **선택: Flyway** — SQL을 그대로 버전 관리, Spring Boot Auto-configuration 내장. ddl-auto의 silent drift 패턴을 (a) validate로 컬럼/타입 누락 가시화, (b) 명시적 스크립트로 변경 의도 코드화, (c) `flyway_schema_history`로 환경 간 일관성 추적, 세 축으로 해소한다.

#### 운영/테스트 적용 방식
- 로컬/운영: `validate`로 전환하여 엔티티-스키마 불일치를 부팅 실패로 즉시 가시화.
- test(H2): 그대로. 단위/슬라이스 테스트의 부팅 속도 자산이고 Flyway 스크립트는 MySQL 문법이라 H2에 직접 적용 불가.
- integrationTest(Testcontainers MySQL): Flyway 활성. 컨테이너 싱글톤 재사용 + 컨텍스트 캐싱 + `deleteAllInBatch()` 격리 모델이 자연스럽게 맞물린다. `application-test.yml`의 `flyway.enabled: false`는 `TestcontainersSupport`의 dynamic property로 `true` override해 무효화.
- Spring Batch 메타테이블: Flyway 관리 제외. Batch 자체 `initialize-schema` 유지. Spring Batch 버전업 시 마이그레이션 책임이 프로젝트로 옮겨오는 비용 회피.

#### 트레이드오프
- **운영 복잡성 증가 — 인정한 비용**: 도입 미뤄온 가장 큰 이유였던 "운영 복잡성"이 실제로 늘어난다. 엔티티 변경 시 마이그레이션 스크립트를 같은 PR에서 함께 작성해야 하고, 로컬에서 엔티티만 수정하고 부팅하면 실패한다. 두 사고에서 드러난 silent drift 비용보다 이 복잡성 비용이 작다고 판단해 수용한다.
- **validate가 모든 drift를 잡지는 못한다**: validate는 컬럼/타입 누락은 잡지만 unique constraint 누락, 인덱스 누락은 검사하지 않는다. 사고 2 유형은 Flyway 도입 후에도 ADR-023 같은 코드 규칙으로 1차 방어한다. Flyway는 "변경 이력이 명시적이라 리뷰에서 잡힐 가능성을 높인다"는 간접 효과로만 기여.
- **validate가 sql type 차이도 strict 비교하지 않는다 — silent zone**: 본 PR review 단계에서 Codex의 `AsyncTestEntity` 지적을 계기로 integrationTest를 직접 돌려 확인했다. Hibernate SchemaValidator는 enum 매핑(native ENUM) vs 스키마(varchar) 같은 sql type 차이를 strict 비교하지 않는다. 즉 ADR-018의 dialect-driven silent drift는 validate 도입 후에도 *부팅 실패로 가시화되지 않는다*. `@JdbcTypeCode(SqlTypes.VARCHAR)` 명시(ADR-018)는 Flyway 도입 후에도 (a) test(H2) ↔ prod(MySQL) INSERT 행위 parity 보장, (b) dialect 변경 안전망으로 코드 규칙으로 유지된다. ADR-018 후속 메모 참조.
- **Flyway 10 추적 부담**: Flyway 10에서 DB별 모듈 분리(`flyway-mysql`), 일부 deprecated API, license 정책 변경. 메이저 업그레이드 시 release note 확인 책임이 추가된다.
- **test 프로파일 회귀 미검증**: H2 + Flyway 비활성이라 마이그레이션 스크립트 자체의 회귀는 integrationTest에서만 검증된다. CI의 통합 잡이 `integrationTest`를 명시 호출하므로 회귀가 PR 단계에서 검증된다 (CI 잡 분리 적용 후).

#### 연계 ADR / 이슈
- ADR-018 (Hibernate 6.x ENUM 매핑 `@JdbcTypeCode(SqlTypes.VARCHAR)`) — 사고 1 직접 연계
- ADR-023 (multi-column unique constraint 컬럼 길이 명시) — 사고 2 직접 연계, Flyway 도입 후에도 유효한 코드 규칙
- 이슈 #142, #176, PR #179 — 두 사고의 원자료

### ADR-025: enum 컬럼의 DB CHECK 제약을 두지 않는다
- **결정**: `@Enumerated(STRING) + @JdbcTypeCode(SqlTypes.VARCHAR)`로 매핑되는 enum 컬럼에 대해 Hibernate가 자동 생성하는 `CHECK (column in (...))` 제약을 V1__init.sql과 이후 마이그레이션에서 모두 제거한다. enum 값의 유효성 보장은 애플리케이션 layer(Java enum 타입 시스템 + `@Enumerated(STRING)` Hibernate 매핑)에 위임한다.
- **배경**: ADR-024(Flyway 도입) PR review 단계에서 외부 조언으로 enum CHECK 제약의 silent mismatch 함정이 제기되었다. 시나리오는 다음과 같다 — Java enum에 새 값을 추가하면 `ddl-auto: validate`는 "varchar 맞네" 하고 통과시킨다. 그러나 그 새 값으로 INSERT하는 순간 DB의 CHECK 제약에 걸려 런타임에 실패한다. 컴파일·기동 다 통과한 변경이 실제 저장에서 터지는, 정확히 ADR-018·ADR-023과 같은 결의 silent drift 패턴이다.
- **이유**:
  - **이중 안전망의 실용 가치 작음**: 본 프로젝트는 단일 백엔드, JPA 단일 INSERT 경로, 외부 시스템의 직접 INSERT 경로 없음. `@Enumerated(STRING)`이 application layer에서 invalid enum을 차단하므로 DB CHECK는 실질적으로 발동될 일이 없는 layer.
  - **enum 진화 마찰**: 결제 fail_code(16개), 주문 status(5개), 이벤트 type 등 enum은 도메인이 자라면 종종 추가된다. CHECK를 유지하면 enum 추가마다 마이그레이션 스크립트가 필요하다.
  - **silent mismatch 위험**: validate가 통과시킨 변경이 운영에서 INSERT 실패로 발견되는 디버깅 비용이 크다. 보통 운영 알람 → 롤백 흐름.
  - **Hibernate가 자동 생성한다는 점**: 두는 결정도 안 두는 결정도 의식적이어야 하는데 자동이라 의식 안 됨. 본 결정은 그 자동 동작을 명시적으로 우회하는 의미.
  - **대안 비교**:
    - 옵션 A (CHECK 유지): 이중 안전망. enum 추가마다 V 스크립트 부담 + silent mismatch 위험 그대로.
    - 옵션 B (V1에서만 제거, 향후 자동 생성 그대로): 다음 dump 시 회귀. 운영 부담.
    - **옵션 C (본 결정 — 의식적 제거 + 향후 자동 생성 차단 검토)**: 마찰 최소화 + silent drift 차단.
- **운영 적용**:
  - 본 PR(ADR-024)에서 V1__init.sql의 모든 `*_chk_N CHECK (... in (...))` 제약을 제거했다.
  - 향후 ddl-auto: create 기반 dump 시 Hibernate가 CHECK를 또 자동 생성한다. 의식적으로 제거가 필요하다.
  - Hibernate 차원의 CHECK 자동 생성 차단 방법(설정 또는 엔티티 어노테이션 차원)은 후속 task에서 검토한다.
- **트레이드오프**:
  - **외부 시스템이 같은 DB에 INSERT하는 시나리오가 추가되면 본 결정을 재검토해야 한다**. 마이크로서비스 분리, BI/ETL 도구 직접 접근, 운영자 raw SQL 수정 같은 경로가 일상화되면 application layer만으로는 안전망이 부족할 수 있다.
  - 본 결정이 적용되는 영역은 enum CHECK 한정이다. `NOT NULL`, `UNIQUE`, `FOREIGN KEY` 같은 다른 제약은 본 결정 대상이 아니며 각자의 도메인 의도에 따라 유지한다.
- **연계**: ADR-018(ENUM → VARCHAR 매핑), ADR-024(Flyway 도입의 silent drift 트레이드오프 섹션)와 같은 결의 결정.

### ADR-026: 결제 도메인 재설계 — Order↔Payment 경계 분리 + RESERVE 별도 거주지 (B안)

- **결정 요약**:
  1. **두 테이블 분리** — `tbl_payment_reservation` (상태 `{RESERVED, USED, EXPIRED}`, 결제창 준비물, RESERVED → USED/EXPIRED 한 번 전이) + `tbl_payment` (타입 `{APPROVE, CANCEL}`, PG 사건 append-only)
  2. **merchantPayKey 책임 이동** — Order.merchantPayKey / assignMerchantPayKey / findByMerchantPayKey* 전량 제거. merchantPayKey 발급·저장 책임이 PaymentReservation 으로 이동
  3. **NULL 트릭 partial unique** — MySQL InnoDB 의 partial unique index 미지원 → `uk_payment_approved_order_key (approved_order_key NULL)` + `uk_payment_reservation_reserved_key (reserved_key NULL)` 로 대체. 조건 불충족 행은 NULL 로 두어 unique 제외. 두 컬럼 모두 도메인 메서드(`succeed`, `markUsed`, `markExpired`) 안에서 상태 변경과 *같은 UPDATE* 에 묶어 캡슐화 강제. 만료/무효 예약은 reserve 진입 시 `markExpired` 로 reservedKey 를 회수해 재예약 허용
  4. **완료 판단 = EXISTS** — `(성공 APPROVE 존재) AND (무효화 성공 CANCEL 부재)`. 마지막 행 기반 판단 금지. 현재는 `existsApproveSucceeded(merchantPayKey)` 로 단순 구현
  5. **UNKNOWN 마킹** — PG 호출 timeout / DB 반영 실패 시 `Payment.markUnknown` 흔적 보존. UNKNOWN 행 있는 주문에 reserve/approve 차단 (`PAYMENT_RESULT_PENDING` 409). 해소는 후속 task `PaymentReconciliationService`
  6. **API rename** — `POST /payments/ready` → `POST /payments/reserve` (frontend 미개발이라 호환 깨도 무방)
  7. **멱등 redirect 흡수** — 같은 merchantPayKey 의 redirect 중복은 차단이 아닌 *기존 결제 결과 200 응답* 으로 흡수
  8. **reserve 재사용 정책** — `(status=RESERVED ∧ expiresAt>now ∧ provider 일치 ∧ memberId 일치 ∧ amount 일치)` 조건으로 기존 Reservation 재사용. 만료·amount mismatch 시 새 Reservation 발급 (amount UPDATE 금지)
  9. **외부 PG 호출 경계 유지** — PG 호출은 트랜잭션 밖, payment+order DB 쓰기는 한 트랜잭션 안 (ADR-008 정책 유지)
  10. **Flyway V6 마이그레이션** — `tbl_payment` (구 성공 1:1) DROP → `tbl_payment_attempt` → `tbl_payment` RENAME + 컬럼 정리 + `tbl_payment_reservation` CREATE + `tbl_order` merchant_pay_key 관련 DROP
- **상세**: `docs/tasks/payment-order-redesign/adr.md` (ADR-1 ~ ADR-10)
- **연계 ADR**: ADR-010 (후속, amount mismatch → 새 Reservation), ADR-014 (후속, `existsApproveSucceeded` 구현 갱신), ADR-015 (후속, `compensateDuplicateApproval` 추가)
