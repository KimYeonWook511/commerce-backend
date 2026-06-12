# Step 3: relocate-inbound-adapters

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (4장 presentation, 5.2 scheduler/batch=inbound adapter, 5.4 consumer)
- `/src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` (`scheduledOnlyInPresentation`, `kafkaListenerOnlyInConsumer`)

## 작업

진입점(inbound adapter)을 `presentation/` 하위로 **순수 이동**한다. `git mv` + package/import 갱신(main·test). 내용 불변.

이동 대상:

- **scheduler/** (`@Scheduled`)
  - `order`: `OrderExpirationJobScheduler` (`com.commerce.order.batch` → `com.commerce.order.presentation.scheduler`)
  - `outbox/stock`: `StockRestoreOutboxScheduler` (`com.commerce.outbox.stock.scheduler` → `com.commerce.outbox.stock.presentation.scheduler`)
  - `payment`: `PaymentReconciliationScheduler` (`com.commerce.payment.scheduler` → `com.commerce.payment.presentation.scheduler`)
- **consumer/** (`@KafkaListener`)
  - `outbox/stock`: `StockRestoreKafkaEventConsumer` (`com.commerce.outbox.stock.infrastructure` → `com.commerce.outbox.stock.presentation.consumer`)
- **batch/** (Spring Batch)
  - `order`: `OrderExpirationBatchConfig` (`com.commerce.order.batch` → `com.commerce.order.presentation.batch`)
  - `order`: `batch/listener/`의 5개 listener(`OrderExpirationJobListener`, `OrderExpirationItemReadListener`, `OrderExpirationItemWriteListener`, `OrderExpirationRetryListener`, `OrderExpirationSkipListener`) (`com.commerce.order.batch.listener` → `com.commerce.order.presentation.batch.listener`)

주의:
- `OrderExpirationJobScheduler`(scheduler)와 `OrderExpirationBatchConfig`(batch)는 서로 참조(scheduler가 `Job` 빈 주입)하므로 둘 다 옮긴 뒤 import를 함께 맞춘다.
- `OrderExpirationBatchConfig`는 `OptimisticLockingFailureException`을 `.retry/.skip`에 명명한다 — 이 step에서 그 코드를 바꾸지 않는다(ArchUnit 규칙 예외처는 Step 8에서 처리).
- 이동 후 빈 디렉터리(`order/batch`, `payment/scheduler`, `outbox/stock/scheduler`)는 남기지 않는다(git은 빈 디렉터리를 추적하지 않으므로 자동 정리됨).

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest batchTest
```

- `batchTest`로 batch Job/Step 와이어링이, `integrationTest`로 scheduler·consumer 빈 등록이 정상인지 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `rg "@Scheduled" src/main/java`의 선언이 모두 `..presentation..` 안에 있는가?
   - `rg "@KafkaListener" src/main/java`의 선언이 `..presentation.consumer..` 안에 있는가?
   - batch config·listener가 `presentation/batch/` 아래로 옮겨졌는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스 이름·로직을 바꾸지 마라. 이유: 순수 이동 PR.
- `OrderExpirationBatchConfig`의 `.retry/.skip` 예외 타입을 바꾸지 마라. 이유: ArchUnit 예외처는 Step 8 범위이고, 도메인 예외 변환은 이번 PR 밖(B)이다.
- scheduler를 application service에 합치는 식의 구조 변경을 하지 마라. 이유: 순수 위치 이동만 한다.
- 기존 테스트를 깨뜨리지 마라.
