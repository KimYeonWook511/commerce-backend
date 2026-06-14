# Step 3: orchestrator-to-usecase

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/application-layer-relocate/prd.md`
- `/docs/tasks/application-layer-relocate/architecture.md`
- `/docs/tasks/application-layer-relocate/adr.md` (ADR-L1 분류 기준 + "조율 없으면 usecase 안 둠", ADR-L2)
- `/docs/adr.md` (ADR-006 supersede, ADR-008 NOT_SUPPORTED, ADR-021)
- `/docs/package-structure-guide.md` (usecase=orchestrator, scheduler→usecase 위임, 불필요한 추상화 회피)
- `/docs/optimistic-lock-design.md` (usecase에 @Transactional 금지)
- `/docs/exception-strategy.md` (보상/멱등 흐름)
- `/docs/testing-conventions.md`
- step1·step2에서 변경된 클래스들, `OutboxService`와 그 호출처 `OrderExpirationService`

## 작업

`NOT_SUPPORTED`만 가진 orchestrator 3개를 `usecase/`로 옮기고, 이름 충돌이 풀린 `OrderCreateProcessor`를 리네임하고, pass-through `OutboxService`를 제거한 뒤, application 네이밍 ArchUnit 규칙을 활성화한다. 이 step에서 application 계층의 네이밍·구조 불일치가 모두 해소된다. **호출 구조·로직 불변, 동작 보존. 접미사만 바꾸고 어순은 유지.**

### 3-1. orchestrator 3개: `service/…Service` → `usecase/…UseCase`

| 현재 | 변경 |
| --- | --- |
| `auth/application/service/AuthSignUpService` | `auth/application/usecase/AuthSignUpUseCase` |
| `order/application/service/OrderCreateService` | `order/application/usecase/OrderCreateUseCase` |
| `outbox/stock/application/service/StockRestoreOutboxRelayService` | `outbox/stock/application/usecase/StockRestoreOutboxRelayUseCase` |

각 클래스에 대해:

1. `git mv`로 `usecase/` 패키지로 이동하고 package 선언을 갱신한다.
2. 클래스명 접미사를 `…UseCase`로 바꾼다(어순은 그대로).
3. 빈 애너테이션을 `@Service` → `@Component`로 바꾼다.
4. **모든 `@Transactional` 애너테이션을 제거한다** — `AuthSignUpUseCase.signUp()`·`OrderCreateUseCase.createOrder()`의 `@Transactional(propagation = NOT_SUPPORTED)`, `StockRestoreOutboxRelayUseCase`의 3개 메서드(`publishPendingEvents`/`publishRetryableFailedEvents`/`recoverStalePublishingEvents`) `NOT_SUPPORTED`를 모두 제거한다. import도 정리한다. (usecase는 tx를 열지 않으며, 호출처가 진입점뿐이라 바깥 tx가 없어 동작 동일)
5. 이동·리네임한 클래스를 주입·참조하는 모든 곳(Controller, scheduler, 다른 application 클래스)의 타입·import를 갱신한다.

### 3-2. OrderCreateProcessor → service/OrderCreateService

- `order/application/service/OrderCreateProcessor` → `order/application/service/OrderCreateService`로 `git mv` + 리네임. (3-1에서 기존 `OrderCreateService`가 `usecase/OrderCreateUseCase`로 빠져 이름이 비었다)
- `OrderCreateUseCase`가 주입하는 필드 타입(`OrderCreateProcessor` → `OrderCreateService`)과 변수명을 갱신한다.
- `@Transactional`(tx 단위작업)·`@Service`는 유지한다.

### 3-3. OutboxService 제거 (pass-through)

`outbox/application/usecase/OutboxService`는 `StockRestoreOutboxCreateService.createOutboxEvent(...)`에 위임만 하는 pass-through다(조율 없음). ADR-L1("조율이 있을 때만 usecase") + CLAUDE.md("불필요한 추상화를 피한다")에 따라 제거한다.

1. 호출처 `order/application/service/OrderExpirationService`가 `OutboxService` 대신 `StockRestoreOutboxCreateService`(`outbox/stock/application/service/`)를 직접 주입받아 `createOutboxEvent(...)`를 호출하도록 바꾼다. (`outboxService.createStockRestoreOutboxEvent(command)` → `stockRestoreOutboxCreateService.createOutboxEvent(command)`)
2. `OutboxService.java`를 `git rm`으로 삭제한다.
3. 이 변경으로 기존 `service → usecase` 역방향 의존(`OrderExpirationService` → `OutboxService`)도 사라진다. 호출 결과(outbox 이벤트 생성)는 불변이다.

### 3-4. 테스트 동기화

- 위 클래스들의 테스트 클래스명·참조를 동기화한다. `OrderCreateService`(기존 orchestrator)를 검증하던 테스트는 `OrderCreateUseCase`로, `OrderCreateProcessor` 테스트는 `OrderCreateService`로 대상을 맞춘다.
- `OutboxService` 테스트가 있으면 제거하고, `OrderExpirationService` 테스트의 mock 대상을 `StockRestoreOutboxCreateService`로 교체한다.
- `rg`로 사용처를 빠짐없이 찾는다.

### 3-5. ArchUnit 네이밍 규칙 활성화

3-1~3-3으로 `…Processor`·usecase의 `…Service`가 모두 사라졌다. `ArchitectureRulesTest`에 두 규칙을 추가한다(위반 0):

```java
classes().that().resideInAPackage("..application.usecase..")
    .should().haveSimpleNameEndingWith("UseCase");
classes().that().resideInAPackage("..application.service..")
    .should().haveSimpleNameEndingWith("Service");
```

`@DisplayName`에 ADR-006 supersede / ADR-L1 근거를 단다. (step2에서 추가한 application class-level `@Transactional` 금지 규칙이 usecase의 class-level도 이미 커버하므로 별도 추가 불필요)

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew batchTest
```

(`batchTest` 포함 이유: `StockRestoreOutboxRelayUseCase`·`OrderExpirationService`가 outbox relay / 주문 만료 → outbox 경로로 batch·통합 경계에 닿는다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다(추가한 네이밍 규칙 2개 포함).
2. 아래를 확인한다.
   - orchestrator 3개가 `usecase/…UseCase` + `@Component` + `@Transactional` 0인가?
   - `OrderCreateProcessor`가 `OrderCreateService`로 바뀌고 `OrderCreateUseCase` 주입이 갱신됐는가?
   - `OutboxService`가 완전히 제거되고 `OrderExpirationService`가 `StockRestoreOutboxCreateService`를 직접 호출하는가?
   - `usecase/` 전체가 `…UseCase`, `service/` 전체가 `…Service`인가? (네이밍 규칙 green)
3. 사용처 탐색으로 누락 확인:
   - `rg "OrderCreateProcessor" src/main src/test` (0건)
   - `rg "\bOutboxService\b" src/main src/test` (0건 — `StockRestoreOutbox*Service`는 별개라 매칭되지 않음)
   - `rg "(AuthSignUp|StockRestoreOutboxRelay)Service|OrderCreateService" src/main src/test` (orchestrator 옛 이름 0건, 새 `OrderCreateService`는 tx 단위작업만)
4. 동작 보존 확인: 회원가입·주문생성·주문만료·outbox relay 흐름이 통합/batch 테스트로 통과하는지 확인한다.

## 금지사항

- orchestrator 3개에 `@Transactional`을 남기지 마라. 이유: usecase 패키지는 tx를 열지 않으며 ArchUnit이 이를 강제한다.
- self-invocation을 만들지 마라. 이유: 3개 모두 tx 작업을 별도 빈(register/issue, processor, repository)으로 호출하므로 구조를 그대로 두면 프록시가 유지된다. tx 호출을 같은 클래스 메서드로 끌어들이면 프록시가 깨진다.
- outbox 이벤트 생성 로직·payload를 바꾸지 마라. 이유: OutboxService 제거는 pass-through 한 단계만 없애는 것이며 동작은 불변이다.
- 클래스명 어순을 바꾸지 마라(접미사만 교체). 이유: 어순 통일은 별도 PR이다.
- 호출 구조·로직을 바꾸지 마라. 이유: 동작 불변 리팩터다.
- 기존 테스트를 깨뜨리지 마라.
