# 아키텍처

## 패키지 구조

```
src/main/java/com/commerce/
├── common/            # 공통 설정, 예외, JPA base entity, Kafka, 유틸
├── security/          # JWT 인증 필터, 인가 인터셉터, 인증 컨텍스트, argument resolver
├── auth/              # 인증 유스케이스 (회원가입·로그인·토큰 재발급), JWT 구현, refresh token
├── member/            # 회원 등록·조회
├── product/           # 상품 공개 조회, 관리자 상품 관리
├── stock/             # 재고 차감·복구·관리자 조정, 변경 이력
├── order/             # 주문 생성·취소·만료 배치
├── cart/              # 장바구니 항목 추가·변경·삭제·조회, 주문 시 항목 제거 연동
├── payment/           # 결제 준비·승인·시도 이력
│   └── naverpay/      # 네이버페이 PG 연동 (Gateway, Client, Controller)
└── outbox/            # Outbox 이벤트 저장·발행
    └── stock/         # 재고 복구 이벤트 생성·릴레이·소비
```

각 도메인은 아래 레이어 구조를 따른다.

```
<domain>/
├── application/     # 유스케이스 서비스, command, result
│   └── port/        # 외부 시스템 연동 인터페이스 (Redis, 결제 PG, 이메일 등)
├── domain/          # 엔티티, 도메인 로직, repository port
├── infrastructure/  # JpaXxxRepository, XxxRepositoryAdapter, 외부 연동 구현체
├── presentation/    # Controller, request DTO
└── exception/       # 도메인 예외
```

---

## 계층 구조

```
presentation → application → domain ← infrastructure
```

- `presentation`: 요청 수신, 입력 검증, service 위임, 응답 반환
- `application`: 유스케이스 흐름 조율, 트랜잭션 경계. 외부 시스템 연동이 필요할 때는 `application/port/` 인터페이스(Port)로만 의존한다.
- `domain`: 상태 변경 규칙, 비즈니스 로직, repository port 정의
- `infrastructure`: Spring Data JPA repository, repository adapter, 외부 시스템 구현. Port 인터페이스의 실제 구현체가 여기에 위치한다.

`domain`은 `infrastructure`를 직접 참조하지 않는다.  
`infrastructure`의 `XxxRepositoryAdapter`가 `domain.repository.XxxRepository`를 구현하고 `JpaXxxRepository`에 위임한다.

### Repository와 Port 구분

| | Repository | Port |
|---|---|---|
| 위치 | `domain/repository/` | `application/port/` |
| 소유 이유 | 도메인 모델의 영속성 관심사 | Service가 필요한 외부 기능 |
| 관심사 | 도메인 모델 저장·조회 | 외부 시스템 연동 추상화 |
| 예시 | `OrderRepository` | `IdempotencyStore`, `PaymentGateway` |

Port 인터페이스 설계 원칙:
- 처음에는 하나의 인터페이스로 시작한다
- 특정 Service에만 필요한 메서드가 생기는 순간, 기존 인터페이스를 상속 확장하여 분리한다
- 이름은 의도가 드러나는 방향으로 자유롭게 선택한다 (`IdempotencyStore`, `EmailSender` 등, `Port` suffix 불필요)

---

## 서비스 네이밍 원칙

하나의 Service 클래스는 하나의 유스케이스 행위만 담당한다.

- 네이밍은 `{행위}{도메인}Service` 형식을 따른다 (`CreateOrderService`, `CancelOrderService`, `GetOrderService`)
- 구조는 UseCase 패턴과 동일하며, 현재는 Spring 생태계 관습과의 일관성을 위해 `Service` suffix를 사용한다 (ADR-006 참고)
- 처음부터 지나치게 잘게 나누지 않되, 트랜잭션 흐름·변경 이유·호출 맥락이 달라지는 시점에 분리한다

---

## 도메인별 서비스

각 도메인의 application 계층은 위 "서비스 네이밍 원칙"에 따라 유스케이스 단위 Service로 구성된다. 도메인별 서비스의 정확한 전체 목록은 코드(`com.commerce.<domain>.application`)가 단일 출처이며, 특정 기능의 구현 맥락은 해당 task의 `docs/tasks/<task>/architecture.md`를 참조한다. 본 문서는 개별 서비스를 전수 나열하지 않는다.

---

## 데이터 흐름

아래 흐름은 컴포넌트 간 협력과 순서, 트랜잭션 경계를 보여주는 개념도다. 등장하는 클래스·메서드명은 현재 구현 기준 예시이며, 정확한 시그니처는 코드가 단일 출처다. 흐름이 표현하려는 것은 "어떤 책임이 어떤 순서로, 어떤 경계 안팎에서 협력하는가"이다.

```
# 상품 공개 조회
ProductController → ProductQueryService → ProductRepository, StockRepository

# 관리자 상품 관리
AdminProductController → AdminProductService → ProductRepository

# 관리자 재고 관리
AdminStockController → AdminStockService → StockRepository, StockHistoryRepository

# 장바구니 담기 / 조회 / 수량 변경 / 항목 삭제
CartController → AddCartItemService → CartItemRepository (UPSERT: 있으면 수량 합산, 없으면 생성)
CartController → GetMyCartService → CartItemRepository, ProductRepository (최신 가격 재조립, 구매 불가 마킹)
CartController → UpdateCartItemQuantityService → CartItemRepository (수량 절대값 변경)
CartController → RemoveCartItemService → CartItemRepository (항목 삭제)

# 주문 생성
OrderController → OrderCreateService
  → StockInventoryService (재고 차감)
  → ReservePaymentService (결제 준비 — PaymentReservation 생성/재사용)
  → CartItemRemover port (주문된 productId만 cart에서 제거)

# 결제 reserve (구 ready)
ReservePaymentController → ReservePaymentService
  → OrderRepository (주문 확인 + 결제 가능 상태 검증)
  → PaymentRepository (UNKNOWN 차단 검사)
  → PaymentReservationRepository (재사용 가능 Reservation 탐색 또는 신규 RESERVED INSERT)
  → uk_payment_reservation_reserved_key UNIQUE 가 동시 중복 요청(따닥) 차단

# 결제 승인 (네이버페이)
NaverPayController → NaverPayApprovalService
  → PaymentReservationRepository ((memberId, merchantPayKey) 로 Reservation 역조회 — Order 안 거침; 남의/없는 키는 PAYMENT_RESERVATION_NOT_FOUND 로 존재 비노출, ADR-038)
  → PaymentRepository (UNKNOWN 차단 검사)
  → USED Reservation 발견 시: 같은 pgPaymentId 는 멱등 응답 200, 다른 pgPaymentId 는 PAYMENT_RESERVATION_ALREADY_USED 차단
  → 이미 성공(APPROVE·SUCCEEDED) 결제 있는 주문 진입 차단 — PG 호출 전 PAYMENT_DUPLICATE (approved_order_key 존재 검사, ADR-037)
  → [트랜잭션 안] Reservation 사용 처리 (@Version 낙관적 락 — 동시 이중 use 진 쪽은 saveUsed 에서 PAYMENT_RESERVATION_ALREADY_USED 로 PG 호출 전 차단, ADR-036) + Payment(APPROVE, REQUESTED) INSERT
  → [트랜잭션 밖] PG Gateway (approve API 호출)
  → PaymentApprovalRecordService (승인 시도 상태 반영)
  → 승인 완료 반영 (saveApproved — uk_payment_approved_order_key 위반은 adapter 가 PAYMENT_DUPLICATE 도메인 예외로 번역, ADR-033)
  → 완료 여부 판단 (성공 APPROVE 행 존재 기반, 보상 가능성 판단)
  → PaymentCancellationService (취소 시도 이력 기록, 보상 흐름)
  → PaymentApprovalCompensationService (보상 dispatcher — 이중결제 fail-first 단일 보상; 정상 승인 후 transient 기록 실패는 보상 없이 전파·REQUESTED 유지로 reconcile 에 위임, ADR-032)

# 주문 만료 배치
Spring Batch (주문 만료)
  → OrderExpirationService (만료 대상 처리)
  → OrderCancelService (주문 취소)
  → StockRestoreOutboxCreateService (복구 이벤트 생성)
  → Outbox 스케줄러 → relay → Kafka
  → Kafka consumer → StockRestoreOutboxConsumeService (재고 복구)
```

---

## 도메인 책임

- `auth`는 인증 유스케이스의 owner다. 비밀번호 검증, JWT 발급·검증, refresh token 저장 흐름을 담당한다. 회원 생성·조회는 `member.application`에 위임한다.
- `security`는 HTTP 요청 인증/인가 adapter다. `JwtAuthenticationFilter`가 `TokenAuthenticationService`를 호출해 인증 결과를 `AuthenticationContext`에 저장하고, `AuthorizationInterceptor`와 `AuthenticatedMemberIdArgumentResolver`가 이를 사용한다.
- `product` 도메인은 공개 상품 조회와 관리자 상품 등록·수정·soft delete를 제공한다. 상품 목록은 `ON_SALE` 또는 `SOLD_OUT` 상태, `deletedAt IS NULL`, `createdAt DESC` 기준으로 반환한다. 상품 상세는 상품 정보와 현재 재고 수량을 조합한다.
- `stock` 도메인은 상품별 현재 재고, 주문 경로의 재고 차감·복구, 관리자 초기 재고 생성, 관리자 수동 조정, 재고 변경 이력을 담당한다. `Product : Stock = 1:1` 관계를 유지하며, `Stock` 은 `productId: Long` 으로 Product 를 ID 참조한다(ADR-020). `StockHistory` 는 Stock 과 별도 aggregate 로 `stockId: Long` 으로 Stock 을 ID 참조한다. 응답 조립 시 Application 계층이 path 컨텍스트(productId)를 외부 주입한다. 후속 트랙(`order-jpa-association-decouple`, `payment-jpa-association-decouple`)에서 Order·Payment aggregate 에도 동일 원칙이 적용됐다. DB FK 일괄 제거는 `cross-aggregate-fk-cleanup` 트랙에서 완료됐다.
- `order` 도메인은 주문 생성·취소·만료를 담당한다. 주문 생성은 멱등 키로 중복 요청을 방어한다. 만료 처리는 Spring Batch로 스케줄링한다. 주문 생성 트랜잭션 내에서 `CartItemRemover` port를 통해 주문된 항목만 cart에서 제거한다. `Order` 는 `memberId: Long` 으로 Member 를, `OrderItem` 은 `productId: Long` 으로 Product 를 ID 참조한다(ADR-020). same-aggregate 관계(`Order.orderItems`, `OrderItem.order`)는 객체 참조를 유지한다. cross-aggregate fetch join 은 제거하고 사용처별로 batch composition(필요한 Product를 ID 목록으로 한 번에 조회) 또는 컬럼 직접 사용(cancel/expiration)으로 대체한다. 세부 결정은 `docs/tasks/order-jpa-association-decouple/adr.md` 참조. 후속 트랙: `payment-jpa-association-decouple`. DB FK 일괄 제거는 `cross-aggregate-fk-cleanup` 트랙에서 완료됐다.
- `cart` 도메인은 회원의 장바구니 항목 추가(UPSERT)·조회(최신 가격 재조립, 구매 불가 마킹)·수량 변경·삭제를 담당한다. 다른 aggregate(Member, Product)는 `Long` ID로만 참조한다(ADR-020). 주문-cart 연동은 `CartItemRemover` port(order 소유)를 cart 쪽 adapter가 구현하는 방식으로 의존 방향을 보존한다.
- `payment` core는 결제 예약(reserve)·승인·시도 이력 관리를 담당한다. `naverpay`는 provider 서브패키지로, PG 호출과 내부 결제 상태 반영을 분리한다. 도메인은 두 엔티티로 분리됐다 (ADR-026): `PaymentReservation` (결제창 준비물, `RESERVED → USED` 전이) + `Payment` (PG 사건 append-only, type ∈ `{APPROVE, CANCEL}`). Order 는 결제 식별자를 모른다 — merchantPayKey 발급·저장 책임이 `PaymentReservation` 으로 이동했다. `Payment` 는 `orderId: Long` 으로 Order 를, `merchantPayKey` 로 `PaymentReservation` 을 값 참조한다. 결제 완료 판단은 *성공한 APPROVE 행 존재(EXISTS)* 기반이다 (ADR-014, ADR-026). 보상 정책(중복 승인 보상 포함)은 payment.application의 보상 서비스가 소유하며, 이중결제(uk 위반) 탐지는 adapter 가 도메인 예외(PAYMENT_DUPLICATE)로 번역하고 application 이 fail-first 로 보상한다 (ADR-015, ADR-026, ADR-033). 정상 승인 후 transient 기록 실패는 보상 없이 전파하고 approve 를 REQUESTED 로 두어 reconcile 에 위임한다 (완료 우선, ADR-032). UNKNOWN 마킹 — PG 호출 결과 불명 시 흔적을 보존하고, UNKNOWN 행 있는 주문은 reserve/approve 를 차단한다 (`PAYMENT_RESULT_PENDING` 409). ADR-020 후속 트랙 series (Stock / Order / Payment / FK cleanup) 완전 종료. `cross-aggregate-fk-cleanup` 트랙에서 cross-aggregate FK 5건을 Flyway V4 migration 으로 일괄 제거해 코드 + DB schema 정합성이 회복됐다. 운영 DB 의 FK 제거 적용은 별도 결정. 결제 도메인 재설계 세부 결정은 `docs/tasks/payment-order-redesign/` 참조 (ADR-026).
- `outbox` 도메인은 재고 복구 이벤트를 Outbox 패턴으로 처리한다. 이벤트 생성, Kafka 릴레이, 소비 책임을 별도 서비스로 분리한다.

---

## Application 계층 로깅

Application Service는 유스케이스 완료 시점에 도메인 이벤트 INFO 로그를 남긴다. 메시지는 한국어 본문 + 영어 식별자 필드 + SLF4J placeholder `{}` 형식을 따른다.

```java
log.info("주문 생성 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}", ...);
```

- **Controller**: 로그 없음 (얇은 위임 레이어)
- **Domain**: 로그 없음 (순수 도메인 보호, SLF4J 의존 금지)
- **Infrastructure**: 외부 시스템 경계 요청/소비 시작·완료 (INFO), 실패·retry (WARN/ERROR)

로깅 컨벤션 전체(레벨 기준, 레이어별 책임, 예외 로깅 표준, 민감 정보 마스킹, 메시지 패턴 등)의 단일 진실의 원천은 `docs/logging-conventions.md`다.

### 도메인 이벤트 INFO 로그 적용 범위

도메인 상태를 전환하는 유스케이스 Service는 완료 시점에 INFO 로그를 남긴다. 단순 조회·위임 서비스(상태 전환 없음)는 INFO 로그를 두지 않는다. 어떤 컴포넌트에 적용돼 있는지의 정확한 목록은 코드가 단일 출처이며, 적용 기준·메시지 패턴의 단일 진실의 원천은 `docs/logging-conventions.md`다.

---

## Application 계층 트랜잭션·영속화 컨벤션

Application Service의 트랜잭션 경계와 영속화 호출 방식은 ADR-021(method-level `@Transactional`)과 ADR-022(`repository.save(entity)` 명시 호출)를 따른다. 정책 본문·근거·트레이드오프는 ADR을 단일 출처로 한다.

---

## HTTP 요청 처리 Filter

application Filter는 `FilterRegistrationBean`으로 명시 등록하고 `Ordered` 기반 순서를 가진다. `@Component` 자동 등록은 쓰지 않는다(암묵적 등록 순서 의존과 `LOWEST_PRECEDENCE` 충돌 회피).

| 순서 | Filter | 역할 |
|---|---|---|
| 1 | `TraceIdFilter` | 모든 요청에 UUID traceId 발급 → MDC push, 응답 헤더 `X-Trace-Id` 부착 |
| 2 | `AccessLogFilter` | 모든 요청에 시작/종료 접근 로그 |
| 3 | `JwtAuthenticationFilter` | 인증 필요 경로의 Bearer 토큰 검증 → 인증 컨텍스트 저장 |

순서가 구조적으로 중요하다: `TraceIdFilter`·`AccessLogFilter`가 `JwtAuthenticationFilter`보다 바깥(먼저)에 있어, 인증 실패(401) 요청에도 traceId와 접근 로그가 남는다.

인증된 요청의 `memberId`는 MDC로 전파되어 이후 도메인 로그와 접근 로그에 자동 포함된다. 각 Filter는 자신이 push한 MDC 키만 제거한다(`MDC.clear()` 금지 — 다른 Filter의 키를 함께 날림). 구체 전파·정리 메커니즘은 `docs/logging-conventions.md`와 `docs/tasks/memberid-mdc-propagation/architecture.md`가 단일 출처다.

### 비동기 경계 traceId 전파

HTTP 요청 traceId는 스레드 로컬 MDC라 비동기 경계에서 자동 전파되지 않으므로, 경계마다 명시적으로 전달한다(ADR-017, ADR-019):

- **Kafka**: producer가 traceId를 헤더 `X-Trace-Id`에 부착하고 consumer가 MDC로 복원한다.
- **Outbox**: 원본 traceId를 `tbl_outbox_event.trace_id`에 저장한 뒤 relay 시 MDC로 복원해 Kafka 헤더로 전파한다.
- **`@TransactionalEventListener`**: 이벤트 객체에 traceId를 동봉한다. 현재 사용처 0건(`order-idempotency-cache-simplification`에서 제거, 향후 도입 시 갱신).

각 경계의 의사코드 수준 흐름과 운영 정책(스케줄러 로그 제외 범위 등)은 `docs/logging-conventions.md`와 ADR-017/019, 관련 task architecture가 출처다.

---

## 예외 처리 정책

예외 처리 정책(find-first, 안전망 계층, 보상 catch 2차 예외 처리)은 `docs/exception-strategy.md`를 참고한다.

로깅 컨벤션(레이어별 로그 책임, 레벨 기준, 예외 로깅 표준, 민감 정보 마스킹 등)은 `docs/logging-conventions.md`를 참고한다.

환경별 appender·encoder·rolling·마스킹 등 로깅 인프라 설정의 단일 진실의 원천은 `src/main/resources/logback-spring.xml`이다. `application-{local,prod,test}.yml`에는 `logging:` 섹션을 두지 않는다.

---

## 저장소 및 인프라 의존성

- 영속 데이터는 MySQL에 저장한다.
- 토큰은 Redis에 저장한다. 주문 멱등성은 Redis 에 in-flight 마커만 저장 (TTL 60초). 멱등성 진실은 `tbl_order.(member_id, idempotency_key)` unique 제약. Redis 장애 시 infra adapter 가 `OrderIdempotencyStoreUnavailableException` 으로 변환, application 이 catch 해 DB unique 안전망 경로로 fallback 진행 (단독 요청 정상 응답 가능).
- 재고 복구 이벤트는 Outbox 모듈을 중심으로 Kafka로 전달한다.
- 외부 결제는 네이버페이 PG 연동 모듈(`payment/naverpay`)을 통해 처리한다.

---

## 인프라 경계

- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
