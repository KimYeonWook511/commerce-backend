# Step 3: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `docs/tasks/event-outbox-trace-propagation/prd.md`
- `docs/tasks/event-outbox-trace-propagation/architecture.md`
- `docs/tasks/event-outbox-trace-propagation/adr.md`
- `docs/logging-conventions.md` — §8 비동기·이벤트 경계 절 갱신 대상
- `docs/ADR.md` — 새 ADR 항목 추가 대상
- `docs/architecture.md` — 비동기 경계 절 갱신 대상 (선택)
- `docs/db-schema.md` — `tbl_outbox_event` 컬럼 변경 반영 대상

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

본 태스크의 설계 결정과 구현 결과를 루트 docs에 동기화한다.

### 수정 파일 1: `docs/logging-conventions.md`

§8 "비동기·이벤트 경계의 traceId 전파" 절을 갱신한다. 갱신 후 절의 하위 구성은 다음과 같다.

#### 1. 적용 경계 (구현 완료)

- **Kafka 경계** — 기존 내용 유지
- **`@TransactionalEventListener(AFTER_COMMIT)` 경계** — 신규 추가
  - 이벤트 객체에 `traceId` 필드 동봉
  - publisher가 발행 시점의 `LogContext.getTraceId()`를 전달
  - listener 진입 시 유효성 검증 후 MDC push, finally에서 remove
  - `@Async`와 달리 같은 스레드에서 호출되지만 트랜잭션 경계를 넘으므로 명시적 전파 채택
- **Outbox 경계** — 신규 추가
  - `tbl_outbox_event.trace_id` 컬럼에 생성 시점의 traceId 저장
  - relay 시 컬럼 값을 MDC로 복원, Kafka producer interceptor가 자동으로 헤더에 전파
  - null인 경우 Kafka 인터셉터가 신규 UUID 발급 (fallback)

#### 2. 미적용 경계 (정책상 제외)

다음 경계에는 traceId를 의도적으로 적용하지 않는다. 이는 누락이 아니라 의식적인 정책 결정이다.

- **Outbox 스케줄러 자체 로그** (`StockRestoreOutboxScheduler`)
  - 적용 안 함. 이유: 한 번의 스케줄러 실행에서 여러 독립 outbox 이벤트를 배치 처리하므로, 실행 단위 traceId를 부여하면 독립 거래들이 같은 traceId를 공유하게 되어 의미가 희석된다.
  - 운영 통계 로그(`selected=5 published=3` 등) 성격이며, 개별 이벤트 처리는 outbox에 저장된 traceId로 추적된다.
- **Spring Batch** (`OrderExpirationJob` 등)
  - 적용 안 함. 이유: chunk별 traceId의 의미가 모호하다(chunk 단위? item 단위? job 실행 단위?). 운영상 필요 판단 시 별도 작업으로 분리한다.
- **`@Async`**
  - 현재 프로덕션 코드에서 미사용. 도입 시점에 `TaskDecorator` 방식으로 별도 작업한다.

#### 3. 신규 비동기 경계 추가 시 가이드

새 비동기/이벤트 경계를 도입할 때 다음 기준으로 traceId 적용 여부를 판단한다.

- **요청 단위로 거래 흐름을 추적해야 하는가?** → 적용
- **여러 독립 거래를 묶는 배치성 작업인가?** → 미적용 (운영 통계 로그 성격)
- **호출 스레드의 MDC가 자동 전파되는가?** → 자동 전파되면 별도 작업 불필요

### 수정 파일 2: `docs/ADR.md`

새 ADR 항목을 **하나** 추가한다. 본 태스크의 4개 결정을 하나의 ADR로 묶는다 (Kafka traceId 전파 ADR-017의 패턴과 일관성 유지). 상세는 본 태스크 ADR 문서로 위임한다.

다음 번호 확인: `rg "^### ADR-" docs/ADR.md | tail -n 1` (현재 마지막은 ADR-018이므로 ADR-019)

ADR 본문 구성:

- **제목**: `ADR-019: 비동기/이벤트 경계 traceId 전파는 명시적 동봉 방식으로 구현한다`
- **결정**: Spring Event 경계는 이벤트 객체에 traceId 필드를 동봉, Outbox 경계는 `tbl_outbox_event.trace_id` 컬럼에 저장 후 relay 시 MDC 복원. 두 경계 모두 publisher 시점의 MDC traceId를 명시적으로 전달한다.
- **배경**: ADR-017(Kafka traceId 전파)로 Kafka 경계는 해결됐으나, `@TransactionalEventListener(AFTER_COMMIT)`과 Outbox relay 경계에서는 여전히 traceId가 단절되었다. Spring Event는 (A) 이벤트 객체 동봉, (B) `ApplicationEventMulticaster` wrapping을 비교했다. Outbox는 (A) 스케줄러 단위 traceId 발급, (B) DB 컬럼 저장, (C) 현행 유지를 비교했다.
- **이유**: Spring Event는 사용처가 한 곳뿐이라 wrapping은 과한 추상화다. Outbox는 원본 HTTP 요청의 traceId를 consumer까지 전파하는 유일한 방안이 DB 컬럼 저장이다. 스케줄러 단위 traceId는 한 실행에서 여러 독립 거래가 같은 traceId를 공유하게 되어 의미가 희석된다.
- **트레이드오프**: Outbox 스케줄러 자체 로그는 traceId가 없다(운영 통계 로그 성격이므로 허용). traceId가 없거나 유효하지 않은 케이스는 outbox.trace_id를 null로 저장하고 relay 시 MDC 조작 없이 진행한다(기존 데이터 호환, Kafka 인터셉터가 신규 UUID fallback). Spring Event가 늘어나면 반복 작업 부담이 생기며 5개 이상 시점에 Multicaster wrapping으로 재검토한다.
- 상세는 `docs/tasks/event-outbox-trace-propagation/adr.md` 참조.

### 수정 파일 3: `docs/db-schema.md`

`tbl_outbox_event` 항목에 `trace_id VARCHAR(64) NULL` 컬럼 추가를 반영한다.

- 컬럼 설명: outbox 생성 시점의 MDC traceId. relay 시 MDC로 복원되어 Kafka 헤더로 전파됨.

### 수정 파일 4 (선택): `docs/architecture.md`

만약 `docs/architecture.md`에 "비동기 경계 traceId 전파" 또는 유사한 절이 있으면 본 태스크 결과를 반영한다. 없으면 본 step에서는 추가하지 않는다(별도 작업).

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다(문서만 수정해도 기존 테스트 회귀가 없는지 확인하기 위함).
2. 아래를 확인한다.
   - `docs/logging-conventions.md` §8가 @TransactionalEventListener / Outbox 구현 완료를 반영하는가
   - `docs/ADR.md`에 새 ADR 항목이 추가되고 본 태스크의 4개 결정이 요약되었는가
   - `docs/db-schema.md`의 `tbl_outbox_event`에 `trace_id` 컬럼이 반영되었는가
   - 기존 테스트가 모두 통과하는가
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고 문서(`docs/ddd/*.md`) 수정 금지. 이유: 회고는 역사 기록이며 사후 소급 수정하지 않는다.
- 본 태스크와 무관한 docs 갱신 금지. 이유: 별도 PR 범위가 흐려진다.
- 새 컨벤션을 함부로 `CLAUDE.md`에 추가 금지. 이유: ADR/관련 문서에서 관리한다.
- 기존 문서의 다른 절을 무관하게 수정 금지.
