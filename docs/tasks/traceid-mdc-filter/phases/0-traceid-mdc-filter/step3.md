# Step 3: write-retrospective

## 읽어야 할 파일

- `docs/tasks/traceid-mdc-filter/prd.md`
- `docs/tasks/traceid-mdc-filter/architecture.md`
- `docs/tasks/traceid-mdc-filter/adr.md`
- `docs/tasks/traceid-mdc-filter/phases/0-traceid-mdc-filter/index.json` — 완료된 step 요약 확인
- `docs/tasks/logback-setup/retrospective.md` — 회고록 작성 패턴 참고

## 작업

`docs/tasks/traceid-mdc-filter/retrospective.md`를 신규 작성한다.

포함할 내용:

### 배경 절
- 이슈 #129 맥락, Epic #133 내 위치(P2), P0/P1 완료 후 남은 과제
- 작업 전 상태: logback-spring.xml에 `%X{traceId:-}` 패턴이 있지만 항상 빈 문자열로 출력됨

### 결정사항 요약 표
adr.md의 "본 태스크 내부 결정 요약" 표를 기준으로 작성

### 진행 중 트레이드오프
결정 과정에서 비교 검토한 항목들:
- UUID v4 vs nanoid 21자: 의존성 vs 가독성
- TraceIdFilter 위치: `common/log/filter` vs `security/filter`
- `MDC.remove` vs `MDC.clear`: 단일 키 제거 vs 전체 clear
- `@Component` vs `FilterRegistrationBean` 단독 등록
- Filter order: `HIGHEST_PRECEDENCE` vs `HIGHEST_PRECEDENCE + 10`
- incoming traceId 검증 여부: 검증 없음 vs 패턴 검증

### 후속 작업 제안
- P3 #130 핵심 도메인 로깅 보강 — userId MDC push, 도메인 식별자(orderId/paymentId) MDC 확장
- P4 #131 Controller·Exception·외부 호출 로깅 표준화
- `@Async`, Kafka consumer, `@TransactionalEventListener` traceId 전파 (TaskDecorator, Kafka header propagation)
- 외부 HTTP 호출 시 X-Trace-Id out-bound 전파
- MdcKeys 상수 클래스 추출 (P3에서 키가 늘어나는 시점)

## 수정 가능 경로

- `docs/tasks/traceid-mdc-filter/retrospective.md` (신규)
- `docs/tasks/traceid-mdc-filter/**` (task 문서)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. `retrospective.md`가 배경, 결정사항, 트레이드오프, 후속 작업 제안 절을 포함하는가 확인.
2. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/tasks/traceid-mdc-filter/retrospective.md` 이외 다른 문서 수정 금지. 이유: 회고록은 역사 기록이며, 다른 문서 동기화는 step2에서 완료됨
