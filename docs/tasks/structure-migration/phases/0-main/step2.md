# Step 2: move-cache-messaging-notification

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (3장 infrastructure, 5.3 캐시, 5.4 Kafka producer/consumer 방향 구분)
- `/src/main/java/com/commerce/outbox/stock/infrastructure/StockRestoreKafkaEventConsumer.java` (Step 3에서 consumer로 옮길 대상 — 이 step에서는 producer/config만 다룬다, 참고용)

## 작업

Redis·Kafka producer·notification 구현체를 외부 대상별 서브패키지로 **순수 이동**한다. `git mv` + package/import 갱신(main·test). 내용 불변.

이동 대상:

- **cache/** (`com.commerce.<domain>.infrastructure` → `...infrastructure.cache`)
  - `auth`: `RedisRefreshTokenStore`
  - `order`: `RedisOrderIdempotencyStore`
- **messaging/**
  - `outbox`: `OutboxRelayMessage` (`com.commerce.outbox.infrastructure` → `com.commerce.outbox.infrastructure.messaging`)
  - `outbox/stock`: `StockRestoreKafkaEventProducer`, `StockRestoreKafkaConsumerConfig` (`com.commerce.outbox.stock.infrastructure` → `...infrastructure.messaging`)
- **notification/**
  - `payment`: `LogNotificationAdapter` (`com.commerce.payment.infrastructure` → `com.commerce.payment.infrastructure.notification`)

주의:
- `OutboxRelayMessage`는 consumer(`StockRestoreKafkaEventConsumer`)·producer 양쪽이 참조한다. 옮긴 뒤 두 곳의 import를 모두 갱신한다(consumer는 아직 `outbox/stock/infrastructure`에 있다).
- `StockRestoreKafkaEventConsumer`(@KafkaListener)는 이 step에서 옮기지 않는다(Step 3, presentation/consumer 대상).
- `auth/infrastructure/BcryptPasswordHasher`는 외부 대상 서브패키지에 해당하지 않으므로 `infrastructure/` 루트에 그대로 둔다.
- `payment/infrastructure/BlockingPaymentCheckerAdapter`는 Step 1에서 이미 persistence로 옮겼다. 건드리지 않는다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- 통합 테스트로 Redis·Kafka 빈 와이어링과 Spring context 부팅이 정상인지 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - cache/messaging/notification 대상이 각 서브패키지로 옮겨졌는가?
   - `OutboxRelayMessage`를 참조하는 consumer·producer import가 모두 갱신됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스/메서드 이름을 바꾸지 마라. 이유: 순수 이동 PR.
- `StockRestoreKafkaEventConsumer`를 옮기지 마라. 이유: Step 3(presentation/consumer) 대상이다.
- `BcryptPasswordHasher`를 억지로 서브패키지로 내리지 마라. 이유: 외부 대상 분류(persistence/cache/messaging/notification)에 해당하지 않는다.
- 기존 테스트를 깨뜨리지 마라.
