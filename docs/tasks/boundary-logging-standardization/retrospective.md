# boundary-logging-standardization 회고

## 배경

이 작업은 이슈 #131로 진행한 시스템 외부 경계 로깅 표준화 태스크다. 로깅 Epic #133의 P4 작업으로, P0(로깅 컨벤션 문서, #127), P1(logback 설정, #128), P2(traceId MDC Filter, #129)이 모두 머지된 상태에서 진행했다.

P0~P2까지 컨벤션 문서와 logback 인프라, traceId 발급/MDC push가 모두 준비되어 있었으나, 시스템 외부 경계 3곳이 컨벤션을 따르지 않아 traceId 인프라의 효과를 실감하기 어려운 상태였다.

작업 전 저장소 상태는 다음과 같았다.

- **Controller 7개 전부 로그 없음** → HTTP 요청 단위 운영 추적 불가. traceId가 MDC에 push되어 있어도 어떤 요청이 들어왔는지 자체가 로그에 안 남았다.
- **`GlobalExceptionHandler` 9개 핸들러가 4xx/5xx 무차별 `log.error`** → prod에서 일상적인 400/404가 ERROR로 도배되어 신호와 잡음을 구분할 수 없었다. `handleException` 안전망은 stack trace 누락 버그(메시지에 `ex.getMessage()`만 끼워 넣음)까지 있었다.
- **`NaverPayGatewayImpl` 영문 메시지 7건 + 라운드트립 패턴 비일관** → 컨벤션 §7(한국어) 위반. 외부 호출 추적이 메서드별로 형식이 달라 grep으로 일관 추적이 어려웠다.

P3(#130 도메인 로깅 보강)와 병렬로 진행 가능한 작업이라 이슈 #131 단독으로 시작했다. 본 작업이 완료되면 traceId 인프라가 외부 경계에서 실제로 가시화되어 운영 모니터링이 작동하는 단계로 넘어간다.

---

## 결정사항 요약

ADR 4건을 본 태스크의 `adr.md`에 기록했다. 회고록에서는 결과만 인용한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| AccessLogFilter 분리 | TraceIdFilter와 별도 클래스 (`HIGHEST_PRECEDENCE + 20`) | 단일 책임 — MDC 관리와 액세스 로그 작성을 분리해 향후 형식 변경 영향 범위를 한정 |
| 4xx CustomException 정책 | 모두 무로그, WARN 분류는 후속 작업으로 미룸 | 컨벤션 §4가 "잠정 목록" 상태 — 운영 데이터 없이 화이트리스트/메타데이터를 만들면 dead code 위험 |
| 액세스 로그 path 제외 | 미적용 (YAGNI) | actuator 미사용 + favicon은 `api.*` 서브도메인 — 사전 제외는 dead code 가능성, 추가 비용도 작음 |
| NaverPay 호출 실패 레벨 | Gateway는 모두 WARN, 거래 종료 ERROR는 호출자(`PaymentService`) 책임 | Gateway는 호출 1회 단위 — 재시도 정책·보상 흐름은 상위 정책 |

---

## 진행 중 트레이드오프

### AccessLogFilter 분리 vs TraceIdFilter 통합

TraceIdFilter에 액세스 로그 코드를 끼워 넣으면 클래스 하나로 끝나고 Filter, Config 보일러플레이트가 줄어든다. 그러나 traceId 관리(MDC push/remove)와 액세스 로그 작성은 변경 사유가 다르다. 향후 액세스 로그 형식이 바뀔 때(path 제외, body 옵션화 등) TraceIdFilter를 건드리면 traceId 관리 코드가 의도치 않게 영향받을 위험이 있다. Filter 클래스 1개와 Config 1개 추가 비용을 받아들이고 단일 책임을 우선했다. TraceIdFilter 단위 테스트와 AccessLogFilter 단위 테스트가 분리되어 회귀가 어느 책임에서 발생했는지 즉시 식별 가능한 점도 이점이다.

### 4xx 분기 방식: 화이트리스트 vs 메타데이터 vs 일괄 무로그

세 가지 안을 검토했다.

- **핸들러 inline 화이트리스트**: `CustomException` 핸들러 안에서 `ErrorCode`별로 로그 여부를 분기. 단순하지만 도메인 ErrorCode가 추가될 때마다 핸들러를 건드려야 해 결합도가 높다.
- **메타데이터(`ErrorCode.loggable()`)**: 결합도가 낮고 분류 책임이 도메인에 있다. 그러나 컨벤션 §4가 "잠정 목록"이라 명시했고, 실제로 어떤 4xx가 WARN인지 운영 데이터 없이 결정하면 dead code 위험이 있다.
- **모두 무로그**: prod 노이즈를 즉시 해소. WARN 분류는 운영 데이터가 누적된 후 별도 작업에서 도입.

"잘못된 정책을 빨리 만들기"보다 "맞는 정책을 늦게 만들기"가 낫다고 판단해 일괄 무로그를 선택했다. WARN 분류는 P5(#132 운영 파이프라인) 이후 운영 데이터를 보고 메타데이터 방식으로 도입한다. Trade-off로, 그 시점까지 인증 반복 실패 같은 의미 있는 4xx가 핸들러 로그에서 추적되지 않을 수 있다는 점은 수용했다(액세스 로그에서 status=401/403은 여전히 보임).

### 액세스 로그 path 제외 (YAGNI)

`/actuator/**`, `/favicon.ico` 등을 상수 배열로 미리 제외하는 안과 모든 요청을 일단 로그하는 안이 있었다. 검토 결과 actuator는 현재 미사용, favicon은 `api.*` 서브도메인이라 브라우저가 직접 칠 일이 거의 없어 실측 noise가 없다. 사전 제외는 dead code 가능성이 높고, 후속에 추가하는 비용도 상수 배열 한 줄이라 작다. AccessLogFilter 클래스 주석에 "noise 발생 시 path 제외 목록 추가를 검토하라" 한 줄만 남겨두었다.

### NaverPay 호출 실패 레벨: Gateway에서 ERROR vs 호출자 책임

컨벤션 §2는 "외부 호출 완전 실패로 거래가 종료된 경우 ERROR"라 적었다. Gateway에서 호출 실패를 ERROR로 일괄 처리하면 코드가 단순하지만, Gateway는 호출 1회 단위의 결과만 알 수 있다. `approve()` 1회 실패가 거래 종료를 의미하는지는 재시도 정책, 보상 흐름 같은 상위 정책에 달려 있다. Gateway에서 ERROR로 일괄 처리하면 호출자가 재시도 성공으로 복구한 경우에도 ERROR 로그가 남아 false positive가 된다. Gateway는 WARN으로 한정하고 거래 종료 ERROR 판단은 호출자(`PaymentService`)의 책임으로 두었다. 호출자 변경은 본 작업 범위 밖.

### cancelReason 로그 제외

기존 코드는 cancel 호출 시 `cancelReason`을 로그 메시지에 포함하고 있었다. cancelReason은 운영자가 입력한 자유 문자열로 PII나 민감 정보가 섞일 가능성이 있다(고객명, 결제 사유 등). 추적에 꼭 필요한 paymentId와 cancelAmount만 로그에 남기고 cancelReason은 제외했다. 보수적인 처리로 PII 누출 위험을 사전 차단했다. 디버깅에 필요해지면 컨벤션 §3의 DEBUG 옵션화로 후속 도입한다.

### 요청/응답 body 로깅 미지원

컨벤션 §3은 "DEBUG 옵션화"를 명시했다. 그러나 body 로깅을 위해서는 `ContentCachingRequestWrapper`로 InputStream을 캐싱해야 하고, 큰 요청(파일 업로드, 대용량 JSON)에서 메모리 부담이 있다. AccessLogFilter는 method/path/status/latency로 한정하고, body 로깅은 디버깅 사례가 발생했을 때 후속으로 별도 도입한다.

---

## 후속 작업 제안

- **4xx WARN 분류 (운영 데이터 누적 후)**: P5(#132) 운영 파이프라인에서 4xx 빈도를 측정한 뒤, 의미 있는 4xx(인증 반복 실패, 결제 검증 실패 등)를 `ErrorCode.loggable()` 메타데이터 방식으로 분류한다. 핸들러 inline 화이트리스트는 결합도가 높아 피한다.
- **body 로깅 DEBUG 옵션 (디버깅 필요 시)**: `ContentCachingRequestWrapper`/`ContentCachingResponseWrapper`로 캐싱하고, application 프로파일에서 DEBUG 레벨일 때만 활성화한다. 본문 크기 상한과 마스킹 정책을 함께 도입한다.
- **memberId MDC push (P3 #130 또는 인증 Filter 확장)**: `JwtAuthenticationFilter`에서 인증 성공 시 memberId를 MDC에 push하고, 응답 직전에 remove한다. AccessLogFilter는 이미 MDC를 읽기만 하므로 인증 이후 액세스 종료 로그에는 memberId가 자동으로 부착된다.
- **actuator 도입 시 액세스 로그 path 제외 추가**: 헬스체크/메트릭 폴링이 시작되면 `/actuator/**`를 제외 목록에 추가한다. AccessLogFilter 클래스 안에 상수 배열로 도입하면 변경 범위가 최소다.
- **비동기/Kafka traceId 전파**: `@Async`는 `TaskDecorator`로, Kafka consumer는 헤더에 traceId를 실어 consumer 측에서 MDC로 복원, `@TransactionalEventListener`는 이벤트 publish 시점의 MDC를 복사해 전달한다. Epic #133의 후속 작업으로 다룬다.
- **거래 종료 ERROR 분류**: `PaymentService`가 NaverPay 호출 결과를 받아 재시도 정책·보상 흐름까지 거친 후에도 실패인 경우 ERROR 로그를 남긴다. Gateway는 WARN으로 유지하고 호출자에서만 ERROR를 결정한다.

---

## 검증 결과

- `./gradlew test`: 통과 (Step 1~4 진행 중 누적 검증). AccessLogFilter 단위 5건 + 통합 3건, GlobalExceptionHandlerTest 9건, NaverPayGatewayImplTest 5건, 그 외 전체 회귀 통과.
- 수동 검증: 작업 범위는 로깅 정책이므로 실제 API를 호출해 로그 형식을 직접 확인하는 절차는 호출자(`PaymentService` 변경)나 운영 환경 도입 시점에 함께 진행한다. 단위/통합 테스트가 메시지 형식과 레벨을 모두 검증하므로 본 작업 단위로는 자동 검증으로 충분하다고 판단했다.
