# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 결정과 step 1의 코드 변경 결과를 파악하라:

- `/docs/tasks/auth-refresh-token-store-unavailable/prd.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/architecture.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/adr.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/api-spec.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/db-schema.md`

step 1 결과 코드 (변경 반영 확인):

- `/src/main/java/com/commerce/auth/exception/RefreshTokenStoreUnavailableException.java`
- `/src/main/java/com/commerce/auth/exception/AuthExceptionHandler.java`
- `/src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`
- `/src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`
- `/src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`

수정 대상 루트 문서 (현재 상태 파악):

- `/docs/ADR.md`
- `/docs/exception-strategy.md`

## 작업

본 step은 step 1의 코드 변경에 맞춰 루트 docs의 외부 캐시 장애 처리 규약과 task 색인을 동기화한다. *문서만 만진다.* 코드 변경 없음.

### 1. `docs/exception-strategy.md` — *Redis 캐시 장애 처리* 섹션 정리

현재 구조 (요약):

- *본질 흐름* — infra `DataAccessException` catch → 도메인 예외 변환 / application catch → fallback 진입.
- *비교: fallback 불가한 경우* — Auth는 application에서 직접 `DataAccessException` catch + INTERNAL_ERROR 변환 (예외 케이스로 기술).
- *로깅 규약* — infra ERROR / application WARN (fallback 분기 결정).

목표 구조:

- *본질 흐름* 메시지를 *fallback 가능 여부와 무관하게 적용되는 공통 매핑 패턴* 으로 격상.
- *application 정책 결정 분기* 와 *catch 위치 분기* 를 명시: Order는 application catch + fallback, Auth는 presentation `@RestControllerAdvice` 위임 + 응답 매핑.
- *적용처* 줄에 `RefreshTokenStore` ↔ Auth services + `AuthExceptionHandler` 위임 추가.
- *로깅 규약* 섹션에 *fallback 불가 케이스의 application/presentation 로그 생략* 규칙 한 줄 추가.

#### 1-1. *본질 흐름* 섹션 재정리

문장 톤을 *fallback이 있을 때만 적용되는 패턴* 에서 *fallback 가능 여부와 무관하게 적용되는 공통 매핑 패턴* 으로 격상한다. 핵심 메시지:

- infra adapter는 `DataAccessException`을 catch해 도메인 예외 (`*StoreUnavailableException`)로 변환한다. ERROR + stack 로그.
- 도메인 예외는 `RuntimeException` 직접 상속 (`CustomException` 상속 시 `GlobalExceptionHandler.handleCustomException`이 자동 응답 매핑되어 *책임 분리* 또는 *application catch 의도* 가 우회됨).
- 변환 이후 *정책 결정* 은 도메인 사정에 따라 두 갈래로 분기:
  - **fallback 가능 (Order)** — application이 도메인 예외를 catch해 DB unique 안전망 경로로 fallback 진입. WARN 로그.
  - **fallback 불가 (Auth)** — application은 catch하지 않는다. 도메인 모듈의 `@RestControllerAdvice` (`AuthExceptionHandler`)가 도메인 예외를 받아 `AUTH-500-1` 500 응답으로 매핑. 추가 로그 없음.
- port 시그니처에 Spring `DataAccessException`이 노출되지 않아 추상화 보존된다.
- 적용처:
  - `OrderIdempotencyStore` ↔ `OrderCreateService` (`order-idempotency-cache-simplification`, application catch + fallback).
  - `RefreshTokenStore` ↔ `RedisRefreshTokenStore` ↔ `AuthExceptionHandler` (`auth-refresh-token-store-unavailable`, presentation 위임 + 응답 매핑).

#### 1-2. *비교: fallback 불가한 경우* 섹션 재명명/재작성

섹션 제목을 *catch 위치 분기 — application vs presentation* 류로 재명명한다. 본문 핵심 메시지:

- 매핑 단계 (infra adapter)는 어느 도메인이든 동일.
- catch 위치는 *fallback 가능 여부* 에 따라 두 갈래:
  - **fallback 가능** — application이 catch한다. fallback 진입이라는 *정책 결정 사실* 이 있어 WARN 로그 가치도 있다.
  - **fallback 불가** — application은 catch하지 않는다. 도메인 모듈의 `@RestControllerAdvice`가 받아 응답 매핑. *단순 변환 보일러플레이트* 와 *중복 로그* 가 모두 사라진다.
- 패턴 선택의 분기점은 fallback 가능 여부가 아니라 *catch 위치 결정 내용* . 매핑 구조 자체는 공통 규약이다.
- 도메인-specific `@RestControllerAdvice`는 *도메인 모듈 안에* 둔다 (`common`이 도메인을 import하는 역의존 회피). 우선순위/범위 한정은 사용처가 늘어났을 때 재검토.
- 베이스 클래스 (`StoreUnavailableException`) 추출은 Payment/Stock 등 3곳 이상 사용처가 등장하고 공통 catch 시나리오가 실제로 필요해진 시점에 별도 검토 (현재 YAGNI).

#### 1-3. *로깅 규약* 섹션 보강

기존 두 줄 (infra adapter ERROR, application WARN)은 그대로 두고 한 줄 추가:

- *fallback 불가 케이스 (Auth)*: application과 presentation 모두 로그를 남기지 않는다. infra ERROR + stack으로 운영 인지가 보장되며, 정책 결정 사실 (`AUTH-500-1` 응답 매핑)은 운영 로그에서 별도 식별 가치가 없다.

### 2. `docs/ADR.md` — Task ADR 색인 표 행 추가

`auth-redis-timing` 다음 줄, `boundary-logging-standardization` 직전에 알파벳 순으로 한 줄을 추가한다:

```markdown
| auth-refresh-token-store-unavailable | [`docs/tasks/auth-refresh-token-store-unavailable/adr.md`](tasks/auth-refresh-token-store-unavailable/adr.md) | refresh token Redis 장애 시 도메인 예외 매핑 + 도메인-specific @RestControllerAdvice 응답 매핑 (외부 캐시 장애 규약 통일) |
```

## Acceptance Criteria

```bash
./gradlew test
```

추가 수동 확인 (커맨드는 정보 목적):

```bash
grep -n "auth-refresh-token-store-unavailable" docs/ADR.md
grep -n "RefreshTokenStore\|AuthExceptionHandler" docs/exception-strategy.md
```

위 grep 결과가 의도한 표 행과 적용처 줄을 정확히 가리키는지 사람이 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/exception-strategy.md`의 *Redis 캐시 장애 처리* 섹션이 fallback 가능 여부와 무관한 공통 매핑 패턴으로 재정리됐다.
   - *catch 위치 분기* 가 application catch (Order) vs presentation 위임 (Auth)으로 설명됐다.
   - 적용처 줄에 `RefreshTokenStore` ↔ Auth 흐름 (infra adapter → `AuthExceptionHandler`)이 추가됐다.
   - 로깅 규약에 fallback 불가 케이스의 application/presentation 로그 생략 규칙이 명시됐다.
   - `docs/ADR.md` task 표에 `auth-refresh-token-store-unavailable` 행이 알파벳 순으로 들어갔다.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 코드 변경을 같이 넣지 마라. 이유: 본 step은 문서 동기화 전용. 코드 보정이 필요해 보이면 별도로 보고하고 step1로 돌아간다.
- 머지 완료된 task 폴더 문서 (`docs/tasks/order-idempotency-cache-simplification/` 등) 본문을 수정하지 마라. 이유: 완료된 tasks 불변 원칙 (CLAUDE.md, `docs/tasks/README.md`). 필요한 cross-reference는 본 task의 `adr.md`에서만 표현한다.
- `commerce-workspace/docs/` 하위 문서를 수정하지 마라. 이유: frontend 세션 책임.
- `docs/exception-strategy.md`의 `DB 무결성 위반 흐름` 섹션을 함께 손대지 마라. 이유: 별개 정책이고 본 태스크 범위 밖.
- 기존 테스트를 깨뜨리지 마라.
