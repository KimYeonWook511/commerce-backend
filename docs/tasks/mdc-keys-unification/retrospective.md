# mdc-keys-unification 회고

## 1. 배경과 목표

이 작업은 이슈 #150 후속 리팩토링이다. `kafka-trace-propagation`(#149) 회고록과 `traceid-mdc-filter`(#129) PRD에서 "MdcKeys 통합 리팩토링 PR에서 일괄 처리"로 명시하며 의도적으로 미뤘던 후속 과제를 정리하는 PR이다.

작업 전 저장소 상태는 다음과 같았다.

- TRACE 관련 상수 3종(`TRACE_ID_HEADER`, `TRACE_ID_MDC_KEY`, `VALID_TRACE_ID`)이 `TraceIdFilter`, `TraceIdKafkaProducerInterceptor`, `TraceIdRecordInterceptor` 3개 클래스에 동일한 형태로 중복 정의되어 있었다.
- `MEMBER_ID_MDC_KEY("memberId")`가 `AccessLogFilter`에 정의되어 있었고, `JwtAuthenticationFilter`가 이를 `AccessLogFilter.MEMBER_ID_MDC_KEY`로 외부 참조하는 형태로 두 클래스에 흩어져 있었다.

달성하려 한 것은 MDC 키·관련 상수(MDC 키, traceId 헤더 이름, traceId 유효성 정규식)를 단일 `MdcKeys` 클래스로 통합해 모든 사용처가 단일 출처를 참조하게 만드는 것이다. 동작과 외부 계약은 변경하지 않았다.

---

## 2. 설계 결정 요약

본 태스크 내부에서 내린 구현 차원의 결정은 `prd.md`와 `architecture.md`에 기록되어 있다. 회고에서는 결과만 인용한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| 단일 `MdcKeys` 클래스 통합 | MDC 키 2종(`TRACE_ID`, `MEMBER_ID`)에 traceId 헤더 계약 2종(`TRACE_ID_HEADER`, `VALID_TRACE_ID`)을 함께 묶음 | 사용처가 두 종을 짝지어 사용하므로 통합이 자연스러움. 분리하면 매번 두 클래스를 import해야 하고 정의 위치가 다시 분산됨 |
| 패키지 위치 `com.commerce.common.log` | 별도 `mdc/` 서브패키지를 만들지 않음 | 이미 traceId/access 로그 Filter·Interceptor의 공통 상위 패키지라 보유 파일 1개에 서브패키지 분리는 과함 |
| `MEMBER_ID_ATTRIBUTE` 유지 | `AccessLogFilter`에 그대로 유지 | HTTP request attribute이며 MDC 키가 아님. `AccessLogFilter` 내부에서만 쓰이는 캡슐화된 식별자 |
| `Pattern` 객체 자체를 정적 노출 | `VALID_TRACE_ID`를 `Pattern` 객체로 공개 | `Pattern`은 thread-safe immutable이라 정적 상수로 안전하게 공유됨. 호출부 반복 컴파일(`Pattern.compile(...)`) 제거 효과 |

---

## 3. 구현 범위

| 구분 | 파일 | 변경 내용 |
|------|------|-----------|
| 신규 | `src/main/java/com/commerce/common/log/MdcKeys.java` | MDC 키·traceId 헤더 계약 통합 상수 4개 |
| 수정(main) | `TraceIdFilter` | 클래스 내부 상수 3종 제거 → `MdcKeys.*` 참조 |
| 수정(main) | `TraceIdKafkaProducerInterceptor` | 동일하게 상수 3종 제거 → `MdcKeys.*` 참조 |
| 수정(main) | `TraceIdRecordInterceptor` | 동일하게 상수 3종 제거 → `MdcKeys.*` 참조. `success`/`failure`/`afterRecord` 콜백 위치·기존 주석 유지 |
| 수정(main) | `AccessLogFilter` | `MEMBER_ID_MDC_KEY` 상수만 제거 → `MdcKeys.MEMBER_ID` 참조. `MEMBER_ID_ATTRIBUTE` 유지 |
| 수정(main) | `JwtAuthenticationFilter` | `AccessLogFilter.MEMBER_ID_MDC_KEY` 참조 → `MdcKeys.MEMBER_ID`. `AccessLogFilter.MEMBER_ID_ATTRIBUTE` 참조 유지 |
| 수정(test) | `TraceIdFilterTest` | 정적 참조 `TraceIdFilter.TRACE_ID_HEADER`, `TraceIdFilter.TRACE_ID_MDC_KEY` → `MdcKeys.*` |
| 수정(test) | `TraceIdFilterIntegrationTest` | 동일 |
| 수정(test) | `AccessLogFilterTest` | `AccessLogFilter.MEMBER_ID_MDC_KEY` → `MdcKeys.MEMBER_ID` |
| 수정(test) | `JwtAuthenticationFilterTest` | `MDC.get("memberId")`, `MDC.put("memberId", ...)` 리터럴 → `MdcKeys.MEMBER_ID` 참조 |
| 수정(test) | `SecurityWebMvcTest` | `MDC.get("memberId")` 리터럴 3곳 → `MdcKeys.MEMBER_ID` 참조 |
| 수정(test) | `TraceIdKafkaPropagationIntegrationTest` | `MDC.get/put/remove("traceId")` 리터럴과 `record.headers().lastHeader("X-Trace-Id")` 리터럴 → `MdcKeys.TRACE_ID`, `MdcKeys.TRACE_ID_HEADER` 참조 |
| 수정(docs) | `docs/logging-conventions.md` §8 | "MDC 키는 `MdcKeys`에서 단일 관리한다" 1줄 추가 |

---

## 4. 한계와 후속 과제

### 과거 태스크 문서의 잔존 언급

`docs/tasks/traceid-mdc-filter/`, `docs/tasks/memberid-mdc-propagation/retrospective.md`, `docs/tasks/core-domain-logging/retrospective.md`, `docs/tasks/kafka-trace-propagation/` 등에 남은 "MdcKeys 통합 리팩토링 PR에서 일괄 처리" 후속 작업 언급은 역사 기록 원칙에 따라 사후 수정하지 않았다. 해당 언급이 가리키는 후속 과제는 본 태스크(이슈 #150)로 해소되었다.

### 도메인 식별자 MDC 키 미확장

`orderId`, `paymentId` 등 도메인 식별자 MDC 키는 본 태스크에서 신설하지 않았다. 도메인 식별자 도입 시점에 `MdcKeys`에 추가한다.

### logback 패턴의 Java 상수 대체 불가

`logback-spring.xml`의 `%X{traceId:-}`, `%X{memberId:-}`는 Java 상수로 대체할 수 없다. MDC 키 값을 변경할 일이 생기면 `MdcKeys.java`와 `logback-spring.xml` 두 파일을 함께 갱신해야 한다. 현재는 모두 정합 상태다.

---

## 5. 배운 점

### 리팩토링 범위 추정 시 리터럴 grep을 빠뜨리면 누락된다

"private 상수만 검색"으로는 충분하지 않다. 본 태스크에서도 `JwtAuthenticationFilterTest`, `SecurityWebMvcTest`, `TraceIdKafkaPropagationIntegrationTest`는 정적 상수 없이 `MDC.get("memberId")` / `record.headers().lastHeader("X-Trace-Id")`처럼 리터럴을 직접 사용하고 있어 1차 범위 파악에서 누락됐다가 검증 grep으로 발견했다. 동일 패턴의 후속 리팩토링에서는 정적 상수 식별자 grep과 문자열 리터럴 grep을 모두 수행한다.

### `Pattern`은 정적 상수로 공유해도 안전하다

`Pattern` 객체 자체는 thread-safe immutable이다. `Matcher`만 비공유 객체로 호출 시점에 생성한다. 따라서 `Pattern.compile(...)`을 매 호출마다 반복하는 대신 정적 상수로 노출해 단일 객체를 공유해도 안전하다.

### 역사 기록 원칙이 회고 구조를 정돈한다

회고록을 사후 수정하지 않는 원칙 덕에 "지금 이 PR이 어떤 과거 약속을 해소했는가"를 본 회고에 모아 쓰는 형태로 정리된다. 분산된 후속 과제 언급을 소급 정리하는 대신, 현재 회고에서 해소 사실을 명시하는 것으로 충분하다.
