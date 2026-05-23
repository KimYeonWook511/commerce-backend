# 태스크 ADR

## 결정 1. AccessLogFilter를 TraceIdFilter와 별도 클래스로 분리한다

### 배경

기존 `TraceIdFilter`에 액세스 로그 코드를 추가하는 안과 새 Filter 클래스를 만드는 안이 있었다.

### 결정 내용

`AccessLogFilter`를 신규 클래스로 분리한다. 패키지는 `common/log/filter/`로 동일하며, order는 `HIGHEST_PRECEDENCE + 20`(TraceIdFilter +10 다음)로 둔다.

### 근거

- 단일 책임 원칙: TraceIdFilter는 MDC 컨텍스트 관리, AccessLogFilter는 운영 로그 작성. 두 가지가 한 클래스에 묶이면 향후 액세스 로그 형식 변경이 traceId 관리 코드를 건드리는 위험을 만든다.
- 향후 형식 변경(예: 경로별 제외 목록, body 옵션화) 시 변경 범위가 AccessLogFilter 안으로 한정된다.
- TraceIdFilter 단위 테스트와 AccessLogFilter 단위 테스트가 분리되어 어떤 책임의 회귀인지 즉시 식별 가능하다.

### 결과

- Filter 클래스가 1개 늘어난다(약간의 보일러플레이트 — Filter, Config 2 파일).
- traceId가 채워진 상태에서 액세스 로그가 작성되어 logback 패턴이 자동 부착된다.

## 결정 2. 4xx CustomException은 모두 무로그, WARN 4xx 분류는 후속 작업으로 미룬다

### 배경

컨벤션 §4 표는 "운영 주목 4xx"를 WARN으로 분류하고 "인증·인가 반복 실패, 결제 검증 실패"를 잠정 목록으로 제시. 핸들러에서 화이트리스트로 분기하는 안, ErrorCode에 메타데이터(`loggable()` 등)를 추가하는 안, 모두 무로그로 가는 안이 있었다.

### 결정 내용

본 작업에서는 4xx CustomException을 모두 무로그로 처리한다. WARN 분류는 운영 데이터가 누적된 후 별도 작업에서 메타데이터 방식으로 도입한다.

### 근거

- 컨벤션 §4가 "잠정 목록"이라 명시했고, 운영 데이터 없이 화이트리스트를 만들면 실제로 의미 있는지 검증할 수 없다.
- 메타데이터(`ErrorCode.loggable()`) 방식은 결합도가 낮지만, 분류 기준 자체가 미정인 상태에서는 dead code가 될 위험이 있다.
- 핸들러 inline 화이트리스트 방식은 도메인 ErrorCode가 추가될 때마다 핸들러 수정이 필요해 결합도가 높다.
- "잘못된 정책을 빨리 만드는 것"보다 "맞는 정책을 늦게 만드는 것"이 낫다. P5(#132 운영 파이프라인) 작업에서 운영 데이터 기반으로 결정하는 것이 옳다.

### 결과

- prod 노이즈가 즉시 줄어든다 (4xx ERROR 도배 해소).
- 후속 작업에서 WARN 4xx 분류가 추가될 때까지 일부 의미 있는 4xx(예: 인증 반복 실패)가 추적되지 않을 수 있다 — Trade-off 수용.

## 결정 3. 액세스 로그 path 제외 목록을 두지 않는다 (YAGNI)

### 배경

`/actuator/**`, `/favicon.ico` 등을 미리 상수 배열로 제외하는 안과 일단 전부 로그하고 noise 발견 시 제외 목록을 추가하는 안이 있었다.

### 결정 내용

path 제외 목록을 두지 않는다. AccessLogFilter는 모든 요청을 로그한다.

### 근거

- 현재 actuator 미사용 — 헬스체크/메트릭 폴링 noise 없음.
- favicon은 `api.*` 서브도메인이라 브라우저가 직접 칠 일이 거의 없음 — 실측 noise 없음.
- Nginx 설정은 path 필터링 없이 `api.*` 서브도메인 전체를 백엔드로 프록시 — 향후 actuator 도입하면 noise 발생 가능하지만, 그 시점에 추가하면 된다.
- 사전에 만들면 dead code일 가능성이 높고, 후속에 추가하는 비용도 작다(상수 배열에 한 줄 추가).

### 결과

- 코드가 단순해진다.
- actuator 도입 시 noise가 발생할 수 있어 그 시점에 제외 목록을 추가해야 한다 — AccessLogFilter 클래스 주석에 "noise 발생 시 path 제외 목록 추가를 검토하라"는 한 줄 메모를 남긴다.

## 결정 4. NaverPayGatewayImpl의 호출 실패는 Gateway에서 WARN, 거래 종료 ERROR는 호출자 책임

### 배경

컨벤션 §2는 "외부 호출 완전 실패로 거래가 종료된 경우 ERROR"라 적었다. Gateway에서 호출 실패를 ERROR로 일괄 처리하는 안, 네트워크/SERVER_ERROR만 ERROR로 구분하는 안, Gateway는 모두 WARN으로 두고 호출자가 ERROR를 결정하는 안이 있었다.

### 결정 내용

NaverPayGatewayImpl은 호출 실패를 모두 WARN으로 로그한다. 거래 종료 ERROR 판단은 호출자(`PaymentService`)의 책임으로 둔다.

### 근거

- Gateway는 외부 호출의 한 단위에 대해 결과 객체를 반환할 뿐, 거래 종료 여부를 판단하지 않는다. 예를 들어 `approve()` 1회 실패가 거래 종료를 의미하는지는 재시도 정책, 보상 흐름 등 상위 정책에 달려 있다.
- Gateway에서 ERROR로 일괄 처리하면 호출자가 재시도 성공으로 복구한 경우에도 ERROR 로그가 남아 false positive가 된다.
- 현재 코드 정책이 이미 그렇게 되어 있어 변경 범위 최소화 측면에서도 유리하다.

### 결과

- Gateway 로그는 호출 1회 단위로 "성공·실패"만 기록한다.
- 거래 종료 ERROR는 호출자 변경 시점(별도 작업)에 추가한다. 본 작업 범위 밖.
