# 태스크 아키텍처

## 개요

본 태스크는 MDC 키·관련 상수를 단일 출처(`MdcKeys`)로 통합하는 리팩토링이다. 컴포넌트 경계·레이어 책임·의존성 방향은 변경하지 않는다.

## 통합 클래스 위치

- 패키지: `com.commerce.common.log`
- 파일: `src/main/java/com/commerce/common/log/MdcKeys.java`

`com.commerce.common.log`는 이미 `TraceIdFilter`, `AccessLogFilter`, `TraceIdKafkaProducerInterceptor`, `TraceIdRecordInterceptor`의 공통 상위 패키지다. MDC 키 정의는 로깅·관측 책임에 속하므로 같은 패키지 루트에 둔다. 별도 `mdc/` 서브패키지는 도입하지 않는다(현재 보유 파일이 1개라 과한 분리가 된다).

## 클래스 형태

```java
package com.commerce.common.log;

import java.util.regex.Pattern;

public final class MdcKeys {
    public static final String TRACE_ID = "traceId";
    public static final String MEMBER_ID = "memberId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private MdcKeys() {
    }
}
```

- `public final class` + `private` 생성자: 인스턴스화 차단.
- `VALID_TRACE_ID`는 `Pattern` 객체 자체를 노출한다. `Pattern`은 thread-safe immutable이므로 정적 상수로 안전하게 공유된다. (`Matcher`만 thread-unsafe.)
- 상수 분류는 다음 두 가지를 묶는다.
  - MDC 키(`TRACE_ID`, `MEMBER_ID`)
  - traceId 헤더 계약(`TRACE_ID_HEADER`, `VALID_TRACE_ID`) — HTTP·Kafka 경계에서 traceId를 운반하는 표준 헤더·검증 규칙은 MDC 키와 짝을 이루어 사용되므로 한 곳에서 함께 관리한다. 별도 클래스로 분리하면 사용처가 매번 두 클래스를 import해야 하고 두 정의 위치가 다시 분산된다.

## 의존성 방향

```
common.log.filter.TraceIdFilter          ┐
common.log.filter.AccessLogFilter        │
common.log.kafka.TraceIdKafkaProducerInterceptor  ├──> common.log.MdcKeys
common.log.kafka.TraceIdRecordInterceptor         │
security.filter.JwtAuthenticationFilter  ┘
```

- `MdcKeys`는 단방향으로 참조된다.
- `JwtAuthenticationFilter` → `MdcKeys` 의존이 추가되지만, `security` 모듈이 `common.log`를 참조하는 패턴은 이미 존재한다(`AccessLogFilter.MEMBER_ID_ATTRIBUTE` 참조). 의존 방향 변경 없음.

## 변경 불가 사항

- MDC 키 문자열 값: `"traceId"`, `"memberId"`
- HTTP 응답 헤더 이름: `"X-Trace-Id"`
- traceId 유효성 정규식: `^[A-Za-z0-9_-]{1,64}$`
- Filter 등록 순서, Interceptor의 `intercept`/`success`/`failure`/`afterRecord` 콜백 위치, `MDC.remove` 호출 위치
- logback 패턴 `%X{traceId:-}`, `%X{memberId:-}` (logback 설정 문자열, Java 상수로 대체 불가)

## 외부 노출

- 공개 API, 로그 포맷, 응답 헤더, Kafka 헤더 이름은 변경되지 않는다.
- 본 리팩토링은 컴파일러 가시성(클래스 식별자) 수준의 정리이며, 런타임 동작과 외부 계약은 동일하다.
