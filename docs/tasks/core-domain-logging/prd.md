# 태스크 PRD

## 태스크명

- `core-domain-logging`

## 배경

- Epic "운영용 로깅 체계 도입"(#133)의 P3 작업이다. P0(#127 로깅 컨벤션), P1(#128 logback 설정), P2(#129 TraceIdFilter, #140 MDC `memberId` 통일)이 완료되어 모든 HTTP 요청에 `traceId`/`memberId`가 MDC에 push되고 JSON 로그에 자동 부착되는 인프라가 준비된 상태다.
- 그러나 application 계층의 비즈니스 이벤트 INFO 로그가 비어 있어, 장애 발생 시 "어느 요청이 어느 단계에서 무엇을 했는가"를 추적할 수 없다.
- 이슈 #130에서 27개 application Service 중 9개에만 `@Slf4j`가 적용된 현황을 확인했다(잔여 18개). 이번 작업은 그 중 도메인 이벤트가 의미 있는 13개 Service + 신규 주문 생성을 담당하는 `OrderCreateProcessor`(Component) 총 14개 컴포넌트를 대상으로 한다.

## 목표

- application 계층 14개 컴포넌트에 도메인 이벤트 INFO 로그를 추가하여, traceId·memberId가 박힌 로그 위에 비즈니스 의미를 입힌다.
- 운영자가 grep 가능한 일관된 메시지 패턴(`{명사형} {상태/동사} <필드>={값}`)으로 주문/결제/재고/회원/상품 이벤트를 추적할 수 있게 한다.
- `docs/logging-conventions.md`(§3 레이어 정책, §7 메시지 패턴)의 정책을 코드에 실제로 반영한다.

## 범위

### 포함 범위

- 14개 컴포넌트에 `@Slf4j` 부착 + INFO 로그 추가
  - Order: `OrderCreateService`, `OrderCreateProcessor`, `OrderCancelService`, `OrderConcurrencyService`, `OrderExpirationService`
  - Outbox: `StockRestoreOutboxCreateService`
  - Payment: `PaymentApprovalService`, `PaymentReadyService`
  - Stock: `StockInventoryService`, `AdminStockService`
  - Auth: `AuthLoginService`, `AuthSignUpService`
  - Member: `MemberRegistrationService`
  - Product: `AdminProductService`
- 루트 `docs/architecture.md` Application 계층 로깅 절 보강

### 제외 범위

- 단순 조회/위임 5개 서비스(`OrderQueryService`, `MemberQueryService`, `ProductQueryService`, `TokenAuthenticationService`, `OutboxService`): 컨벤션 §3 "유스케이스 시작·완료" 정신에 어긋나거나 매 요청 호출되어 노이즈가 큼
- 이미 `@Slf4j`가 적용된 9개 서비스: 메시지 보강 별도 필요 없음
- DEBUG 로그 추가: 외부 호출/SQL 디버깅 영역으로 별도
- WARN/ERROR 보강: 컨벤션 §4에 따라 `GlobalExceptionHandler`가 일괄 처리, 보상 catch는 이미 적용된 서비스(`PaymentApprovalAttemptService`, `PaymentApprovalCompensationService`) 외 신규 패턴 없음
- 비동기·이벤트 경계 traceId 전파(`@Async`, Kafka consumer): Epic 후속 작업
- 로그 수집 인프라(ELK, Loki 등): #132 백로그

## 주요 시나리오

- 사용자가 주문을 생성하면 `OrderCreateProcessor.execute()`에서 `주문 생성 orderId=... memberId=... itemCount=...` INFO 로그가 한 줄 남는다.
- 같은 idempotencyKey로 재요청하면 `OrderCreateService`에서 `주문 멱등 응답 orderId=... memberId=...` INFO 로그가 남는다(신규 주문은 생성되지 않음).
- 결제 승인 콜백이 들어오면 `결제 승인 완료 merchantPayKey=... pgPaymentId=... orderId=...` 또는 멱등 흡수 시 `결제 승인 멱등 흡수 merchantPayKey=... pgPaymentId=...` 로그가 남는다.
- 운영자가 admin API로 재고를 증가시키면 `재고 운영 증가 productId=... quantity=... reason=... adminMemberId=... newTotal=...` 로그가 남아 책임 추적이 가능하다.
- 회원이 가입을 완료하면 같은 흐름에서 `회원 등록 완료 memberId=...` (도메인 레이어) + `회원 가입 성공 memberId=...` (유스케이스 레이어) 로그가 순차적으로 남는다.

## 요구사항

- 14개 컴포넌트에 `@Slf4j` 부착
- 각 컴포넌트의 유스케이스 메서드 성공 직후 INFO 로그 1회 추가(분기가 있는 경우 분기별 별도 메시지)
- 메시지는 한국어 본문 + 영어 필드명, SLF4J placeholder `{}` 사용
- 핵심 식별자(`orderId`, `memberId`, `productId` 등)는 모든 INFO 로그에 포함
- `OrderConcurrencyService` 8개 메서드는 공통 헬퍼에서 `strategy` 필드로 통일 메시지
- `OrderCreateService` 멱등 흡수 2분기, `PaymentApprovalService.completeApprovedPayment()` 멱등 분기는 신규와 별개 메시지

## INFO 이벤트 사전 시그니처

```java
// Order
// OrderCreateService — 멱등 흡수 (Redis hit / DB hit 각각 source 구분)
log.info("주문 멱등 응답 orderId={} memberId={} source=redis idempotencyKey={}", orderId, memberId, idempotencyKey);
log.info("주문 멱등 응답 orderId={} memberId={} source=db idempotencyKey={}", orderId, memberId, idempotencyKey);
log.info("주문 생성 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.info("주문 취소 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.info("주문 생성 orderId={} memberId={} itemCount={} strategy={}", orderId, memberId, itemCount, strategy);
// strategy ∈ { without-lock, synchronized, synchronized-tx, reentrant-tx,
//              optimistic, pessimistic, pessimistic-ordered, pessimistic-batch }
log.info("주문 만료 orderId={} itemCount={}", orderId, itemCount);

// Outbox
log.info("재고 복구 Outbox 발행 orderId={} itemCount={}", orderId, itemCount);

// Payment
log.info("결제 준비 완료 merchantPayKey={} orderId={} memberId={} amount={}", merchantPayKey, orderId, memberId, totalPayAmount);
log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}", merchantPayKey, provider, pgPaymentId, orderId);
// 멱등 흡수 — provider, orderId 포함 (리뷰 반영)
log.info("결제 승인 멱등 흡수 merchantPayKey={} provider={} pgPaymentId={} orderId={}", merchantPayKey, provider, pgPaymentId, orderId);

// Stock — StockInventoryService 로그는 상위 트랜잭션 롤백 시 잔류 문제로 제거 (리뷰 반영)
// AdminStockService (어드민 직접 조작 — 독립 트랜잭션 진입점)
log.info("재고 초기 설정 productId={} quantity={} reason={} adminMemberId={}", productId, quantity, reason, adminMemberId);
log.info("재고 운영 증가 productId={} quantity={} reason={} adminMemberId={} newTotal={}", productId, quantity, reason, adminMemberId, stock.getQuantity());
log.info("재고 운영 감소 productId={} quantity={} reason={} adminMemberId={} newTotal={}", productId, quantity, reason, adminMemberId, stock.getQuantity());

// Auth + Member
log.info("로그인 성공 memberId={}", memberId);
log.info("회원 가입 성공 memberId={}", memberId);           // AuthSignUpService — 유스케이스 완료
log.info("회원 등록 완료 memberId={}", memberId);           // MemberRegistrationService — 도메인 entity 영속화

// Product
log.info("상품 생성 productId={} name={}", productId, name);
log.info("상품 수정 productId={}", productId);
log.info("상품 삭제 productId={}", productId);
```

## 제약사항

- `docs/logging-conventions.md` 정책 준수(§2 레벨, §3 레이어, §5 마스킹, §7 메시지)
- `docs/logging-conventions.md` 수정 금지 — 단일 진실의 원천
- application Service에 `log.error()` 추가 금지(보상 catch 1차 예외 제외)
- 이메일·비밀번호·토큰 평문 로깅 금지 — 사용자 식별은 `memberId`로 통일
- 단순 조회/위임 5개 서비스에 `@Slf4j` 부착 금지 — dead code + 컨벤션 §3 정합성 위반
- 기존 비즈니스 로직·테스트 변경 금지 — 이번 작업은 로그 추가만
