# Step 3: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 변경 범위를 파악하라:

- `docs/tasks/kafka-trace-propagation/prd.md`
- `docs/tasks/kafka-trace-propagation/adr.md`
- `docs/adr.md` — 신규 ADR 항목 추가 위치 확인 (현재 마지막 항목: ADR-016)
- `docs/logging-conventions.md` — §8 비동기·이벤트 경계 traceId 전파 절 갱신 위치 확인
- `docs/architecture.md` — "비동기 경계와 traceId 전파" 절 신규 추가 위치 확인

## 작업

### 1. `docs/adr.md` — ADR-017 신규 항목 추가

기존 `ADR-016` 항목 다음에 아래 항목을 추가하라.

```
### ADR-017: Kafka traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 구현한다
- **결정**: Kafka producer가 메시지를 발행할 때 `TraceIdKafkaProducerInterceptor`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착하고, consumer가 수신할 때 `TraceIdRecordInterceptor`가 헤더에서 traceId를 추출해 MDC에 push한다.
- **배경**: HTTP 요청 단위 traceId(ADR-N, traceid-mdc-filter)가 Kafka 경계에서 단절되어 producer-consumer 흐름 추적이 불가능했다. 해결 방법으로 (A) 헤더 직접 부착(producer/consumer 코드 수정), (B) Spring Kafka 표준 확장점(ProducerInterceptor + RecordInterceptor)을 비교했다.
- **이유**: (B)가 producer/consumer 코드 시그니처를 무손상으로 유지하고, 향후 추가되는 producer/consumer에도 자동 적용된다. `DefaultKafkaProducerFactoryCustomizer` Bean 등록 방식은 `application.yml` 프로퍼티 방식 대비 프로파일별 누락 위험이 없다. `RecordInterceptor.success`/`failure` 콜백은 Spring Kafka 2.7+ 계약상 정확히 하나만 호출되므로 MDC 정리가 보장된다.
- **트레이드오프**: outbox relay 스케줄러 → consumer 흐름에서 원 HTTP 요청 traceId와 consumer 로그가 연결되지 않는다. 이 연결은 OutboxEvent에 traceId 컬럼 추가가 필요하며 별도 후속 작업으로 분리된다. 상세는 `docs/tasks/kafka-trace-propagation/adr.md` 참조.
```

### 2. `docs/logging-conventions.md` — §8 비동기·이벤트 경계 절 갱신

`## 8. MDC 운영` 절 내의 `### 비동기·이벤트 경계의 traceId 전파` 서브섹션을 아래로 교체하라.

현재 내용:
```
### 비동기·이벤트 경계의 traceId 전파
`@Async`, Kafka consumer, `@TransactionalEventListener(AFTER_COMMIT)` 등에서는 호출 스레드의 MDC가 자동 전파되지 않는다. 구체적인 전파 방식(`TaskDecorator`, header propagation 등)은 별도 후속 작업에서 다룬다.
```

교체할 내용:
```
### 비동기·이벤트 경계의 traceId 전파

#### Kafka 경계 (구현 완료)

Kafka producer/consumer 경계는 `ProducerInterceptor` + `RecordInterceptor` 조합으로 traceId를 전파한다.

- **producer**: `TraceIdKafkaProducerInterceptor.onSend()`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착. MDC에 유효한 traceId가 없으면 신규 UUID 발급.
- **consumer**: `TraceIdRecordInterceptor.intercept()`가 헤더 `X-Trace-Id`를 읽어 MDC에 push. 헤더가 없거나 유효하지 않으면 신규 UUID 발급. `success`/`failure` 콜백에서 `MDC.remove("traceId")`로 정리.
- **등록**: `TraceIdKafkaConfig` — `DefaultKafkaProducerFactoryCustomizer` Bean(producer factory) + `TraceIdRecordInterceptor` Bean(consumer factory 주입)
- **DLT**: `DeadLetterPublishingRecoverer`가 동일 KafkaTemplate을 사용하므로 DLT 발행 시에도 traceId 헤더 자동 전파.

#### @Async, @TransactionalEventListener 경계 (미구현)

`@Async` 및 `@TransactionalEventListener(AFTER_COMMIT)`의 비동기 전환 시 MDC 전파는 별도 후속 작업에서 다룬다(`TaskDecorator`, `ApplicationEventMulticaster` wrapping 등).
```

### 3. `docs/architecture.md` — "비동기 경계와 traceId 전파" 절 신규 추가

로깅/MDC 관련 절 또는 가장 적절한 위치에 아래 절을 추가하라. 정확한 위치는 `docs/architecture.md`를 읽고 기존 구조에 맞게 판단한다.

```
### 비동기 경계와 traceId 전파

HTTP 요청 단위 traceId는 `TraceIdFilter`가 MDC에 push하지만, 비동기 경계에서는 스레드 로컬인 MDC가 자동 전파되지 않는다.

#### Kafka 경계

```
HTTP 요청 → TraceIdFilter → MDC.put("traceId", uuid)
   ↓
StockRestoreKafkaEventProducer.send()
   ↓
TraceIdKafkaProducerInterceptor.onSend()
  headers.add("X-Trace-Id", MDC.get("traceId") or 신규 UUID)
   ↓
[Kafka broker: stock-restore-events topic]
   ↓
TraceIdRecordInterceptor.intercept()
  MDC.put("traceId", headers.get("X-Trace-Id"))
   ↓
StockRestoreKafkaEventConsumer.consume()
  [동일 traceId로 로그 출력]
   ↓
TraceIdRecordInterceptor.success()/failure()
  MDC.remove("traceId")
```

outbox relay 스케줄러는 HTTP 요청 컨텍스트가 없으므로 publish 시 신규 UUID가 발급된다. 원 HTTP 요청 traceId와의 연결은 OutboxEvent 컬럼 추가 별도 후속 작업이다.
```

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/adr.md`에 ADR-017 항목이 추가됐는가?
   - `docs/logging-conventions.md` §8의 Kafka 전파 내용이 갱신됐는가?
   - `docs/architecture.md`에 "비동기 경계와 traceId 전파" 절이 추가됐는가?
   - 기존 테스트가 통과하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 기존 ADR 항목 수정 금지. 이유: ADR은 결정 당시 컨텍스트를 보존하는 역사 기록이다.
- `docs/tasks/kafka-trace-propagation/` 하위 문서 수정 금지. 이유: 이 step은 루트 docs만 갱신한다. task 문서는 Execution Authorization 커밋에서 이미 확정된다.
- 워크스페이스 공유 문서(`../docs/api-contract.md`, `../docs/progress.md`) 수정 금지. 이유: Backend 세션 책임 범위 밖이다.
