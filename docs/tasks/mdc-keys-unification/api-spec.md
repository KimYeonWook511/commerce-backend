# 태스크 API 스펙

## 개요

본 태스크는 외부에 노출되는 HTTP API와 Kafka 메시지 계약을 변경하지 않는다. 내부 식별자(`private static final` 상수, 리터럴) 정리만 수행한다.

## 변경 없음

- HTTP 요청 처리 경로: `TraceIdFilter`가 읽고 응답에 set하는 헤더 이름은 `X-Trace-Id` 그대로다.
- traceId 유효성 정규식: `^[A-Za-z0-9_-]{1,64}$` 그대로다. 유효하지 않으면 신규 UUID를 발급하는 동작도 동일하다.
- Kafka 메시지 헤더: producer가 `X-Trace-Id` 헤더를 부착하고 consumer가 동일 헤더에서 읽는 동작 동일.
- MDC 키 값: `traceId`, `memberId` 문자열 그대로. logback 패턴 `%X{traceId:-}`, `%X{memberId:-}`도 그대로 동작.
- 응답/요청 페이로드 구조: 변경 없음.

## 호환성

본 리팩토링은 컴파일러 차원의 정리이며 런타임 외부 노출은 변하지 않는다. 기존 클라이언트(Frontend, 외부 시스템, 옵저버빌리티 도구)는 영향이 없다.
