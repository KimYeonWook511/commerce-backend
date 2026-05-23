# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/boundary-logging-standardization/prd.md`
- `/docs/tasks/boundary-logging-standardization/architecture.md`
- `/docs/tasks/boundary-logging-standardization/adr.md`
- `/docs/architecture.md` — 갱신 대상
- 이전 step 결과: AccessLogFilter, GlobalExceptionHandler, NaverPayGatewayImpl 변경분

## 작업

`docs/architecture.md`의 HTTP 요청 처리 Filter 절에 `AccessLogFilter`를 추가한다.

P2(#129)에서 이미 TraceIdFilter가 해당 절에 기록되어 있을 것이므로, 같은 절에 AccessLogFilter 항목을 추가한다.

추가할 내용:
- 클래스 위치: `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`
- 등록 방식: `FilterRegistrationBean` (`AccessLogFilterConfig`)
- order: `Ordered.HIGHEST_PRECEDENCE + 20` (TraceIdFilter +10 다음)
- 책임: 요청 시작/종료 액세스 로그 2건 (method, path, status, latency). traceId/memberId는 MDC를 통해 logback 패턴이 자동 부착.
- 인증 Filter 이전 실행 — 미인증 요청도 액세스 로그를 남기며, memberId는 인증 이후 채워지므로 시작 로그 시점에는 빈 값일 수 있음.

만약 `docs/architecture.md`에 Filter 절이 없다면 P2의 PR(#136) 변경분을 확인해 어디에 추가되었는지 보고 동일 위치에 둔다.

`GlobalExceptionHandler` 로깅 정책 변경과 NaverPay 메시지 변경은 별도 문서 갱신이 필요하지 않다 (컨벤션 문서 `docs/logging-conventions.md`가 이미 모든 정책을 정의하고 있으므로 코드 변경만으로 충분).

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/architecture.md`에 AccessLogFilter 항목이 추가되었는가?
   - 클래스 경로, 등록 방식, order, 책임이 정확히 기술되었는가?
   - 다른 절을 손대지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/logging-conventions.md`를 수정하지 마라. 이유: 컨벤션 자체는 P0(#127)에서 확정되었으며 본 작업은 코드가 컨벤션을 따르도록 하는 작업이다.
- 코드를 수정하지 마라. 이유: 본 step은 문서 동기화만이다.
- 기존 테스트를 깨뜨리지 마라.
