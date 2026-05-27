# Step 4: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크 진행 내역을 파악하라:

- `docs/tasks/event-outbox-trace-propagation/prd.md`
- `docs/tasks/event-outbox-trace-propagation/architecture.md`
- `docs/tasks/event-outbox-trace-propagation/adr.md`
- `docs/tasks/event-outbox-trace-propagation/db-schema.md`
- `docs/tasks/event-outbox-trace-propagation/phases/0-core/index.json` — 이전 step의 summary 참고
- 참고 회고: `docs/tasks/kafka-trace-propagation/retrospective.md`

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

회고록을 작성한다. 위치: `docs/tasks/event-outbox-trace-propagation/retrospective.md`

다음 섹션을 포함한다:

### 배경과 목표

- 이슈 #146의 원래 의도와 본 태스크 진행 시점의 코드베이스 상태를 정리
- Kafka 경계는 #149로 선행 완료, @Async는 프로덕션 미사용이라 본 태스크는 @TransactionalEventListener와 Outbox 경계만 다룸
- 달성하려 한 것: 결제 승인 → outbox → kafka → consumer 흐름이 같은 traceId로 묶이는 것

### 설계 결정 요약

본 태스크 ADR의 4개 결정을 표로 압축 요약한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| Spring Event 경계 | 이벤트 객체에 traceId 동봉 | 사용처가 한 곳, Multicaster wrapping은 과한 추상화 |
| Outbox 경계 | DB 컬럼에 traceId 저장 | 원본 HTTP 요청의 traceId를 consumer까지 전파하는 유일한 방안 |
| 스케줄러 traceId 발급 | 발급하지 않음 | 배치 단위 traceId는 독립 이벤트들이 공유하게 되어 의미 희석 |
| traceId 없을 때 | null 저장 허용 | Kafka 인터셉터 fallback에 위임 |

### 구현 범위

각 step에서 신규/수정된 파일 목록을 정리한다.

### 한계와 후속 과제

- @Async 경계: 프로덕션 미사용으로 본 태스크에서 제외. 향후 도입 시 별도 작업.
- Spring Batch 경계: 이슈 #146에서 명시적 범위 밖. chunk별 traceId 의미 정리가 선행되어야 함.
- incoming X-Trace-Id 신뢰 경계 (#139): 게이트웨이 도입 시점 재검토.
- Outbox 운영 로그(스케줄러 selected/published 통계)는 여전히 traceId 없음. 운영 통계 로그 성격이므로 허용.

### 배운 점

- 이슈 작성 시점의 코드 상태와 진행 시점의 상태가 다를 수 있어 작업 범위를 재정의해야 했다.
- "스케줄러 traceId 발급"이라는 직관적 방안이 실제로는 traceId 의미를 희석시키는 함정이었다.
- Outbox 패턴에 traceId를 저장하는 결정은 단순 코드 변경이 아니라 DB 스키마 변경이 포함되어 ADR 등록이 필요한 결정이었다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/tasks/event-outbox-trace-propagation/retrospective.md`가 신규 생성되었는가
   - 본 태스크의 4개 ADR 결정이 회고에 압축 요약되었는가
   - 한계와 후속 과제가 명확히 기록되었는가
   - 기존 테스트가 모두 통과하는가
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 회고를 다른 회고 문서와 묶지 마라. 이유: 태스크별 독립 회고를 유지한다.
- 회고 작성 후 이전 step 결정을 소급 수정하지 마라. 이유: 회고는 역사 기록이다.
- 검증되지 않은 미래 작업을 회고에 단언하지 마라. "후속 과제" 섹션의 항목은 가능성으로만 표기한다.
