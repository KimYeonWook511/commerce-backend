# Step 3: write-retrospective

## 읽어야 할 파일

- `docs/tasks/mdc-keys-unification/prd.md`
- `docs/tasks/mdc-keys-unification/architecture.md`
- `docs/tasks/mdc-keys-unification/adr.md`
- `docs/tasks/mdc-keys-unification/phases/0-impl/step1.md`
- `docs/tasks/mdc-keys-unification/phases/0-impl/step2.md`
- 이전 회고 참고: `docs/tasks/kafka-trace-propagation/retrospective.md`, `docs/tasks/traceid-mdc-filter/retrospective.md`

## 작업

`docs/tasks/mdc-keys-unification/retrospective.md`를 신규 생성한다. 다음 5단 구조로 작성한다.

### 1. 배경과 목표

- 이슈 #150 후속 리팩토링이며 `kafka-trace-propagation` 회고록(#149)과 `traceid-mdc-filter` PRD(#129)에서 "MdcKeys 통합 리팩토링 PR에서 일괄 처리"로 미뤘던 작업임을 명시한다.
- 작업 전 상태: TRACE 관련 상수 3종이 3개 클래스에 중복 정의되어 있었고, MEMBER_ID MDC 키가 `AccessLogFilter`/`JwtAuthenticationFilter`에 흩어진 형태로 있었다.

### 2. 설계 결정 요약

- 단일 `MdcKeys` 클래스 통합: MDC 키 2종(`TRACE_ID`, `MEMBER_ID`)에 traceId 헤더 계약 2종(`TRACE_ID_HEADER`, `VALID_TRACE_ID`)을 함께 묶었다. 분리 안을 검토했으나 사용처가 두 종을 짝지어 사용하므로 통합이 자연스러웠다.
- 패키지 위치 `com.commerce.common.log`: 이미 traceId/access 로그 Filter·Interceptor의 공통 상위 패키지라 별도 `mdc/` 서브패키지를 만들 만큼의 분리 가치가 없었다.
- `MEMBER_ID_ATTRIBUTE`는 `AccessLogFilter`에 유지: HTTP request attribute이며 MDC 키가 아니다.
- `Pattern` 객체 자체를 정적 노출: thread-safe immutable이라 안전하며, 호출부 반복 컴파일을 제거한다.

### 3. 구현 범위

다음 표 형태로 신규/수정 파일을 정리한다.

| 구분 | 파일 | 변경 내용 |
|------|------|-----------|
| 신규 | `src/main/java/com/commerce/common/log/MdcKeys.java` | MDC 키·traceId 헤더 계약 통합 상수 4개 |
| 수정(main) | `TraceIdFilter`, `TraceIdKafkaProducerInterceptor`, `TraceIdRecordInterceptor`, `AccessLogFilter`, `JwtAuthenticationFilter` | 중복 상수 제거 → `MdcKeys.*` 참조 |
| 수정(test) | `TraceIdFilterTest`, `TraceIdFilterIntegrationTest`, `AccessLogFilterTest`, `JwtAuthenticationFilterTest`, `SecurityWebMvcTest`, `TraceIdKafkaPropagationIntegrationTest` | 정적 참조·assertion 리터럴 → `MdcKeys.*` 참조 |
| 수정(docs) | `docs/logging-conventions.md` §8 | "MDC 키는 `MdcKeys`에서 단일 관리한다" 1줄 추가 |

### 4. 한계와 후속 과제

- 회고록·PRD 잔존 언급: 과거 task 문서(`docs/tasks/traceid-mdc-filter/...`, `docs/tasks/memberid-mdc-propagation/retrospective.md`, `docs/tasks/core-domain-logging/retrospective.md`, `docs/tasks/kafka-trace-propagation/...`)에 남은 "MdcKeys 통합 리팩토링 PR에서 일괄 처리" 후속 작업 언급은 역사 기록 원칙에 따라 사후 수정하지 않았다. 본 회고에서 해당 후속 과제가 본 태스크로 해소되었음을 명시하는 것으로 갈음한다.
- 도메인 식별자 MDC 키 미확장: `orderId`, `paymentId` 등 도메인 식별자는 본 태스크에서 신설하지 않았다. 도메인 도입 시점에 `MdcKeys`에 추가한다.
- `logback-spring.xml`의 `%X{traceId:-}`, `%X{memberId:-}`는 Java 상수로 대체 불가능하다. 키 값을 변경할 일이 생기면 두 파일을 함께 갱신해야 한다(현재는 모두 정합 상태).

### 5. 배운 점

- 리팩토링 범위 추정 시 "private 상수만 검색"으로는 누락 위험이 크다. 본 태스크에서도 `JwtAuthenticationFilterTest`, `SecurityWebMvcTest`, `TraceIdKafkaPropagationIntegrationTest`는 정적 참조 없이 `MDC.get("memberId")` / `record.headers().lastHeader("X-Trace-Id")`처럼 리터럴을 직접 쓰고 있어 plan 1차 안에서 누락됐다가 검증 grep으로 발견했다. 동일 패턴의 후속 리팩토링에서는 정적 상수 식별자 grep과 문자열 리터럴 grep을 모두 수행한다.
- `Pattern`을 정적 상수로 노출해도 안전하다는 점이 명확해졌다. `Matcher`만 비공유 객체로 호출 시점에 만든다.
- 회고록을 사후 수정하지 않는 원칙 덕에 "지금 이 PR이 어떤 과거 약속을 해소했는가"를 본 회고에 모아 쓰는 형태로 정리된다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. `docs/tasks/mdc-keys-unification/retrospective.md`가 생성되었고 5단 구조가 모두 포함되어 있는지 확인한다.
3. 본문에 step1·step2 결과 요약과 한계·후속 과제 항목이 빠짐없이 들어 있는지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 task의 회고록·문서를 수정하지 마라. 이유: 역사 기록 원칙. 회고는 작성 시점의 상태를 보존한다.
- 코드 동작이나 추가 리팩토링을 본 step에서 수행하지 마라. 이유: 본 step은 문서 작성만 담당한다.
- `MdcKeys` 외 별도 상수 클래스 신설 가능성을 회고 본문에서 후속 과제로 적지 마라. 이유: 본 태스크의 설계 결정과 모순된다.
