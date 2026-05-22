# 태스크 ADR

## 결정 제목

- 본 태스크는 별도 ADR 항목을 추가하지 않는다.

## 배경

- 이번 태스크의 핵심 결정(traceId MDC 운영 정책, 키명 `traceId`, Filter 책임, MDC.clear 금지)은 이미 머지된 `docs/logging-conventions.md §8`이 결정 기록 역할을 한다.
- TraceIdFilter는 그 정책의 implementation이며, ADR로 따로 기록할 새로운 설계 결정이 없다.
- logback-setup 태스크에서 동일한 정책을 적용한 선례가 있다(`docs/tasks/logback-setup/adr.md` 참고).

## 결정 내용

- 루트 `docs/ADR.md`에 신규 ADR을 추가하지 않는다.
- 본 태스크 내부에서 내린 구현 차원의 결정은 `prd.md`와 `architecture.md`에 기록한다.

## 근거

- ADR은 "되돌리기 어려운 설계 결정"을 다루는 문서다. 컨벤션 문서가 이미 그 역할을 하는 상황에서 ADR을 중복 추가하면 단일 진실의 원천 원칙에 어긋난다.

## 본 태스크 내부 결정 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| traceId 생성 방식 | UUID v4 36자 (java.util.UUID) | 외부 의존성 0. 분산 추적 표준(128-bit)과 호환 |
| Filter 패키지 | `com.commerce.common.log.filter` | MaskingMessageJsonProvider와 같은 곳에 로깅 인프라 응집 |
| Filter 등록 방식 | FilterRegistrationBean (Config Bean) | @Component 중복 등록 방지 + order 명시 |
| Filter order | `Ordered.HIGHEST_PRECEDENCE + 10` | JwtAuthenticationFilter(LOWEST_PRECEDENCE) 전 실행 보장 |
| incoming 헤더 검증 | `^[A-Za-z0-9_-]{1,64}$` | 로그 인젝션 차단 + UUID/일반 alphanum 허용 |
| MDC.remove vs MDC.clear | `MDC.remove("traceId")` 사용 | 향후 P3/P4에서 추가될 다른 MDC 키를 같이 날리는 위험 차단 |
| CORS expose 헤더 | 추가 없음 | 프로젝트 CORS 설정 미사용(확인됨) |
