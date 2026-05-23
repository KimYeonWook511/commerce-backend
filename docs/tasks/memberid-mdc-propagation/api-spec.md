# 태스크 API 스펙

## 개요

- 이 태스크는 신규 또는 변경된 HTTP API가 없다. Filter 내부 동작과 로그 출력만 변경한다.

## 엔드포인트

- 해당 없음.

## 요청

- 해당 없음.

## 응답

- 해당 없음.
- 단, 응답 헤더 `X-Trace-Id`는 `TraceIdFilter` 기존 동작 그대로 유지된다.

## 검증 규칙

- 해당 없음.

## 비고

- 인증/인가 동작은 기존 그대로 유지된다.
- Filter chain 순서 변경 없음 (TraceId → AccessLog → JwtAuth 순). `JwtAuthenticationFilter`의 등록 방식만 `@Component` → `FilterRegistrationBean`으로 이전되며, 결과적 실행 순서는 동일.
- 로그 출력 변화: 인증된 요청의 모든 로그(도메인 + access log "요청 종료")에 `memberId=<숫자>`가 채워짐. 비인증 요청과 인증 실패 요청의 로그는 `memberId=` 빈 값 유지.
