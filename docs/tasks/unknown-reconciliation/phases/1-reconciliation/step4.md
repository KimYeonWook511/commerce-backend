# Step 4: reconciliation-scheduler

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L1, L2)

step 3에서 만든 대사 서비스:

- `src/main/java/com/commerce/payment/application/PaymentReconciliationService.java`

기존 스케줄러 패턴(참고):

- `/src/main/java/com/commerce/order/batch/OrderExpirationJobScheduler.java`
- `/src/main/java/com/commerce/outbox/stock/scheduler/StockRestoreOutboxScheduler.java`

## 작업

step 3의 `PaymentReconciliationService.reconcile()`를 주기적으로 트리거하는 `@Scheduled` 스케줄러를 추가한다.

- 기존 스케줄러 패턴을 따른다.
  - cron은 설정값으로 외부화한다(예: `payment.reconciliation.cron`, 기본값은 정책의 최단 진입 지연(1분) 안에서 도는 짧은 주기 — 예: `0 */1 * * * *`). 설정 키와 기본값은 기존 스케줄러 컨벤션에 맞춘다.
  - 테스트 프로파일에서 자동 기동하지 않도록 `@Profile("!test")`(또는 기존 스케줄러와 동일한 방식)를 적용한다.
- 스케줄러는 **트리거만** 담당한다. 수집·PG조회·확정·트랜잭션 경계는 step 3 서비스가 갖는다. 스케줄러에 비즈니스 로직을 넣지 않는다.
- 스케줄러 실행 로깅은 기존 스케줄러의 로깅 컨벤션(`docs/logging-conventions.md`)을 따른다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 스케줄러가 `PaymentReconciliationService.reconcile()`만 위임 호출하는가? (비즈니스 로직 비포함)
   - cron이 설정값으로 외부화되어 있고 기본 주기가 정책 진입 지연과 정합한가?
   - 테스트 프로파일에서 자동 기동하지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 스케줄러에 수집/조회/확정 로직을 직접 넣지 마라. 이유: 트리거와 유스케이스 책임을 분리한다(기존 스케줄러 패턴).
- 분산 락을 추가하지 마라. 이유: 멱등성으로 방어하며 분산 락은 후속이다 (ADR-L2).
- 기존 테스트를 깨뜨리지 마라.
