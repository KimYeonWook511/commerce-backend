# Step 2: sync-root-docs

## 읽어야 할 파일

- `docs/tasks/traceid-mdc-filter/prd.md`
- `docs/tasks/traceid-mdc-filter/architecture.md`
- `docs/architecture.md` — 현재 로깅/요청 처리 절 확인

step1에서 생성된 파일:

- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`
- `src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java`

## 작업

`docs/architecture.md`의 로깅 또는 요청 처리 관련 절에 TraceIdFilter 도입 사실을 짧게 추가한다.

포함할 내용:
- Filter 클래스 위치: `com.commerce.common.log.filter.TraceIdFilter`
- 등록 방식: `FilterRegistrationBean`, order `Ordered.HIGHEST_PRECEDENCE + 10`
- 기능: 모든 요청에 UUID traceId를 MDC에 push, 응답 헤더 `X-Trace-Id` 추가
- JwtAuthenticationFilter(LOWEST_PRECEDENCE)보다 먼저 실행됨

기존 문서 구조를 유지하고, 이미 언급된 내용은 중복 작성하지 않는다.

## 수정 가능 경로

- `docs/architecture.md`
- `docs/tasks/traceid-mdc-filter/**` (task 문서)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. `docs/architecture.md`에 TraceIdFilter 관련 내용이 추가됐는가 확인.
2. 기존 문서 구조가 유지됐는가 확인.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/logging-conventions.md` 수정 금지. 이유: 이미 §8에서 MDC 운영 정책이 완전히 정의되어 있으며, 단일 진실의 원천 문서에 구현 디테일을 추가하면 책임 경계가 흐려짐
