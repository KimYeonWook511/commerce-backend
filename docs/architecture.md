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
- `application`: 유스케이스 흐름 조율, 트랜잭션 경계
- `domain`: 상태 변경 규칙, 비즈니스 로직, repository port 정의
- `infrastructure`: Spring Data JPA repository, repository adapter, 외부 시스템 구현

`domain`은 `infrastructure`를 직접 참조하지 않는다.  
`infrastructure`의 `XxxRepositoryAdapter`가 `domain.repository.XxxRepository`를 구현하고 `JpaXxxRepository`에 위임한다.

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

## 저장소 및 인프라 의존성

- 영속 데이터는 MySQL에 저장한다.
- 토큰과 주문 멱등 키는 Redis에 저장한다.
- 재고 복구 이벤트는 Outbox 모듈을 중심으로 Kafka로 전달한다.
- 외부 결제는 네이버페이 PG 연동 모듈(`payment/naverpay`)을 통해 처리한다.

---

## 인프라 경계

- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
