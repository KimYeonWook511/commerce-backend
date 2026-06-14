# Step 2: class-tx-to-method

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/application-layer-relocate/prd.md`
- `/docs/tasks/application-layer-relocate/architecture.md`
- `/docs/tasks/application-layer-relocate/adr.md` (ADR-L2)
- `/docs/adr.md` (ADR-021 method-level only, ADR-008 NOT_SUPPORTED 함정)
- `/docs/optimistic-lock-design.md` (tx 경계 설계)
- `/docs/exception-strategy.md` (NOT_SUPPORTED orchestrator 맥락)
- step1에서 리네임된 클래스들

## 작업

application 계층의 **class-level `@Transactional`을 전부 제거**하고 tx 경계를 method-level로만 표현한다. (ADR-021 전 도메인 확장)

### 2-1. 대상 15개 클래스의 class-level `@Transactional(readOnly = true)` 제거

```
auth/application/service/    AuthLoginService, AuthSignUpService, AuthTokenReissueService
member/application/service/  MemberQueryService, MemberRegistrationService
order/application/service/   OrderCancelService, OrderConcurrencyService, OrderCreateService, OrderExpirationService
payment/application/service/ ReservePaymentService
product/application/service/ AdminProductService, ProductQueryService
stock/application/service/   AdminStockService, StockConcurrencyService, StockInventoryService
```

각 클래스에서:

1. 클래스 선언 위의 `@Transactional(readOnly = true)`를 제거한다.
2. **그 class-level 선언에 의존하던(= 별도 method-level 애너테이션이 없던) 조회 메서드**에 `@Transactional(readOnly = true)`를 메서드마다 명시적으로 부착한다. 누락하면 그 메서드가 tx 없이 실행되어 동작이 바뀐다 — 반드시 클래스별로 모든 public 메서드의 tx 정책을 확인한다.
3. 이미 method-level `@Transactional`(쓰기) 또는 `@Transactional(propagation = ...)`이 있던 메서드는 그대로 둔다.

### 2-2. NOT_SUPPORTED 보유 클래스 주의 (ADR-008 함정)

- `AuthSignUpService.signUp()`, `OrderCreateService.createOrder()`는 method-level `@Transactional(propagation = NOT_SUPPORTED)`로 class-level readOnly를 끄고 있었다. class-level을 제거해도 이 두 메서드의 `NOT_SUPPORTED`는 **이 step에서는 그대로 유지**한다(아직 service에 있음). 이 둘은 step3에서 usecase로 이동하며 `@Transactional` 자체가 제거된다.
- `PaymentCancellationService`, `StockConcurrencyService`는 NOT_SUPPORTED 메서드와 실제 tx 메서드가 공존하는 혼합 클래스다. **service에 유지**하고, class-level만 제거한 뒤 각 메서드의 기존 method-level 정책(tx / NOT_SUPPORTED)을 보존한다. (`StockConcurrencyService`는 2-1 목록에 있으니 class-level 제거 대상, `PaymentCancellationService`는 class-level이 없으니 메서드만 확인)

### 2-3. ArchUnit: application class-level `@Transactional` 금지 규칙 추가

`ArchitectureRulesTest`에 application 패키지의 **클래스 레벨** `@Transactional`을 금지하는 규칙을 추가한다. 기존 `transactionalOnlyInServicePackage`(usecase 메서드 금지)와 별개로, class-level을 잡는다:

```java
noClasses()
    .that().resideInAPackage("..application..")
    .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");
```

`@DisplayName`에 ADR-021/ADR-L2 근거를 단다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다(추가한 ArchUnit 규칙 포함).
2. 아래를 확인한다.
   - 15개 클래스에 class-level `@Transactional`이 남아 있지 않은가? (`rg "^@Transactional" src/main/java/com/commerce/*/application` 가 클래스 선언 직전에 매칭되지 않아야 함)
   - class-level readOnly에 의존하던 조회 메서드 전부에 method-level `@Transactional(readOnly = true)`가 명시됐는가?
   - AuthSignUp/OrderCreate의 `NOT_SUPPORTED`가 보존됐는가?
3. 동작 보존 확인: readOnly 누락으로 인한 쓰기/조회 동작 변화가 없는지 통합 테스트로 검증한다.

## 금지사항

- class-level을 제거하면서 조회 메서드에 `@Transactional(readOnly = true)` 부착을 누락하지 마라. 이유: 그 메서드가 tx 없이 실행되어 동작이 바뀐다(silent 회귀).
- AuthSignUp/OrderCreate의 `NOT_SUPPORTED`를 이 step에서 제거하지 마라. 이유: 이들의 usecase 이동·애너테이션 제거는 step3 책임이다.
- 혼합 클래스(PaymentCancellation/StockConcurrency)를 usecase로 옮기지 마라. 이유: 실제 tx 메서드를 보유하므로 service가 맞다.
- 네이밍 ArchUnit 규칙은 추가하지 마라. 이유: step3 책임.
- 기존 테스트를 깨뜨리지 마라.
