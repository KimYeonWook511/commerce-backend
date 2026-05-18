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
Application 과 Adapter 어디서도 Spring DAO 예외(`DuplicateKeyException`, `DataIntegrityViolationException` 등) 를 catch 하지 않으며, 정상 흐름은 사전 `find` 로 처리하고 DB 무결성 위반은 `GlobalExceptionHandler` 안전망에 위임한다.

### 본질 흐름 — find-first 패턴

```
DB find → 없으면 insert → 충돌 시 500
```

- 사전 `find` 가 정상 멱등/중복 시나리오를 흡수한다 (도메인 4xx 또는 정상 200 흐름).
- `insert` 시점의 unique 위반은 race window 한정이며, 안전망 500 으로 코드 버그처럼 가시화한다.
- NOT NULL / FK / CHECK 위반도 동일하게 안전망 500 으로 전파된다.

### 정책 적용 조건과 한계

본 정책은 다음 두 조건이 모두 만족될 때 유효하다.

1. **트랜잭션이 짧다** — race window 가 좁아 안전망 500 의 발생률이 무시 가능한 수준이다.
2. **정상 흐름에서 동시 충돌 확률이 낮다** — 사용자 입력 식별자(email, merchantPayKey) 나 idempotency key 기반 unique.

조건을 만족하는 현재 적용 대상은 `MemberRegistrationService`, `PaymentApprovalService`, `PaymentAttemptService`, `OrderCreateService`, `StockRestoreOutboxConsumeService` 5곳이다.

**비적용 상황**: 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) 에는 본 정책을 적용하지 않고 **try-save-catch** 패턴이 더 적합하다. 향후 새 unique 제약을 도입할 때 위 두 조건으로 패턴을 선택하며, try-save-catch 를 선택하더라도 인프라 예외 타입(`DuplicateKeyException` 등) 에 직접 의존하지 않도록 처리한다.

### GlobalExceptionHandler 안전망 계층

```
DataAccessException (부모 핸들러, COMMON-500-2)
├─ DataIntegrityViolationException (COMMON-500-1)            ← unique / NOT NULL / FK / CHECK
│  └─ DuplicateKeyException                                   ← 자동 흡수
└─ OptimisticLockingFailureException (COMMON-409-1)           ← 409 (낙관적 락 정상 시나리오)
```

- Spring `@ExceptionHandler` 는 가장 구체적인 타입을 먼저 매칭한다. 두 구체 핸들러(`DataIntegrityViolationException`, `OptimisticLockingFailureException`) 가 우선 매칭되고, 부모 `DataAccessException` 핸들러는 그 외 DAO 예외(`BadSqlGrammarException`, `CannotAcquireLockException`, `DataAccessResourceFailureException` 등) 만 받는다.
- `DuplicateKeyException` 은 `DataIntegrityViolationException` 의 하위라 별도 등록 없이 자동 흡수된다.
- `DataIntegrityViolationException` 핸들러는 unique race window 와 NOT NULL/FK/CHECK 위반을 모두 잡아 500 + stack trace 로그(`COMMON-500-1`) 를 남긴다.
- `DataAccessException` 부모 핸들러는 DAO 카테고리 fallback 으로 500 + stack trace + `COMMON-500-2` 를 남겨 운영 모니터링에서 일반 `Exception` fallback 과 구분 가능하게 한다.
- `OptimisticLockingFailureException` 핸들러는 낙관적 락 충돌(정상 시나리오) 을 409 로 유지한다.

### JpaConfig 빈 등록 목적

`JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈은 안전망 핸들러가 unique 위반을 `DuplicateKeyException` 으로 정확히 분류해 로깅하도록 한다. 코드가 직접 catch 하지는 않지만, 운영 환경(JPA + MySQL) 에서 unique 위반과 그 외 무결성 위반을 로그 레벨로 구분하기 위해 빈 등록은 유지된다.

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
