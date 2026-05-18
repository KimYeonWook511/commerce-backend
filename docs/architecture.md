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
| payment | `PaymentReadyService`, `PaymentApprovalService`, `PaymentAttemptService` |
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
  → PaymentApprovalService (결제 완료 반영)
  → PaymentAttemptService (시도 이력 기록)

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

## 예외 처리 전략

Infrastructure 레벨 예외는 Application Layer를 넘어가지 않는다.  
Application은 `DuplicateKeyException`(unique 위반)만 좁게 catch하여 도메인 의미에 맞게 처리하고, 나머지 무결성 위반은 `GlobalExceptionHandler` 안전망에 위임한다.

### 3계층 책임 분리

| 위반 종류 | Spring 예외 타입 | Application 처리 | 최종 응답 |
|---|---|---|---|
| **Unique** | `DuplicateKeyException` | 좁게 catch → 도메인 의미에 맞게 처리 | 도메인 4xx 또는 정상 흐름 |
| **NOT NULL / FK / CHECK** | `DataIntegrityViolationException` (unique 제외) | **catch 안 함** → 그대로 전파 | 안전망 **500** + ERROR 로그 |

### Unique 위반의 두 종류

| 종류 | 예시 | 대응 |
|---|---|---|
| **비즈니스 unique** | email, idempotency_key, merchantPayKey, eventId | catch → 도메인 의미에 맞게 처리 |
| **기술적 unique** (시스템 생성 ID) | orderNumber(ULID) | catch 안 함 → 안전망 (충돌 = 코드 버그) |

### 두 처리 모드

- **모드 A (도메인 예외 변환)**: catch → 도메인 예외 throw (e.g. `MemberException(DUPLICATE_EMAIL)`)
- **모드 B (멱등 흡수)**: catch → 기존 엔티티 재조회 후 반환. 재조회 실패 시 rethrow → 안전망 500

### Unique 종류를 코드에서 분리하는 방법

Spring의 `DuplicateKeyException`은 어느 unique 제약을 위반했는지 표준 메서드를 제공하지 않는다. 다음 세 가지 케이스로 자연스럽게 해결한다.

**케이스 1 — unique 하나뿐**: 분기 불필요. catch되면 그 unique 의미로 확정.
- `Member.email`, `PaymentAttempt(paymentId, type)`, `ProcessedEvent(eventId, consumerType)`

**케이스 2 — unique 여러 개지만 의미 통일**: 어느 unique가 터졌든 도메인 응답이 같음.
- `Payment`의 `merchantPayKey` / `order_id` / `pgPaymentId` 모두 `PAYMENT_DUPLICATE`로 통일

**케이스 3 — unique 여러 개고 의미가 다름**: fallback 재조회 시도 결과로 분리.
- `Order`의 `(member_id, idempotency_key)`(비즈니스) vs `orderNumber`(기술적 ULID)
- 비즈니스 키로 재조회 성공 → 멱등 흡수
- 비즈니스 키로 재조회 실패 → 다른 unique 위반 또는 데이터 소멸 = 코드 버그 → rethrow → 안전망 500

### GlobalExceptionHandler 역할

- `DataIntegrityViolationException` 핸들러는 **안전망**으로만 존재. 정상 흐름에선 도달하지 않음.
- 도달했다면 application catch 누락 = 코드 버그.
- 응답: **500**, 로그: ERROR + stack trace 포함.
- `DuplicateKeyException` 전용 핸들러는 **신설하지 않음** (unique도 application에서 다 처리).
- `OptimisticLockingFailureException` 핸들러는 **변경 없음** (낙관적 락 = 정상 시나리오 → 409 유지).

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
