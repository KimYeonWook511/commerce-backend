# Step 7: write-retrospective

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/tasks/core-domain-logging/adr.md`
- `docs/tasks/core-domain-logging/phases/0-core-domain-logging/index.json` — 완료된 step 요약 확인
- `docs/tasks/traceid-mdc-filter/retrospective.md` — 회고록 작성 패턴 참고

## 작업

`docs/tasks/core-domain-logging/retrospective.md`를 신규 작성한다.

포함할 내용:

### 1. 배경 절
- 이슈 #130 맥락, Epic #133 내 위치(P3), P0~P2 완료 후 남은 과제
- 작업 전 상태: traceId·memberId가 MDC에 push되지만 application 계층 INFO 로그 없음

### 2. 결정사항 요약 표
adr.md의 결정 7가지(+추가 3가지) 표 형식 정리:
- task 이름 / 브랜치
- PR / step 분할 전략
- INFO 이벤트 사전 시그니처 박기
- DEBUG 미추가
- 단순 조회/위임 5개 서비스 제외
- Order 신규/멱등 분리
- Payment 신규/멱등 분리
- Member 이중 로그 (도메인 + 유스케이스)
- AuthSignUp 이메일 미포함
- OrderConcurrencyService strategy 라벨

### 3. 진행 중 트레이드오프
결정 과정에서 비교 검토한 항목:
- 단순 조회 5개 서비스 제외 vs "28개 모두 `@Slf4j`" 이슈 본문 기준
- Order 신규/멱등 — 분리 메시지 vs 통일 + status 필드 vs 멱등 무로그
- Member 이중 로그 — 둘 다 vs MemberRegistration만 vs AuthSignUp만
- OrderCreateProcessor를 작업 대상에 추가한 근거 (이슈 본문 18개 외)
- DEBUG 추가 여부 (외부 호출/SQL 전용으로 한정)

### 4. 후속 작업 제안
- P4 #132 운영 로그 파이프라인 (백로그) — 본 작업으로 INFO 볼륨 추정 가능해짐
- 비동기·이벤트 경계 traceId 전파 (`@Async`, Kafka consumer, `@TransactionalEventListener`) — Epic 후속
- 외부 HTTP 호출 out-bound `X-Trace-Id` 전파
- MdcKeys 상수 클래스 추출 (현재 MDC 키 2개: `traceId`, `memberId` — 키가 더 늘어나면)
- `OutboxService` 등 위임 layer를 정리할지 검토 (얇은 위임을 유지할지)

## 수정 가능 경로

- `docs/tasks/core-domain-logging/retrospective.md` (신규)
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. `retrospective.md`가 배경, 결정사항, 트레이드오프, 후속 작업 4개 절을 포함하는가 확인
2. 결정사항 표가 ADR과 일치하는가 확인
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/tasks/core-domain-logging/retrospective.md` 이외 다른 문서 수정 금지. 이유: 회고록은 역사 기록이며, 다른 문서 동기화는 step6에서 완료됨.
- 이미 작성된 task 문서(prd/architecture/adr) 사후 소급 수정 금지. 이유: 회고는 현재 시점 기록이며, 결정의 흐름을 보존해야 함.
- 코드 수정 금지. 이유: 본 step은 회고록만.
