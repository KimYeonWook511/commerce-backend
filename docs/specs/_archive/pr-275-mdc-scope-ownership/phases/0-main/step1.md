# step1 — mdc-scope-ownership-refactor (refactor)

MDC 정리를 2-규칙 모델로 전환한다. 코드 4개 파일과 관련 테스트 6종을 **한 커밋**으로 함께 바꾼다. 중간 상태(예: Jwt는 remove를 멈췄는데 TraceIdFilter는 아직 clear 안 함)에선 memberId가 스레드 풀에 새므로 원자적으로 바꿔야 한다.

## 배경 (관련 문서)

- 결정: `docs/specs/mdc-scope-ownership/spec.md`(FR-001~004·FR-006·FR-007), `adr.md`(ADR-L1).
- 모델: (a) 최외곽 `TraceIdFilter`가 요청 끝 `MDC.clear()`로 스레드 스코프 정리 + (b) nested 스코프는 자기 키만 remove. memberId는 (a)에 얹힘 — Jwt가 populate만, `AccessLogFilter`는 순수 로거, 정리는 최외곽 clear.
- 필터 순서(불변): `TraceIdFilter`(+10) → `AccessLogFilter`(+20) → `JwtAuthenticationFilter`(+30).

## 관련 파일

- `src/main/java/com/commerce/common/log/LogContext.java`
- `src/main/java/com/commerce/common/log/filter/TraceIdFilter.java`
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`
- `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`
- 테스트: `TraceIdFilterTest`, `TraceIdFilterIntegrationTest`, `AccessLogFilterTest`, `AccessLogFilterIntegrationTest`, `JwtAuthenticationFilterTest`(security 패키지), `SecurityWebMvcTest`(security 패키지).

## 구현 지시 (인터페이스·제약 위주)

### 1. `LogContext`
- `public static void clear()`를 추가한다. 내부에서 `MDC.clear()`만 호출한다. (MDC를 만지는 유일한 main 클래스로 유지 — §8 "MDC 키는 common.log에서 단일 관리".)
- `removeMemberId()`를 **삭제**한다. 변경 후 호출처가 없다.
- `removeTraceId()`·`getMemberId()`·`getTraceId()`·`putMemberId()`·`putTraceId()`·`isValidTraceId()`는 **유지**한다. `removeTraceId()`는 비동기 경계(Kafka `TraceIdRecordInterceptor`, Outbox relay)가 계속 쓴다.

### 2. `TraceIdFilter`
- `finally`의 `LogContext.removeTraceId()`를 `LogContext.clear()`로 바꾼다.
- 기존 `// MDC.clear() 금지 …` 주석을, "이 필터가 MDC를 만지는 최외곽이라 요청 끝에서 clear()로 스레드 스코프 전체를 정리한다. 단 이 전제는 TraceIdFilter가 최외곽으로 유지될 때만 성립한다"는 취지로 교체한다.

### 3. `JwtAuthenticationFilter`
- 인증 성공 블록에서 `LogContext.putMemberId(memberId)`는 유지하되, `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, memberId)` 줄을 **삭제**한다.
- `finally`에서 `LogContext.removeMemberId()`를 **삭제**한다. `AuthenticationContext.clear()`는 **유지**한다.
- 더 이상 쓰지 않는 `import com.commerce.common.log.filter.AccessLogFilter;`를 제거한다. `import com.commerce.common.log.LogContext;`는 유지.

### 4. `AccessLogFilter`
- `public static final String MEMBER_ID_ATTRIBUTE` 상수를 **삭제**한다.
- `finally`에서 request attribute를 읽어 `putMemberId` 재삽입·`removeMemberId` 재제거하던 로직을 **삭제**한다. 종료 로그(`log.info("요청 종료 ...")`)는 그대로 두되, memberId는 안쪽 Jwt가 넣어둔 MDC 값이 그대로 실린다. `LogContext` import가 더 필요 없으면 제거한다.

### 5. 테스트 갱신 (의도 기준 — 단언 문구는 자유)
- **`JwtAuthenticationFilterTest`**: Jwt를 "populate 전용"으로 검증한다.
  - chain 실행 중 `LogContext.getMemberId()`가 set됨은 유지.
  - `request.setAttribute` 검증 테스트는 **삭제**(릴레이 폐지).
  - "doFilter 후 memberId가 null"이라던 단언들은 **"Jwt가 지우지 않아 populate된 값이 남는다"로 반전**한다(정상 성공·체인 예외·스레드 풀 재사용 케이스 모두 Jwt는 remove하지 않음). 요청 전체의 잔류 없음은 여기서 검증하지 않는다(최외곽 clear의 책임 → SecurityWebMvcTest·TraceIdFilterTest에서 검증).
  - 401·WHITELIST 경로: memberId를 애초에 put 안 하므로 null, attribute 없음(attribute 단언은 삭제).
- **`AccessLogFilterTest`**: AccessLogFilter를 "순수 로거"로 검증한다.
  - MEMBER_ID_ATTRIBUTE·attribute 기반 테스트(`withAttribute_*`, `chainException_withAttribute_*`, `noAttribute_*`의 attribute 의존 부분)는 **삭제/대체**한다.
  - memberId가 이미 MDC에 있는 상황(chain에서 `LogContext.putMemberId(...)`로 안쪽 Jwt를 흉내)에서 종료 로그의 `getMDCPropertyMap().get("memberId")`에 값이 실리는지 검증하는 테스트로 대체한다. AccessLogFilter가 memberId를 **제거하지 않음**(자기 스코프 아님)도 확인.
  - 기본 로그 2건·status·latency·chain 예외 시 종료 로그 테스트는 유지.
- **`TraceIdFilterTest`**: 기존 단언(요청 후 traceId null 등)은 clear()로도 통과. **clear가 memberId까지 지운다**를 검증하는 테스트를 추가한다(요청 전 `putMemberId`한 뒤 doFilter 후 `getMemberId()`·`getTraceId()` 모두 null).
- **`SecurityWebMvcTest`**: `@Import`에 **`JwtAuthenticationFilterConfig`에 더해 `TraceIdFilterConfig`를 추가**한다. 그래야 최외곽 clear가 돌아 "요청 종료 후 memberId 제거" 단언이 새 모델에서 성립한다. (없이 두면 Jwt가 안 지워 잔류 → 실패.)
- **`AccessLogFilterIntegrationTest`·`TraceIdFilterIntegrationTest`**: memberId를 직접 검증하지 않으면 변경이 거의 없다. 컴파일·통과만 확인하고, MEMBER_ID_ATTRIBUTE 참조가 있으면 제거한다.

## 주의사항

- 필터 order(+10/+20/+30)를 바꾸지 마라. 이유: 최외곽 clear·종료 로그 memberId 소비·401 접근 로그가 모두 이 포갬에 의존한다.
- `AuthenticationContext.clear()`를 지우지 마라. 이유: 인증 컨텍스트는 MDC와 별개이고 이 spec 범위 밖이다.
- `removeTraceId()`를 삭제하지 마라. 이유: 비동기 경계(Kafka/Outbox)가 (b) 규칙으로 계속 쓴다.
- 실패 회피를 위해 테스트 단언을 느슨하게 바꾸지 마라. 이유: 이 spec의 핵심은 "populate 1회·잔류 0"의 관측 계약이다.

## Acceptance Criteria

> `# expect: N`은 **바로 다음 줄 명령**의 기대 exit를 지정한다(지정 없으면 0). exit 1 = "매치 없음(존재하면 안 됨)".

```bash
./gradlew test
# expect: 1
rg -q 'MEMBER_ID_ATTRIBUTE' src/main
# expect: 1
rg -q 'removeMemberId' src/main
rg -q 'public static void clear' src/main/java/com/commerce/common/log/LogContext.java
rg -q 'LogContext\.clear\(\)' src/main/java/com/commerce/common/log/filter/TraceIdFilter.java
# expect: 1
rg -q 'removeTraceId' src/main/java/com/commerce/common/log/filter/TraceIdFilter.java
# expect: 1
rg -q 'setAttribute' src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java
# expect: 1
rg -q 'removeMemberId' src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java
```
