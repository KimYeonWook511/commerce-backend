# Step 1: move-persistence-adapters

## 읽어야 할 파일

먼저 아래 파일들을 읽고 목표 구조와 설계 의도를 파악하라:

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/tasks/structure-migration/adr.md`
- `/docs/package-structure-guide.md` (3장 infrastructure — persistence 분리 기준, 정본)
- `/docs/architecture.md` (레이어·의존 방향)
- `/src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` (강제 규칙 — `saveAndFlushOnlyInPersistence`, `daoExceptionsConfinedToPersistence`)

## 작업

전 도메인의 JPA adapter와 Jpa repository를 각 도메인의 `infrastructure/` 루트에서 `infrastructure/persistence/`로 **순수 이동**한다. `git mv`로 옮기고, 옮긴 클래스의 `package` 선언과 이를 참조하는 **main·test 양쪽 import를 모두 갱신**한다. 클래스 내용(로직·시그니처·이름)은 일절 바꾸지 않는다.

이동 대상 (`com.commerce.<domain>.infrastructure` → `com.commerce.<domain>.infrastructure.persistence`):

- **cart**: `CartItemRemoverAdapter`, `CartItemRepositoryAdapter`, `JpaCartItemRepository`
- **member**: `MemberRepositoryAdapter`, `JpaMemberRepository`
- **order**: `OrderRepositoryAdapter`, `JpaOrderRepository`, `JpaOrderItemRepository`
- **outbox**: `OutboxEventRepositoryAdapter`, `ProcessedEventRepositoryAdapter`, `JpaOutboxEventRepository`, `JpaProcessedEventRepository`
- **payment**: `PaymentRepositoryAdapter`, `PaymentReservationRepositoryAdapter`, `BlockingPaymentCheckerAdapter`, `JpaPaymentRepository`, `JpaPaymentReservationRepository`
- **product**: `ProductRepositoryAdapter`, `JpaProductRepository`
- **stock**: `StockRepositoryAdapter`, `StockHistoryRepositoryAdapter`, `JpaStockRepository`, `JpaStockHistoryRepository`

주의:
- `outbox/infrastructure/OutboxRelayMessage`는 이 step에서 **옮기지 않는다**(messaging 대상, Step 2).
- `auth/infrastructure/`(`BcryptPasswordHasher`, `RedisRefreshTokenStore`)는 이 step 대상이 아니다. 건드리지 않는다.
- import 갱신은 단순 텍스트 치환이 아니라, 각 이동 클래스의 FQN을 참조하는 모든 곳(같은 도메인 내부, 다른 도메인, test)을 빠짐없이 반영한다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest batchTest
```

- 통합·배치 테스트까지 포함: persistence 빈 와이어링과 DB 매핑이 그대로인지(Spring context 부팅·Testcontainers·batch 경로) 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 이동 대상이 모두 `infrastructure/persistence/` 아래에 있는가?
   - `rg "saveAndFlush" src/main/java`로 호출처가 전부 `infrastructure/persistence` 안에 있는가?
   - 옮긴 클래스를 참조하는 import가 main·test 모두 갱신됐는가? (`./gradlew test`가 컴파일로 보증)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스/메서드/필드 이름을 바꾸지 마라. 이유: 이번 PR은 순수 이동이고 리네임은 별도 PR이다.
- 로직·메서드 시그니처·애너테이션을 바꾸지 마라. 이유: 동작 불변이 이 task의 증거다.
- 대상 외 클래스(`OutboxRelayMessage`, `auth/infrastructure/*`)를 옮기지 마라. 이유: 각각 Step 2 대상이거나 비대상이다.
- import 갱신을 test 코드에서 빠뜨리지 마라. 이유: 컴파일이 깨진다.
- 기존 테스트를 깨뜨리지 마라.
