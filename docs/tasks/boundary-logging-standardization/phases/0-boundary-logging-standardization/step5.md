# Step 5: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/boundary-logging-standardization/prd.md`
- `/docs/tasks/boundary-logging-standardization/architecture.md`
- `/docs/tasks/boundary-logging-standardization/adr.md`
- `/docs/tasks/boundary-logging-standardization/api-spec.md`
- `/docs/tasks/boundary-logging-standardization/db-schema.md`
- 이전 step 1~4의 모든 변경분
- `/docs/tasks/traceid-mdc-filter/retrospective.md` — 회고 형식 참조 (있다면)

## 작업

`docs/tasks/boundary-logging-standardization/retrospective.md`를 신규 작성한다.

### 포함 절

- **배경**: 이슈 #131(Epic #133 P4), 작업 전 상태(Controller 무로그, GlobalExceptionHandler 4xx/5xx 무차별 ERROR, NaverPay 영문 메시지).
- **결정사항 요약**: ADR 4건을 한 문장씩 요약한 표.
- **진행 중 트레이드오프**:
  - AccessLogFilter 분리 vs TraceIdFilter 통합 — 단일 책임 vs 보일러플레이트
  - 4xx 분기 방식 (화이트리스트 vs 메타데이터 vs 일괄 무로그) — 운영 데이터 부재로 무로그 선택
  - path 제외 (YAGNI 적용)
  - NaverPay 호출 실패 레벨 (Gateway vs 호출자 책임 분리)
  - cancelReason 로그 제외 (PII 보수적 처리)
  - body 로깅 미지원
- **후속 작업 제안**:
  - 4xx WARN 분류 (운영 데이터 누적 후)
  - body 로깅 DEBUG 옵션 (디버깅 필요 시)
  - memberId MDC push (P3 작업 또는 인증 Filter 확장)
  - actuator 도입 시 액세스 로그 path 제외 추가
  - 비동기/Kafka traceId 전파
- **검증 결과**: `./gradlew test` 통과, 수동 검증(API 호출 후 로그 형식 확인)

### 형식

본 회고는 사후 소급 수정하지 않는다(메모리: feedback_retrospective_immutable). 작성 시점의 결정과 트레이드오프를 그대로 기록한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 회고 문서가 작성되었는가?
   - 결정사항 4건과 트레이드오프가 모두 기록되었는가?
   - 후속 작업 제안이 PRD/ADR의 제외 범위와 일치하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 코드를 수정하지 마라. 이유: 본 step은 회고 문서 작성만이다.
- 이전 step 회고 문서를 사후 수정하지 마라 (예: traceid-mdc-filter retrospective). 이유: 회고는 역사 기록이며 사후 소급 수정 금지.
- 기존 테스트를 깨뜨리지 마라.
