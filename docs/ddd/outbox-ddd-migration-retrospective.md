# Outbox DDD Migration Retrospective

## 배경

이번 작업은 마지막으로 남아있던 `outbox` 모듈을 다른 도메인과 동일한 DDD 레이어 구조로 전환했다.

`outbox`는 `service/`, `repository/`, `mq/` 구조를 유지하고 있어 전 도메인 DDD 전환이 완료된 뒤에도 legacy 방식을 혼용하고 있었다.

## 이번 작업에서 확정한 기준

### StockRestoreOutboxService를 책임 단위로 분리한다

기존 `StockRestoreOutboxService`(258줄)는 이벤트 생성, 발행, 재시도, stale 복구까지 4개의 유스케이스를 하나의 클래스에 담고 있었다. 호출 맥락도 달랐다.

- `createOutboxEvent` → order API 경로 (OrderExpirationService에서 호출)
- `publishPendingEvents`, `publishRetryableFailedEvents`, `recoverStalePublishingEvents` → 스케줄러 경로

이번 작업에서 책임 단위로 분리했다.

```
StockRestoreOutboxCreateService  — 이벤트 생성 전담
StockRestoreOutboxRelayService   — 발행·재시도·stale 복구 전담
```

### domain port에서 Pageable을 제거하고 int limit으로 교체한다

기존 `OutboxEventRepository`는 Spring Data JPA 직접 구현체로 `Pageable`을 인자로 받았다. domain port에서 JPA 전용 타입을 드러내지 않도록 `int limit` 파라미터로 교체했다. adapter 내부에서 `PageRequest.of(0, limit)`으로 변환한다.

### domain port에서 saveAndFlush를 save로 은닉한다

`StockRestoreOutboxConsumeService`는 중복 소비 방지를 위해 `saveAndFlush`로 유니크 인덱스를 점유했다. `ProcessedEventRepository` domain port는 `save`만 노출하고, `ProcessedEventRepositoryAdapter`가 내부에서 `saveAndFlush`를 사용한다.

### OutboxPublishTarget을 domain 계층으로 이동한다

`OutboxPublishTarget`은 JPA 어노테이션이 없는 순수 interface다. Spring Data JPA projection으로 계속 사용 가능하며, 패키지를 `outbox/domain/`으로 이동했다.

### OutboxRelayMessage를 infrastructure 계층으로 이동한다

Kafka relay 전용 DTO이므로 `outbox/infrastructure/`로 이동했다.

### 스케줄러는 scheduler 패키지에 그대로 유지한다

`StockRestoreOutboxScheduler`는 order.batch 패턴과 동일하게 실행 기술 계층으로 취급하고 `outbox/stock/scheduler/`에 그대로 유지했다.

## 최종 구조

```
outbox/
├── domain/
│   ├── (기존 엔티티)
│   ├── OutboxPublishTarget.java
│   └── repository/
│       ├── OutboxEventRepository.java
│       └── ProcessedEventRepository.java
├── infrastructure/
│   ├── JpaOutboxEventRepository.java
│   ├── OutboxEventRepositoryAdapter.java
│   ├── JpaProcessedEventRepository.java
│   ├── ProcessedEventRepositoryAdapter.java
│   └── OutboxRelayMessage.java
├── application/
│   └── OutboxService.java
└── stock/
    ├── application/
    │   ├── StockRestoreOutboxCreateService.java
    │   ├── StockRestoreOutboxRelayService.java
    │   ├── StockRestoreOutboxConsumeService.java
    │   ├── command/
    │   ├── payload/
    │   └── result/
    ├── infrastructure/
    │   ├── StockRestoreKafkaEventConsumer.java
    │   ├── StockRestoreKafkaEventProducer.java
    │   └── StockRestoreKafkaConsumerConfig.java
    └── scheduler/
        └── StockRestoreOutboxScheduler.java
```

## 전 도메인 DDD 전환 완료

이번 작업으로 전 도메인의 DDD 구조 전환이 완료됐다.

| 도메인 | 상태 |
|--------|------|
| stock | ✅ 완료 |
| order | ✅ 완료 |
| product | ✅ 완료 |
| payment | ✅ 완료 |
| member | ✅ 완료 |
| auth | ✅ 완료 |
| naverpay | ✅ 완료 |
| outbox | ✅ 완료 |
