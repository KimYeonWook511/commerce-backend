# Step 4: outbox-find-first

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxConsumeService.java`
- `/src/main/java/com/commerce/outbox/domain/ProcessedEvent.java`
- `/src/main/java/com/commerce/outbox/domain/ProcessedEventConsumerType.java`
- `/src/main/java/com/commerce/outbox/domain/repository/ProcessedEventRepository.java`
- `/src/main/java/com/commerce/outbox/infrastructure/ProcessedEventRepositoryAdapter.java`
- `/src/main/java/com/commerce/outbox/infrastructure/JpaProcessedEventRepository.java`
- `/src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxConsumeServiceTest.java`

step 3 이 끝나 있어야 한다.

## 작업

`StockRestoreOutboxConsumeService.markProcessed` 를 **try-save-catch-skip** → **find-first** 패턴으로 리팩토링한다. race 시 unique 위반은 안전망 500 으로 도달하며, Kafka consumer 재시도로 복구된다.

### 1. `ProcessedEventRepository` 에 사전 체크 메서드 확보

먼저 도메인 인터페이스에 `existsByEventIdAndConsumerType(String eventId, ProcessedEventConsumerType consumerType)` 메서드가 있는지 확인한다.

- 이미 있으면 그대로 재사용한다.
- 없으면 다음 세 곳에 모두 추가한다:
  - `outbox/domain/repository/ProcessedEventRepository.java` — 도메인 인터페이스 메서드 시그니처
  - `outbox/infrastructure/ProcessedEventRepositoryAdapter.java` — `jpaProcessedEventRepository` 위임
  - `outbox/infrastructure/JpaProcessedEventRepository.java` — Spring Data 메서드 (`boolean existsByEventIdAndConsumerType(String eventId, ProcessedEventConsumerType consumerType);`)
- 기존에 동일한 의미의 메서드(예: `findByEventIdAndConsumerType().isPresent()` 형태) 가 있다면 그것을 활용해도 된다. 단 본 step 의 흐름은 boolean 반환이 가장 간결하다.

### 2. `markProcessed` 리팩토링

기존 구조 (라인 41-49):

```java
private boolean markProcessed(String eventId) {
    try {
        ProcessedEvent processedEvent = ProcessedEvent.create(eventId, CONSUMER_TYPE);
        processedEventRepository.save(processedEvent);
        return true;
    } catch (DuplicateKeyException ex) {
        return false;
    }
}
```

새 구조:

```java
private boolean markProcessed(String eventId) {
    if (processedEventRepository.existsByEventIdAndConsumerType(eventId, CONSUMER_TYPE)) {
        return false;
    }
    ProcessedEvent processedEvent = ProcessedEvent.create(eventId, CONSUMER_TYPE);
    processedEventRepository.save(processedEvent);
    return true;
}
```

- `import org.springframework.dao.DuplicateKeyException;` 임포트 제거.
- race 시 두 consumer 가 모두 `existsBy(false)` 통과 후 한쪽 save 성공, 다른 쪽 unique 위반 → 안전망 500. Kafka 재시도로 복구 가능하므로 영향 적음.

### 3. 단위 테스트 갱신 (`StockRestoreOutboxConsumeServiceTest.java`)

- 라인 64-81 의 `consume_whenDuplicatedMessage_skipRestoreStock` 케이스: `DuplicateKeyException` mock 을 `existsByEventIdAndConsumerType` mock 으로 교체한다.
  - `existsByEventIdAndConsumerType` true → save 호출 안 됨, `restoreStock` 호출 안 됨, 메서드 결과 `consume` 흐름이 정상 종료
  - `existsByEventIdAndConsumerType` false → save 호출, `restoreStock` 호출, 정상 처리 검증
- 기존 verify(repository).save(...) 케이스도 새 분기에 맞춰 갱신한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `StockRestoreOutboxConsumeService` 에 `org.springframework.dao.DuplicateKeyException` 임포트가 없는가?
   - `existsByEventIdAndConsumerType` 가 도메인 인터페이스 + Adapter + Jpa 모두에 일관되게 정의되어 있는가? (혹은 기존 메서드 재사용 명확한가?)
   - 단위 테스트가 새 분기 시나리오로 갱신되어 모두 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- consumer 재시도 정책(Kafka level) 을 본 step 에서 변경하지 마라. 이유: 본 step 범위 밖. race window 안전망 500 은 Kafka 재시도로 자연스럽게 복구된다는 전제만 사용한다.
- `ProcessedEvent.create` 시그니처를 변경하지 마라. 이유: 다른 outbox consumer 도 사용한다.
- `consume` 메서드의 `Skip duplicated stock restore event` 로깅 메시지를 변경하지 마라. 이유: 운영 모니터링과 알람 매칭이 깨질 수 있다.
- 기존 테스트를 깨뜨리지 마라.
