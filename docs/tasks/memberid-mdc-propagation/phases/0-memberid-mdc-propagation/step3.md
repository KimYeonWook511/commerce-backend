# Step 3: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 변경 내용을 파악하라:

- `docs/tasks/memberid-mdc-propagation/prd.md`
- `docs/tasks/memberid-mdc-propagation/architecture.md`
- `docs/tasks/memberid-mdc-propagation/adr.md`
- `docs/architecture.md` — 수정 대상, HTTP 요청 처리/로깅 절 위치 파악
- `docs/logging-conventions.md` §8 (MDC 운영) — 정합성 확인용
- Step 1, Step 2에서 작성/수정된 코드:
  - `src/main/java/com/commerce/security/filter/JwtAuthenticationFilterConfig.java`
  - `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`
  - `src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`

## 작업

### `docs/architecture.md`의 HTTP 요청 처리/로깅 절 보강

HTTP 요청 Filter chain 또는 로깅 관련 절(없으면 적절한 위치)에 아래 내용을 추가한다.

#### 추가할 내용

- **Filter 등록 정책**: 모든 application Filter는 `FilterRegistrationBean`으로 명시 등록되며 `Ordered` 기반 order를 갖는다.
  - `TraceIdFilter` — `Ordered.HIGHEST_PRECEDENCE + 10`
  - `AccessLogFilter` — `Ordered.HIGHEST_PRECEDENCE + 20`
  - `JwtAuthenticationFilter` — `Ordered.HIGHEST_PRECEDENCE + 30`
  - `@Component` 자동 등록은 사용하지 않는다. 이유: 미래 Filter 추가 시 LOWEST_PRECEDENCE 충돌과 암묵적 등록 순서 의존 회피.

- **memberId MDC propagation 흐름**:
  - `JwtAuthenticationFilter`가 인증 성공 시 `MDC.put("memberId", ...)`로 도메인 로그(Controller/Service/Repository)에 채움.
  - 동시에 `request.setAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE, memberId)`로 `AccessLogFilter`에 전달.
  - `AccessLogFilter` finally가 "요청 종료" access log 출력 시점에 attribute에서 읽어 MDC를 잠깐 채우고 출력 후 제거.
  - 이유: `AccessLogFilter`는 인증 실패(401)/WHITELIST 외 요청의 access log도 남겨야 하므로 `JwtAuthenticationFilter`보다 바깥 Filter여야 한다. `AccessLogFilter` finally 시점엔 `AuthenticationContext.clear()`가 이미 호출된 상태이므로, request attribute로 명시 전달해야 한다.

- **MDC 키 정리 규약**: 각 Filter는 자신이 push한 MDC 키만 `MDC.remove(KEY)`로 제거한다. `MDC.clear()` 호출 금지.

#### 작성 방향

- 길이는 짧게 (10~20줄 내외). 상세 흐름 다이어그램은 `docs/tasks/memberid-mdc-propagation/architecture.md`를 참조하도록 링크.
- 기존 절의 문체와 일관성을 유지한다.

## 수정 가능 경로

- `docs/architecture.md`
- `docs/tasks/memberid-mdc-propagation/**` (task 문서, 필요 시)

## Acceptance Criteria

```bash
./gradlew test
```

문서 변경이지만 테스트 회귀 없음 확인 차원.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다 (회귀 없음).
2. `docs/architecture.md`의 추가 내용이 Step 1, Step 2 실제 구현과 정합한지 확인:
   - Filter order 값 (+10, +20, +30)
   - attribute key 상수 위치 (`AccessLogFilter.MEMBER_ID_ATTRIBUTE`)
   - "요청 시작" access log는 memberId 빈 값이라는 설명 포함
3. `docs/logging-conventions.md` §8과 모순되지 않는지 확인.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- task 문서(`docs/tasks/memberid-mdc-propagation/**`)의 본질적 결정을 사후 변경하지 마라. 이유: ADR은 결정 시점의 근거를 남기는 문서이므로 사후 소급 수정하지 않는다.
- 코드 변경 금지. 이유: 이 step은 문서 동기화만 다룬다.
- `docs/logging-conventions.md` 본문 수정 금지. 이유: 컨벤션 변경은 별도 Epic 작업.
