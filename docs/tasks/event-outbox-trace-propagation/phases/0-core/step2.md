# Step 2: outbox-trace-propagation

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/tasks/event-outbox-trace-propagation/prd.md`
- `docs/tasks/event-outbox-trace-propagation/architecture.md`
- `docs/tasks/event-outbox-trace-propagation/adr.md`
- `docs/tasks/event-outbox-trace-propagation/db-schema.md`
- `src/main/java/com/commerce/common/log/LogContext.java` — MDC 유효성 검증 API
- `src/main/java/com/commerce/common/log/kafka/TraceIdKafkaProducerInterceptor.java` — Kafka 헤더 자동 전파 메커니즘 이해용
- `src/main/java/com/commerce/outbox/domain/OutboxEvent.java` — Entity 수정 대상
- `src/main/java/com/commerce/outbox/domain/OutboxPublishTarget.java` — Projection 수정 대상
- `src/main/java/com/commerce/outbox/infrastructure/JpaOutboxEventRepository.java` — JPA Projection 쿼리 수정 대상
- `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java` — 저장 수정 대상
- `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxRelayService.java` — relay 수정 대상

태스크 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `docs/tasks/kafka-trace-propagation/retrospective.md` — 기존 Kafka 전파 구조

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

Outbox 생성 시점의 MDC traceId를 DB 컬럼에 저장하고, relay 시점에 MDC로 복원하여 Kafka 헤더 → consumer까지 전파한다.

### 수정 파일 1: `OutboxEvent` (도메인)

`src/main/java/com/commerce/outbox/domain/OutboxEvent.java`

- 필드 추가: `@Column(length = 64) private String traceId;` (nullable)
- `@Builder`의 private 생성자 파라미터 마지막에 `String traceId` 추가
- `createPending()` static factory 시그니처에 `String traceId` 파라미터 추가 (마지막 위치)
- 빌더 호출에 `.traceId(traceId)` 추가

### 수정 파일 2: `OutboxPublishTarget` (Projection 인터페이스)

`src/main/java/com/commerce/outbox/domain/OutboxPublishTarget.java`

- `String getTraceId();` 메서드 추가 (마지막 위치)

### 수정 파일 3: `JpaOutboxEventRepository`

`src/main/java/com/commerce/outbox/infrastructure/JpaOutboxEventRepository.java`

- `findPendingPublishTargets`와 `findRetryableFailedPublishTargets`의 JPQL 쿼리 select 절에 `e.traceId as traceId` 추가
- 다른 쿼리는 수정하지 않는다.

### 수정 파일 4: `StockRestoreOutboxCreateService`

`src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateService.java`

- `createOutboxEvent()` 내부에서 `OutboxEvent.createPending(...)` 호출 시 마지막 인자로 `resolveTraceIdForStorage()` 결과 전달
- 헬퍼 메서드 추가:

```java
private String resolveTraceIdForStorage() {
    String traceId = LogContext.getTraceId();
    return LogContext.isValidTraceId(traceId) ? traceId : null;
}
```

- 기존 로그(`"재고 복구 Outbox 발행 orderId={}..."`)는 유지.

### 수정 파일 5: `StockRestoreOutboxRelayService`

`src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxRelayService.java`

- `publishTarget(OutboxPublishTarget target, LocalDateTime now)` 메서드 내부를 try-finally로 감싸 MDC를 복원/정리

권장 구조:

```java
private PublishResult publishTarget(OutboxPublishTarget target, LocalDateTime now) {
    boolean traceIdPushed = pushTraceIdIfValid(target.getTraceId());
    try {
        try {
            eventPublisher.publish(target);
            if (!markSent(target)) {
                return PublishResult.SKIPPED;
            }
            return PublishResult.PUBLISHED;
        } catch (RuntimeException ex) {
            handlePublishFailure(target, now, ex);
            return PublishResult.FAILED;
        }
    } finally {
        if (traceIdPushed) {
            LogContext.removeTraceId();
        }
    }
}

private boolean pushTraceIdIfValid(String traceId) {
    if (LogContext.isValidTraceId(traceId)) {
        LogContext.putTraceId(traceId);
        return true;
    }
    return false;
}
```

- target의 traceId가 유효하면 MDC에 push, 아니면 MDC 조작 없이 진행 (이 경우 Kafka producer interceptor가 신규 UUID 발급)
- `handlePublishFailure()`도 try 블록 안에 있으므로 실패 로그에 traceId가 포함된다.

### 테스트

다음 시나리오를 단위 테스트로 검증한다(통합 테스트는 별도 작업).

- `StockRestoreOutboxCreateService.createOutboxEvent()`가 MDC traceId를 outbox에 저장하는가
  - MDC에 traceId set 후 호출, 저장된 OutboxEvent의 traceId 확인
- MDC에 traceId가 없거나 유효하지 않으면 outbox.traceId가 null로 저장되는가
- `StockRestoreOutboxRelayService.publishTarget()`이 target의 traceId를 MDC에 복원하는가
  - mock으로 `eventPublisher.publish()` 호출 시점의 MDC 값을 캡처
- traceId가 null이거나 유효하지 않으면 MDC 조작이 일어나지 않는가
- relay 종료 시 finally에서 MDC가 정리되는가

테스트 위치는 기존 테스트 파일을 따른다 (모두 존재 확인됨).

- `src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxCreateServiceTest.java`
- `src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxRelayServiceTest.java`

또한 `OutboxEvent.createPending` 시그니처 변경에 따라 다음 테스트도 함께 수정한다.

- `src/test/java/com/commerce/outbox/infrastructure/JpaOutboxEventRepositoryTest.java` (line 293에서 `createPending` 호출)

## Acceptance Criteria

```bash
./gradlew test
```

본 step은 단위 테스트 범위로만 검증한다. outbox → Kafka → consumer end-to-end 통합 검증은 향후 별도 작업으로 분리한다.

## 검증 절차

1. 위 두 커맨드를 실행한다.
2. 아래를 확인한다.
   - `OutboxEvent`에 traceId 필드가 추가되고 `createPending()` 시그니처에 반영되었는가
   - `OutboxPublishTarget`에 `getTraceId()` 메서드가 추가되었는가
   - JPA Projection 쿼리 select 절에 `e.traceId as traceId`가 포함되었는가
   - `StockRestoreOutboxCreateService`가 `LogContext.getTraceId()`를 검증 후 저장하는가
   - `StockRestoreOutboxRelayService.publishTarget()`이 target traceId를 MDC에 복원/정리하는가
   - `JpaOutboxEventRepositoryTest`의 `createPending` 호출이 새 시그니처에 맞춰 수정되었는가
   - 기존 테스트가 모두 통과하는가
3. 결과에 따라 step 상태를 갱신한다.

### 사용처 탐색

`OutboxEvent.createPending` 시그니처 변경이 다른 호출처에 영향을 줄 수 있으므로 탐색한다.

```bash
rg "OutboxEvent.createPending" src/main/java src/test/java
rg "OutboxPublishTarget" src/main/java src/test/java
```

탐색 결과 호출처가 추가로 발견되면 함께 수정한다.

## 금지사항

- `MDC.clear()` 사용 금지. 이유: 다른 MDC 키(memberId 등) 영향 위험. `LogContext.removeTraceId()`만 사용한다.
- 스케줄러 진입 시점에 traceId 발급 금지. 이유: ADR 결정 3에 따라 스케줄러는 traceId를 발급하지 않는다.
- traceId가 null/invalid일 때 강제로 신규 UUID 생성 금지. 이유: ADR 결정 4에 따라 null 저장을 허용하고 Kafka 인터셉터에 fallback을 위임한다.
- Outbox 인덱스 추가 금지. 이유: trace_id는 조회 조건이 아니라 select-list에만 포함된다.
- 기존 retry/stale recovery 로직 변경 금지. 이유: 본 태스크 범위 밖이다.
- 기존 주석 삭제 금지.
- 기존 테스트를 깨뜨리지 마라.
