# 태스크 아키텍처

## 개요

이번 태스크는 application 계층 14개 컴포넌트에 도메인 이벤트 INFO 로그를 추가한다. 비즈니스 로직과 의존성은 손대지 않고, 각 유스케이스 메서드 성공 직후에 SLF4J 로거 호출만 삽입하는 횡단 관심사 작업이다.

P2에서 `TraceIdFilter`가 push한 `traceId`와 `JwtAuthenticationFilter`가 push한 `memberId`가 MDC에 이미 박혀 있어, 본 작업으로 추가되는 INFO 로그는 자동으로 traceId·memberId가 부착된다. logback 설정(`logback-spring.xml`)은 변경하지 않는다.

## 변경 대상

### Order 도메인 (`src/main/java/com/commerce/order/application/`)
- `OrderCreateService.java` — 멱등 흡수 2분기(Redis complete hit / DB existing hit)에서 `주문 멱등 응답` INFO
- `OrderCreateProcessor.java` — `execute()` 끝에서 `주문 생성` INFO (신규 주문 생성 진입점)
- `OrderCancelService.java` — `cancelOrder()` 끝에서 `주문 취소` INFO
- `OrderConcurrencyService.java` — 공통 헬퍼 `createOrderWithStockDecrease()`와 `createOrderWithPessimisticLockBatch()`에서 `주문 생성 ... strategy={}` INFO. 진입 메서드 8개는 strategy 라벨만 전달
- `OrderExpirationService.java` — `expireOrder()`에서 `주문 만료` INFO

### Outbox 도메인 (`src/main/java/com/commerce/outbox/stock/application/`)
- `StockRestoreOutboxCreateService.java` — `createOutboxEvent()` 끝에서 `재고 복구 Outbox 발행` INFO

### Payment 도메인 (`src/main/java/com/commerce/payment/application/`)
- `PaymentApprovalService.java` — 신규 완료와 멱등 흡수 분기 분리. `결제 승인 완료`와 `결제 승인 멱등 흡수` 별도 메시지
- `PaymentReadyService.java` — `readyPayment()` 끝에서 `결제 준비 완료` INFO

### Stock 도메인 (`src/main/java/com/commerce/stock/application/`)
- `StockInventoryService.java` — `decrease/increase` 후 `stock.getQuantity()`로 `remaining` 노출, `decreaseBatch`는 `productCount`만 노출
- `AdminStockService.java` — `createInitialStock/increaseByAdmin/decreaseByAdmin` 각 메서드 끝에서 `adminMemberId`, `reason` 포함 INFO

### Auth 도메인 (`src/main/java/com/commerce/auth/application/`)
- `AuthLoginService.java` — `login()` 끝, `tokenIssueService.issue()` 호출 직후에 `로그인 성공` INFO
- `AuthSignUpService.java` — `signUp()` 끝, 토큰 발급 완료 직후에 `회원 가입 성공` INFO (memberId만)

### Member 도메인 (`src/main/java/com/commerce/member/application/`)
- `MemberRegistrationService.java` — `register()` 끝, `memberRepository.save()` 직후에 `회원 등록 완료` INFO

### Product 도메인 (`src/main/java/com/commerce/product/application/`)
- `AdminProductService.java` — `createProduct/updateProduct/deleteProduct` 각 메서드 끝에서 INFO

### 변경 없는 파일
- `src/main/resources/logback-spring.xml` — MDC 패턴 이미 구성됨
- `docs/logging-conventions.md` — 단일 진실의 원천, 수정 금지
- 단순 조회/위임 5개 서비스(`OrderQueryService`, `MemberQueryService`, `ProductQueryService`, `TokenAuthenticationService`, `OutboxService`) — 컨벤션 §3 정합성 + dead code 방지

## 설계 방향

### 로그 위치 원칙

컨벤션 §3을 따른다.
- **Application Service**: 유스케이스 시작·완료의 도메인 이벤트 INFO. 본 작업의 주 대상.
- **Domain entity**: 로그 없음. `Stock`, `Order`, `Payment` 등은 SLF4J 의존하지 않는다.
- **Controller**: 로그 없음. 얇은 위임 레이어.
- **Filter**: P2에서 traceId/memberId MDC push 완료, P3에서는 손대지 않음.

### 신규/멱등 분기 처리

같은 유스케이스 진입점이라도 도메인 상태 전환 여부에 따라 메시지를 나눈다.

**OrderCreateService**:
- 신규 생성: `OrderCreateProcessor.execute()` 끝 → `주문 생성 ...`
- Redis complete hit 또는 DB existing hit (멱등 흡수): `OrderCreateService`의 두 분기 → `주문 멱등 응답 ...`

**PaymentApprovalService.completeApprovedPayment()**:
- 신규 완료(`completedPayment == null`): `결제 승인 완료 ...`
- 멱등 흡수(`completedPayment != null`): `결제 승인 멱등 흡수 ...`

### Member 도메인의 이중 로그

`AuthSignUpService.signUp()`은 현재 `MemberRegistrationService.register()`의 유일한 호출자다. 두 곳 모두 INFO 로그를 둔다.
- `MemberRegistrationService.register()` 끝 → `회원 등록 완료 memberId={}` (도메인 entity 영속화 이벤트)
- `AuthSignUpService.signUp()` 끝 → `회원 가입 성공 memberId={}` (유스케이스 완료 이벤트)

도메인 레이어와 유스케이스 레이어를 분리해 향후 admin 등록 진입점이 추가될 때도 도메인 이벤트가 누락되지 않게 한다.

### OrderConcurrencyService strategy 라벨

8개 메서드는 모두 공통 헬퍼 `createOrderWithStockDecrease(command, BiConsumer<Long, Integer> stockDecrease)` 또는 별도 경로 `createOrderWithPessimisticLockBatch`로 분기된다. 각 진입 메서드는 strategy 문자열 라벨을 헬퍼에 전달하고, 헬퍼가 INFO 로그를 출력한다. 라벨 8개:
- `without-lock` (`createOrderWithoutLock`)
- `synchronized` (`createOrderWithSynchronized`)
- `synchronized-tx` (`createOrderWithSynchronizedAndTransaction`)
- `reentrant-tx` (`createOrderWithReentrantLockAndTransaction`)
- `optimistic` (`createOrderWithOptimisticLock`)
- `pessimistic` (`createOrderWithPessimisticLock`)
- `pessimistic-ordered` (`createOrderWithPessimisticLockOrdered`)
- `pessimistic-batch` (`createOrderWithPessimisticLockBatch`)

### 민감 정보 마스킹 (컨벤션 §5)

- 사용자 식별은 `memberId`로 통일. 이메일·비밀번호·토큰 평문 금지.
- `AuthSignUpService.signUp()` 성공 시점은 memberId가 발급된 후이므로 이메일 마스킹 없이 memberId만 사용.
- `AuthLoginService.login()` 성공도 동일.
- 로그인 실패(memberId 없음) 시의 이메일 마스킹은 `GlobalExceptionHandler`의 WARN 영역 — 본 작업 범위 밖.

## 데이터 흐름

```
HTTP Request
  ↓ TraceIdFilter (HIGHEST_PRECEDENCE + 10) — traceId MDC push (이미 완료, P2)
  ↓ JwtAuthenticationFilter — memberId MDC push (이미 완료)
  ↓ DispatcherServlet → Controller → Application Service
       ├─ 비즈니스 로직 실행 (이번 작업은 무변경)
       └─ ★ log.info(...) — 본 작업이 추가하는 도메인 이벤트
  ↓ HTTP Response
```

JSON 로그 한 줄 예:
```json
{
  "timestamp": "2026-05-23T10:15:30.123Z",
  "level": "INFO",
  "logger": "com.commerce.order.application.OrderCreateProcessor",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "memberId": "42",
  "message": "주문 생성 orderId=1234 memberId=42 itemCount=3"
}
```

## 예외 및 실패 처리

- application Service는 일상 예외(4xx)를 catch하지 않는다. `GlobalExceptionHandler`가 일괄 분류·로깅(§4)
- 보상 catch의 1·2차 예외 처리는 이미 적용된 서비스(`PaymentApprovalCompensationService`, `PaymentApprovalAttemptService`)에서 패턴 확립됨. 본 작업으로 신규 보상 catch는 추가하지 않음
- `OptimisticLockingFailureException`은 `GlobalExceptionHandler`에서 WARN으로 처리됨(§4) — 본 작업 범위 밖

## 테스트 포인트

- 14개 컴포넌트가 모두 `@Slf4j` 적용된 코드로 컴파일됨
- INFO 로그가 사전 시그니처와 정확히 일치 (메시지 본문, 필드 순서, 식별자 이름)
- 기존 단위·통합·슬라이스 테스트가 모두 PASS — 비즈니스 로직 무변경 검증
- 로컬 `./gradlew bootRun` + 주문 생성 시나리오에서 콘솔에 `[traceId=<UUID> memberId=<N>] 주문 생성 ...` 출력 확인 (선택)
