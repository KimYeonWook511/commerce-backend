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

## 도메인별 주요 서비스

| 도메인 | application 서비스 |
|--------|--------------------|
| auth | `AuthSignUpService`, `AuthLoginService`, `AuthTokenReissueService`, `AuthTokenIssueService`, `TokenAuthenticationService` |
| member | `MemberRegistrationService`, `MemberQueryService` |
| product | `ProductQueryService`, `AdminProductService` |
| stock | `StockInventoryService`, `AdminStockService`, `StockConcurrencyService` |
| order | `OrderCreateService`, `OrderCancelService`, `OrderQueryService`, `OrderExpirationService`, `OrderConcurrencyService` |
| payment | `PaymentReadyService`, `PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `PaymentApprovalCompensationService` |
| naverpay | `NaverPayApprovalService` |
| outbox/stock | `StockRestoreOutboxCreateService`, `StockRestoreOutboxRelayService`, `StockRestoreOutboxConsumeService` |

---

## 데이터 흐름

```
# 상품 공개 조회
ProductController → ProductQueryService → ProductRepository, StockRepository

# 관리자 상품 관리
AdminProductController → AdminProductService → ProductRepository

# 관리자 재고 관리
AdminStockController → AdminStockService → StockRepository, StockHistoryRepository

# 주문 생성
OrderController → OrderCreateService
  → StockInventoryService (재고 차감)
  → PaymentReadyService (결제 준비)

# 결제 승인 (네이버페이)
NaverPayController → NaverPayApprovalService
  → NaverPayGateway (PG 호출, 응답 코드 매핑)
  → PaymentApprovalService (결제 완료 반영, 보상 가능 여부 판단)
  → PaymentApprovalAttemptService (승인 시도 이력 기록)
  → PaymentCancellationAttemptService (취소 시도 이력 기록, 보상 흐름)
  → PaymentApprovalCompensationService (보상 dispatcher — catch 분기 시, this::pgCancel 콜백 주입)

# 주문 만료 배치
OrderExpirationBatchConfig (Spring Batch)
  → OrderExpirationService (만료 대상 처리)
  → OrderCancelService (주문 취소)
  → StockRestoreOutboxCreateService (복구 이벤트 생성)
  → StockRestoreOutboxScheduler
  → StockRestoreOutboxRelayService → Kafka
  → StockRestoreKafkaEventConsumer
  → StockRestoreOutboxConsumeService (재고 복구)
```

---

## 도메인 책임

- `auth`는 인증 유스케이스의 owner다. 비밀번호 검증, JWT 발급·검증, refresh token 저장 흐름을 담당한다. 회원 생성·조회는 `member.application`에 위임한다.
- `security`는 HTTP 요청 인증/인가 adapter다. `JwtAuthenticationFilter`가 `TokenAuthenticationService`를 호출해 인증 결과를 `AuthenticationContext`에 저장하고, `AuthorizationInterceptor`와 `AuthenticatedMemberIdArgumentResolver`가 이를 사용한다.
- `product` 도메인은 공개 상품 조회와 관리자 상품 등록·수정·soft delete를 제공한다. 상품 목록은 `ON_SALE` 또는 `SOLD_OUT` 상태, `deletedAt IS NULL`, `createdAt DESC` 기준으로 반환한다. 상품 상세는 상품 정보와 현재 재고 수량을 조합한다.
- `stock` 도메인은 상품별 현재 재고, 주문 경로의 재고 차감·복구, 관리자 초기 재고 생성, 관리자 수동 조정, 재고 변경 이력을 담당한다. `Product : Stock = 1:1` 관계를 유지한다.
- `order` 도메인은 주문 생성·취소·만료를 담당한다. 주문 생성은 멱등 키로 중복 요청을 방어한다. 만료 처리는 Spring Batch로 스케줄링한다.
- `payment` core는 결제 준비·완료 반영·시도 이력 관리를 담당한다. `naverpay`는 provider 서브패키지로, PG 호출과 내부 결제 상태 반영을 분리한다.
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

다음 7개 도메인의 14개 컴포넌트에 유스케이스 완료 시 INFO 로그가 추가되어 있다.

| 도메인 | 컴포넌트 |
|--------|---------|
| Order | `OrderCreateService`, `OrderCreateProcessor`, `OrderCancelService`, `OrderConcurrencyService`, `OrderExpirationService` |
| Outbox | `StockRestoreOutboxCreateService` |
| Payment | `PaymentApprovalService`, `PaymentReadyService` |
| Stock | `StockInventoryService`, `AdminStockService` |
| Auth | `AuthLoginService`, `AuthSignUpService` |
| Member | `MemberRegistrationService` |
| Product | `AdminProductService` |

단순 조회·위임 서비스(`OrderQueryService`, `MemberQueryService`, `ProductQueryService`, `TokenAuthenticationService`, `OutboxService`)는 도메인 상태 전환이 없으므로 INFO 로그를 두지 않는다.

---

## HTTP 요청 처리 Filter

| Filter | 클래스 | Order |
|--------|--------|-------|
| **TraceIdFilter** | `com.commerce.common.log.filter.TraceIdFilter` | `Ordered.HIGHEST_PRECEDENCE + 10` |
| **AccessLogFilter** | `com.commerce.common.log.filter.AccessLogFilter` | `Ordered.HIGHEST_PRECEDENCE + 20` |
| JwtAuthenticationFilter | `com.commerce.security.filter.JwtAuthenticationFilter` | `Ordered.LOWEST_PRECEDENCE` |

`TraceIdFilter`는 `FilterRegistrationBean`으로 등록된다. 모든 요청(`/*`)에 UUID traceId를 발급해 MDC `traceId` 키에 push하고, 응답 헤더 `X-Trace-Id`에 추가한다. `JwtAuthenticationFilter`보다 먼저 실행되므로 인증 실패 로그에도 traceId가 포함된다.

`AccessLogFilter`는 `FilterRegistrationBean`(`AccessLogFilterConfig`)으로 등록된다. 모든 요청(`/*`)에 대해 요청 시작/종료 INFO 로그 2건(method, path, status, latency)을 남긴다. traceId/memberId는 직접 부착하지 않고 MDC를 통해 logback 패턴이 자동 부착한다. `JwtAuthenticationFilter` 이전에 실행되므로 미인증 요청에도 액세스 로그가 남으며, memberId는 인증 이후 채워지므로 시작 로그 시점에는 빈 값일 수 있다.

---

## 예외 처리 정책

예외 처리 정책(find-first, 안전망 계층, 보상 catch 2차 예외 처리)은 `docs/exception-strategy.md`를 참고한다.

로깅 컨벤션(레이어별 로그 책임, 레벨 기준, 예외 로깅 표준, 민감 정보 마스킹 등)은 `docs/logging-conventions.md`를 참고한다.

환경별 appender·encoder·rolling·마스킹 등 로깅 인프라 설정의 단일 진실의 원천은 `src/main/resources/logback-spring.xml`이다. `application-{local,prod,test}.yml`에는 `logging:` 섹션을 두지 않는다.

---

## 저장소 및 인프라 의존성

- 영속 데이터는 MySQL에 저장한다.
- 토큰과 주문 멱등 키는 Redis에 저장한다.
- 재고 복구 이벤트는 Outbox 모듈을 중심으로 Kafka로 전달한다.
- 외부 결제는 네이버페이 PG 연동 모듈(`payment/naverpay`)을 통해 처리한다.

---

## 인프라 경계

- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
