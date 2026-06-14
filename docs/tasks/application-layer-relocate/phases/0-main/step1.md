# Step 1: role-suffix-and-component

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/application-layer-relocate/prd.md`
- `/docs/tasks/application-layer-relocate/architecture.md`
- `/docs/tasks/application-layer-relocate/adr.md`
- `/docs/architecture.md` (서비스 네이밍 원칙 — 역할별 접미사)
- `/docs/package-structure-guide.md` (application 책임 분리, 네이밍 주의)
- `/docs/optimistic-lock-design.md` (usecase 예시의 빈 애너테이션 = `@Component`)
- `/docs/testing-conventions.md` (테스트 클래스명 규칙)

## 작업

`usecase/` 패키지의 클래스명을 `…UseCase`로, 빈 애너테이션을 `@Component`로 바꾸고, cart의 `…Processor` 2개를 `…Service`로 리네임한다. **이동·리네임만 한다. 호출 구조·로직은 바꾸지 않는다.**

### 1-1. usecase/ 7개: `…Service` → `…UseCase` + `@Service` → `@Component`

**접미사만 바꾼다. 어순({도메인}{행위} 등 기존 순서)은 그대로 둔다.** (어순 통일은 별도 PR)

| 현재 | 변경 |
| --- | --- |
| `auth/application/usecase/AuthTokenIssueService` | `AuthTokenIssueUseCase` |
| `auth/application/usecase/TokenAuthenticationService` | `TokenAuthenticationUseCase` |
| `cart/application/usecase/AddCartItemService` | `AddCartItemUseCase` |
| `cart/application/usecase/UpdateCartItemQuantityService` | `UpdateCartItemQuantityUseCase` |
| `payment/application/usecase/PaymentApprovalCompensationService` | `PaymentApprovalCompensationUseCase` |
| `payment/application/usecase/PaymentReconciliationService` | `PaymentReconciliationUseCase` |
| `payment/naverpay/application/usecase/NaverPayApprovalService` | `NaverPayApprovalUseCase` |

각 클래스에서 클래스 선언의 `@Service` 애너테이션을 `@Component`(`org.springframework.stereotype.Component`)로 바꾼다. import도 함께 교체한다.

> `outbox/application/usecase/OutboxService`는 이 step에서 **건드리지 않는다**. 조율 없는 단순 pass-through라 step3에서 통째로 제거된다(`OutboxUseCase`로 리네임하지 않는다).

### 1-2. cart Processor 2개: `…Processor` → `…Service`

| 현재 | 변경 |
| --- | --- |
| `cart/application/service/AddCartItemProcessor` | `AddCartItemService` |
| `cart/application/service/UpdateCartItemQuantityProcessor` | `UpdateCartItemQuantityService` |

이 두 클래스는 `@Transactional`(tx 단위작업)을 보유하므로 `@Service`를 유지한다. 이름 충돌 없음(동명 usecase가 1-1에서 `…UseCase`로 빠짐).

### 1-3. 참조처·테스트 갱신

- 위 클래스를 주입·참조하는 모든 곳(Controller, 다른 application 클래스, scheduler 등)의 타입명·필드명·import를 갱신한다. 특히 `AddCartItemUseCase`/`UpdateCartItemQuantityUseCase`가 주입하는 Processor 타입이 `…Service`로 바뀌므로 주입 참조도 갱신한다.
- 각 클래스의 테스트 클래스명도 동기화한다(`grep -rl`로 사용처를 찾아 빠짐없이). 예: `AddCartItemServiceTest`(usecase 테스트) → `AddCartItemUseCaseTest`. 테스트가 검증하는 대상이 무엇인지(흐름 vs tx 작업) 확인해 올바른 새 이름으로 바꾼다.
- 클래스 파일 이동·리네임은 `git mv`를 사용해 히스토리를 보존한다.

### 1-4. ArchUnit 네이밍 규칙은 아직 추가하지 않는다

`ArchitectureRulesTest`의 `usecaseClassesEndWithUseCase`·`serviceClassesEndWithService` 규칙은 **step3에서** 활성화한다. 이 step 시점에는 `OrderCreateProcessor`(`…Processor`)가 아직 살아 있어 `serviceClassesEndWithService`가 위반되기 때문이다. 이 step에서는 네이밍 ArchUnit 규칙을 건드리지 않는다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - usecase/ 8개가 모두 `…UseCase` + `@Component`인가?
   - cart Processor 2개가 `…Service`로 바뀌고 주입 참조가 갱신됐는가?
   - 리네임한 클래스의 모든 참조처·테스트가 컴파일되는가?
3. 사용처 탐색으로 누락이 없는지 확인한다.
   - `rg "Processor" src/main/java/com/commerce/cart src/test/java/com/commerce/cart`
   - `rg "(AuthTokenIssue|TokenAuthentication|AddCartItem|UpdateCartItemQuantity|PaymentApprovalCompensation|PaymentReconciliation|NaverPayApproval)Service" src/main src/test`

## 금지사항

- 호출 구조·메서드 시그니처·로직을 바꾸지 마라. 이유: 이 step은 이름·패키지·빈 애너테이션만 바꾸는 동작 불변 리팩터다.
- 클래스명 어순을 바꾸지 마라(접미사만 교체). 이유: 어순 통일은 별도 PR이며, 이 step은 동작 불변 접미사 리네임만 담당한다.
- `OutboxService`를 건드리지 마라(리네임·이동 모두). 이유: step3에서 제거 대상이다.
- `OrderCreateProcessor`를 건드리지 마라. 이유: 이름 충돌(`OrderCreateService`) 해소는 orchestrator가 usecase로 빠지는 step3에서 함께 처리한다.
- ArchUnit 네이밍 규칙을 추가하지 마라. 이유: step3 전까지 `…Processor` 잔존으로 위반된다.
- 기존 테스트를 깨뜨리지 마라.
