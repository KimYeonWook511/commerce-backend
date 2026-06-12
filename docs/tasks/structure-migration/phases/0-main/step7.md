# Step 7: split-application-usecase-service

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (1장 application — usecase/service 분리 기준)
- `/docs/adr.md` (ADR-006 Service suffix 유지, ADR-008 NOT_SUPPORTED tx 분리)
- `/src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` (`transactionalOnlyInServicePackage`)

## 작업

application 루트의 Service/Processor 클래스를 `@Transactional` 보유 여부로 `usecase/`(tx 없음) / `service/`(tx 보유)로 **순수 이동**한다. `git mv` + package/import 갱신(main·test). 내용 불변. **클래스 이름은 그대로 둔다**(리네임은 별도 PR).

분류 기준(기계적): `@Transactional`이 클래스 레벨이든 메서드 레벨이든 하나라도 있으면 `service/`, 전혀 없으면 `usecase/`.

### `usecase/`로 이동 (@Transactional 전혀 없음)

- `auth`: `AuthTokenIssueService`, `TokenAuthenticationService` → `com.commerce.auth.application.usecase`
- `cart`: `AddCartItemService`, `UpdateCartItemQuantityService` → `com.commerce.cart.application.usecase`
- `outbox`: `OutboxService` → `com.commerce.outbox.application.usecase`
- `payment`: `PaymentApprovalCompensationService`, `PaymentReconciliationService` → `com.commerce.payment.application.usecase`
- `payment/naverpay`: `NaverPayApprovalService` → `com.commerce.payment.naverpay.application.usecase`

### `service/`로 이동 (@Transactional 보유)

- `auth`: `AuthLoginService`, `AuthSignUpService`, `AuthTokenReissueService` → `com.commerce.auth.application.service`
- `cart`: `AddCartItemProcessor`, `UpdateCartItemQuantityProcessor`, `RemoveCartItemService`, `GetMyCartService` → `com.commerce.cart.application.service`
- `member`: `MemberQueryService`, `MemberRegistrationService` → `com.commerce.member.application.service`
- `order`: `OrderCreateService`, `OrderCancelService`, `OrderConcurrencyService`, `OrderExpirationService`, `OrderCreateProcessor` → `com.commerce.order.application.service`
- `outbox/stock`: `StockRestoreOutboxConsumeService`, `StockRestoreOutboxCreateService`, `StockRestoreOutboxRelayService` → `com.commerce.outbox.stock.application.service`
- `payment`: `PaymentApprovalRecordService`, `PaymentApprovalService`, `PaymentCancellationService`, `ReservePaymentService` → `com.commerce.payment.application.service`
- `product`: `AdminProductService`, `ProductQueryService` → `com.commerce.product.application.service`
- `stock`: `AdminStockService`, `StockConcurrencyService`, `StockInventoryService` → `com.commerce.stock.application.service`

주의:
- `application/command/`, `application/port/`, `application/result/`, `application/payload/`, `application/dto/` 등 DTO·port 서브패키지는 **그대로 둔다**. 이 step은 Service/Processor 클래스만 옮긴다.
- `OrderCreateService`, `AuthSignUpService`, `StockRestoreOutboxRelayService`는 메서드가 `NOT_SUPPORTED`라도 클래스/메서드에 `@Transactional` annotation을 보유하므로 `service/`다. orchestrator/tx 분할은 이번 PR 밖(B)이다.
- usecase가 service를 호출하는 기존 의존(예: `AddCartItemService`→`AddCartItemProcessor`)은 패키지를 넘는 호출이 되며 그대로 둔다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- 통합 테스트로 service/usecase 빈 와이어링과 tx 프록시(패키지 간 호출)가 정상인지 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `rg "@Transactional" src/main/java/com/commerce/*/application`의 선언이 모두 `application/service/` 안에 있는가? `application/usecase/` 안에는 하나도 없는가?
   - command/port/result/dto 서브패키지가 제자리에 있는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스 이름을 바꾸지 마라(예: `Processor`→`Service`, `…Service`→`…UseCase`). 이유: 리네임은 ADR-006 변경이 얽힌 별도 PR이다.
- `@Transactional`을 추가/제거/이동하지 마라. 이유: 분류는 현재 annotation 상태를 기준으로 하며, tx 경계 변경은 로직 변경(B)이다.
- 혼합 클래스를 orchestrator/tx로 분할하지 마라. 이유: 클래스 분할은 (B)로 이번 PR 밖이다.
- command/port/result/dto를 옮기지 마라. 이유: 이 step은 Service/Processor 클래스만 분류 이동한다.
- 기존 테스트를 깨뜨리지 마라.
