# Step 4: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 태스크 전체 맥락을 파악하라:

- `docs/tasks/kafka-trace-propagation/prd.md`
- `docs/tasks/kafka-trace-propagation/architecture.md`
- `docs/tasks/kafka-trace-propagation/adr.md`
- `docs/tasks/_templates/` — 기존 회고 파일 패턴 참조 (있는 경우)
- `docs/tasks/traceid-mdc-filter/retrospective.md` — 회고 작성 패턴 참조

## 작업

`docs/tasks/kafka-trace-propagation/retrospective.md` 파일을 신규 생성하라.

아래 섹션을 포함하라:

- **배경과 목표**: 왜 이 태스크가 필요했는지, 무엇을 달성하려 했는지
- **설계 결정 요약**: ADR-017의 핵심 결정과 근거 (ProducerInterceptor + RecordInterceptor 선택 이유)
- **구현 범위**: 생성/수정된 파일 목록과 각각의 역할
- **한계와 후속 과제**:
  - outbox relay 스케줄러 → consumer 흐름에서 원 HTTP 요청 traceId가 연결되지 않음
  - `@Async`, `@TransactionalEventListener` 비동기 경계 전파 미구현 (PR-2 범위)
  - `TraceIdConstants` 추출 보류 (MdcKeys 통합 리팩토링 PR에서 일괄 처리)
- **배운 점**: 작업 과정에서 발견한 기술적 사실이나 주의점

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. `docs/tasks/kafka-trace-propagation/retrospective.md`가 생성됐는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고 내용 소급 수정 금지. 이유: 회고는 당시 관점의 역사 기록이다.
- 기존 코드나 다른 task 문서 수정 금지
