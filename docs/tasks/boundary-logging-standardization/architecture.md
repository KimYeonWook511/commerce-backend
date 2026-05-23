# 태스크 아키텍처

## 개요

이 태스크는 시스템 외부 경계 3곳의 로깅을 컨벤션에 맞춘다. 비즈니스 로직과 데이터 모델은 건드리지 않으며, presentation 진입부와 infrastructure 외부 호출의 로깅 책임만 정비한다.

## 변경 대상

| 레이어 | 모듈 | 변경 내용 |
|---|---|---|
| Common (Filter) | `common/log/filter/` | `AccessLogFilter`, `AccessLogFilterConfig` 신규 |
| Common (Exception) | `common/exception/GlobalExceptionHandler` | 9개 핸들러 로그 레벨/스택 정비 |
| Infrastructure (PG) | `payment/naverpay/infrastructure/NaverPayGatewayImpl` | 메시지 한국어화 + 표준 라운드트립 |

## 설계 방향

### Filter 책임 분리

기존 `TraceIdFilter`는 MDC traceId push/remove + `X-Trace-Id` 응답 헤더 부착만 담당한다. 본 작업으로 추가되는 `AccessLogFilter`는 액세스 로그(요청 시작/종료) 작성만 담당한다. 두 Filter를 합치지 않는 이유는 단일 책임을 명확히 하고, 향후 액세스 로그 형식 변경이 traceId 관리 코드와 결합되지 않도록 하기 위함이다.

Filter 실행 순서:
1. `TraceIdFilter` (`HIGHEST_PRECEDENCE + 10`) — traceId 발급·MDC push
2. `AccessLogFilter` (`HIGHEST_PRECEDENCE + 20`) — 액세스 로그. 이 시점에는 MDC에 traceId가 있어 logback 패턴이 자동 부착
3. (이후) `JwtAuthenticationFilter` — 인증, memberId 식별 (MDC push는 P3 작업)

### 예외 핸들러 레벨 정책

컨벤션 §4 표를 그대로 코드에 반영한다. 핵심은 "5xx인가 4xx인가"를 status code 기준으로 명확히 분기하고, 4xx CustomException은 모두 무로그로 두는 것이다. 운영 노이즈를 줄이고, 진짜 시스템 장애만 ERROR로 알려 운영 모니터링이 신호와 잡음을 구분할 수 있게 한다.

`OptimisticLockingFailureException`만 4xx(409)임에도 WARN으로 남기는 예외다. 컨벤션 §4 표에서 명시적으로 WARN으로 분류했고, 동시 수정 충돌은 비즈니스 의미가 있어 운영 모니터링이 빈도를 추적할 가치가 있다.

### NaverPay 라운드트립

외부 호출 1회당 다음 패턴을 따른다.

- **요청 진입**: `log.info("네이버페이 {} 요청 ...", action)` — 호출 직전 INFO
- **응답 처리**: 성공 코드면 `log.info("네이버페이 {} 응답 ...", action)`, 실패 코드면 `log.warn("네이버페이 {} 실패 ...", action)`
- **호출 자체 실패**(네트워크/타임아웃 등 `NaverPayException`): `log.warn("네이버페이 {} 호출 실패 ...", action)`

action은 `승인`·`취소`·`이력조회` 세 가지다. 메시지 형식을 통일해 grep으로 "네이버페이 승인" 같이 추적 가능하게 한다.

## 데이터 흐름

```
HTTP request
  ↓
TraceIdFilter (MDC traceId push)
  ↓
AccessLogFilter (log "요청 시작 ...")
  ↓
JwtAuthenticationFilter (인증, memberId 식별)
  ↓
Controller → Service → Domain → Repository
  ↓ (정상 또는 예외)
GlobalExceptionHandler (예외 시 status/level/stack 결정)
  ↓
AccessLogFilter (log "요청 종료 status=... latency=...ms")
  ↓
TraceIdFilter (MDC traceId remove)
  ↓
HTTP response
```

NaverPay 호출은 Service → NaverPayGateway 인터페이스 → `NaverPayGatewayImpl` → `NaverPayClient`로 전달되며, Gateway 구현체가 라운드트립 로그를 남긴다.

## 예외 및 실패 처리

- AccessLogFilter는 `try ... finally` 블록으로 종료 로그를 보장한다. 다운스트림에서 RuntimeException이 전파되더라도 종료 로그가 누락되지 않는다.
- GlobalExceptionHandler 핸들러 자체에서 발생하는 2차 예외는 없도록 모든 핸들러가 단순 분기만 수행한다.
- NaverPayGatewayImpl은 `NaverPayException` catch 후 결과 객체를 반환하며 예외를 위로 전파하지 않는다. 즉 호출자(`PaymentService`)는 결과 객체로 분기. 본 작업은 이 정책을 유지한다.

## 테스트 포인트

- AccessLogFilter
  - 정상 200 → "요청 시작" + "요청 종료 status=200" 2건
  - 4xx 응답 → "요청 종료 status=4xx"
  - 5xx 응답 → "요청 종료 status=5xx" (다운스트림 예외 전파 시에도 종료 로그 보장)
  - duration > 0
  - traceId가 MDC에 push된 상태에서 로그 작성됨 (logback 패턴이 부착)
- GlobalExceptionHandler
  - 4xx CustomException → 핸들러 로그 없음
  - 5xx CustomException → ERROR + stack
  - `MethodArgumentNotValidException` → 무로그
  - `HttpMessageNotReadableException` → 무로그
  - `OptimisticLockingFailureException` → WARN no-stack
  - `DataIntegrityViolationException` → ERROR + stack
  - `DataAccessException` → ERROR + stack
  - `Exception` (안전망) → ERROR + stack (기존 버그 수정)
- NaverPayGatewayImpl
  - approve 정상 → 요청 INFO + 응답 INFO 2건
  - approve 실패 코드 → 요청 INFO + 실패 WARN
  - approve 호출 예외 → 요청 INFO + 호출 실패 WARN
  - cancel/history 동일 패턴
  - 메시지가 한국어 + placeholder `{}` 사용
