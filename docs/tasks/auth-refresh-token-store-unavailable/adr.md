# 태스크 ADR

## ADR-1: Auth도 외부 캐시 장애를 도메인 예외 매핑 패턴으로 처리한다

### 배경

PR #180 (`order-idempotency-cache-simplification`)에서 외부 캐시 장애 처리 규약이 신설됐다 (`docs/exception-strategy.md` *Redis 캐시 장애 처리* 섹션). infra adapter가 `DataAccessException`을 도메인 예외로 변환하고, application이 도메인 예외를 catch해 정책 결정을 내리는 구조다.

Auth 도메인은 이 패턴을 따르지 않고 *application에서 직접* `DataAccessException`을 catch해 `AuthException(INTERNAL_ERROR)`로 변환한다. `docs/exception-strategy.md`의 *비교: fallback 불가한 경우* 섹션이 이를 *예외 케이스*로 기술해뒀다.

두 가지 갈래가 있었다:

- (A) 현행 유지 — *fallback 가능 여부*가 패턴 분기점. Auth는 fallback 불가하므로 application 직접 catch가 자연스럽다는 해석.
- (B) 패턴 통일 — fallback 가능 여부와 무관하게 *infra의 도메인 예외 매핑 + application/presentation의 정책 결정* 으로 일원화. 매핑 단계는 공통, application 정책 (fallback 진입 vs 응답 매핑)만 도메인별로 달라진다.

### 결정 내용

(B)를 채택한다. `RefreshTokenStoreUnavailableException` 도메인 예외를 신설하고, `RedisRefreshTokenStore`가 `DataAccessException`을 catch해 도메인 예외로 변환 + ERROR 로그를 남긴다. application 정책 결정 방식과 presentation 매핑 위치는 ADR-2, ADR-3에서 별도 결정한다.

### 근거

- **port 추상화 일관성.** application이 Spring `DataAccessException`에 직접 의존하면 port (`RefreshTokenStore`)가 *기술적으로는 노출 없음* 이어도 *사용처 의존* 으로는 노출이다. infra 매핑을 거치면 application/presentation이 도메인 예외만 알면 된다.
- **사용처 추가 시 일관성 부담 누적 회피.** 미래에 Payment, Stock 등 외부 캐시 사용처가 추가될 때 *Auth만 다른 패턴*으로 남아 있으면 신규 사용처가 어떤 매핑을 따를지 매번 판단해야 한다. 매핑 패턴을 통일하면 의사 결정 비용이 줄어든다.
- **`docs/exception-strategy.md` 규약 격상 가능성.** Auth가 같은 매핑 패턴을 따르면 "외부 캐시 장애 = infra가 도메인 예외로 변환"이 *모든 사용처가 따르는 규약*으로 격상된다. fallback 가능 여부는 *application/presentation 정책 결정의 하위 분기* 가 된다.
- **동작 보존.** 응답 코드 (500 `AUTH-500-1`)는 그대로 유지한다.

### 결과

**기대 효과**

- Auth application 계층의 `org.springframework.dao.DataAccessException` 의존 0건.
- 외부 캐시 장애 처리 규약이 fallback 가능 여부와 무관한 단일 매핑 패턴으로 정리됨.
- 미래 사용처 추가 시 매핑 패턴 선택 의사결정 비용 감소.

**감수할 trade-off**

- 도메인 예외 클래스 1개 (`RefreshTokenStoreUnavailableException`) 신설.
- `docs/exception-strategy.md`의 설명을 *fallback 가능 여부 = application/presentation 정책 결정 분기* 로 재정리해야 한다.

## ADR-2: application은 도메인 예외를 catch하지 않는다 — presentation `@RestControllerAdvice`가 매핑한다

### 배경

ADR-1로 도메인 예외 매핑 패턴을 채택한 뒤에도 *application 단의 처리 방식*은 별도 결정이 필요했다. 두 가지 갈래:

- **A 안 (Order와 동일 구조)**: application이 도메인 예외를 catch해 `AuthException(INTERNAL_ERROR)`로 변환한 뒤 throw한다. `GlobalExceptionHandler.handleCustomException`이 자동 매핑해 500 응답.
- **B 안 (presentation 위임)**: application은 catch하지 않는다. 도메인 예외가 그대로 propagate되어 `@RestControllerAdvice`가 응답을 매핑한다.

Order의 application catch는 *fallback 진입* 이라는 정책 결정 사실을 표현하므로 가치가 명확하다. Auth는 fallback이 없어 catch 본문이 *단순 변환 두 줄* 이다.

### 결정 내용

(B)를 채택한다. `AuthTokenIssueService`, `AuthTokenReissueService`의 인프라 장애 try-catch를 모두 제거하고, `RefreshTokenStoreUnavailableException`은 자연 propagate된다. presentation의 `AuthExceptionHandler` (`@RestControllerAdvice`)가 `AUTH-500-1` 응답으로 매핑한다 (구체 위치 결정은 ADR-3).

application의 인프라 장애 관련 `log.error` 호출도 함께 제거한다. infra adapter가 동일 사실을 ERROR + stack으로 기록하므로 중복이다.

### 근거

- **`AuthException`의 의미 정합성.** `AuthException`은 *auth 비즈니스 예외* (`TOKEN_INVALID`, `REFRESH_TOKEN_NOT_FOUND` 등) 의미를 가진다. *인프라 장애*를 비즈니스 예외로 감싸는 건 시멘틱적으로 부정확하다. 인프라 장애는 인프라 도메인 예외 그대로 presentation까지 올리는 게 더 정직하다.
- **보일러플레이트 제거.** Auth의 application catch는 *단순 변환 두 줄* 이라 정책 결정 가치가 없다. catch를 제거하면 service의 happy path만 남아 가독성이 좋아진다.
- **중복 로그 회피.** infra ERROR + application ERROR가 같은 장애에 두 번 찍히면 운영 모니터링에서 노이즈가 커지고 stack trace도 두 번 남아 추적이 오히려 어려워진다.
- **Order와의 차이는 *application 정책 결정 내용* 으로 표현된다.** Order는 *fallback 진입* 이라는 정책 결정이 있어 catch가 필요하다. Auth는 *정책 결정 없음, presentation 위임* 이 정책이다. 매핑 패턴 (ADR-1)은 동일하다.

### 결과

**기대 효과**

- `AuthTokenIssueService`, `AuthTokenReissueService`의 인프라 장애 catch 제거 → happy path만 보임.
- application 계층의 `org.springframework.dao.DataAccessException` 의존 0건.
- Redis 장애 시 운영 로그가 infra adapter 한 번만 남아 추적이 단순.

**감수할 trade-off**

- *catch 위치 분기* 를 application 분기 (Order)와 presentation 분기 (Auth)로 나눠 설명해야 한다. `docs/exception-strategy.md`에서 분명히 정리한다.
- 향후 Auth에 *fallback 가능한 캐시 사용처* (예: 토큰 검증 캐시)가 생기면 그때는 application catch 패턴 (Order와 동일)을 도입해야 한다. 그 시점에 정책 결정 분기를 재검토한다.

## ADR-3: 도메인-specific `@RestControllerAdvice`를 신규 도입한다

### 배경

ADR-2로 *presentation 매핑* 정책이 정해진 뒤에도 *매핑 핸들러를 어디에 둘지*가 남았다. 세 가지 갈래:

- **(a)** `common.exception.GlobalExceptionHandler`에 `@ExceptionHandler(RefreshTokenStoreUnavailableException.class)` 한 줄 추가. 기존 단일 advice 패턴 유지.
- **(b)** `auth.exception.AuthExceptionHandler` (`@RestControllerAdvice`)를 신설. 도메인 모듈 안에 매핑 책임을 두고 `common`이 도메인을 import하지 않게 보존.
- **(c)** `RefreshTokenStoreUnavailableException extends CustomException`으로 만들어 자동 매핑. 추가 핸들러 불필요.

(c)는 ADR-1 채택 사유 (catch 의도 우회 위험)와 충돌해 비채택.

(a)는 *common → auth 역의존* 을 신설한다. 다른 도메인 (Order/Payment 등)이 비슷한 사례를 만나면 `common`이 모든 도메인 예외를 import하게 되어 부담이 누적된다.

(b)는 *도메인별 advice* 컨벤션을 새로 도입하지만 의존 방향은 정합 (auth → common). 도메인이 추가될 때마다 자체 advice를 두면 된다.

### 결정 내용

(b)를 채택한다. `com.commerce.auth.exception.AuthExceptionHandler`를 `@RestControllerAdvice`로 신설하고, `RefreshTokenStoreUnavailableException` 핸들러를 둔다. `common.exception.GlobalExceptionHandler`는 수정하지 않는다.

### 근거

- **의존 방향 정합.** `common` 모듈이 도메인을 import하지 않는 원칙을 그대로 유지한다.
- **확장성.** 미래에 Order, Payment 등에서 인프라 장애 직접 매핑이 필요해지면 같은 패턴 (도메인 모듈 내 `@RestControllerAdvice` 신설)으로 확장 가능하다. 단일 advice에 모든 도메인 예외가 누적되는 부담이 없다.
- **응답 매핑 응답성.** Auth 도메인 응답 정책 (`AUTH-500-1` 사용)이 Auth 모듈 안에 모인다. `AuthErrorCode` 정의와 가까이 있어 변경 시 추적이 쉽다.

### 결과

**기대 효과**

- `common` → `auth` 역의존 회피.
- 도메인-specific 인프라 장애 매핑 컨벤션 확립.
- Auth 응답 정책의 모듈 내 응집.

**감수할 trade-off**

- 단일 `@RestControllerAdvice` 컨벤션을 *새로 도입* 하는 변경이다. 한 사례만으로 컨벤션을 변경하는 부담이 있지만, 의존 방향 보존 가치가 더 크고 미래 사용처 확장성도 명확하다.
- 향후 도메인 advice가 늘어나면 우선순위 (`@Order`)나 범위 한정 (`basePackages`)이 필요할 수 있다. 현 시점에는 `RefreshTokenStoreUnavailableException` 한 종류뿐이라 우선순위 충돌이 없으므로 그대로 둔다.

## ADR-4: `StoreUnavailableException` 베이스 클래스를 추출하지 않는다

### 배경

`OrderIdempotencyStoreUnavailableException`, `RefreshTokenStoreUnavailableException` 두 개의 도메인 예외가 같은 구조 (`RuntimeException(Throwable cause)`)를 갖는다. 공통 베이스 클래스 (`StoreUnavailableException`)를 추출할 수 있다.

### 결정 내용

베이스 클래스를 추출하지 않는다. 도메인별 예외를 그대로 둔다.

### 근거

- **YAGNI.** 사용처가 2곳뿐이고, *공통 catch* 시나리오가 없다. Order는 application에서 catch하고 Auth는 presentation에서 catch한다.
- **도메인 격리.** Order와 Auth가 같은 베이스 클래스를 공유하면 두 도메인 간 *우연한 결합* (한쪽 변경이 다른 쪽에 영향)이 생긴다. 현재는 패키지 분리로 격리된다.
- **추출 트리거.** 이슈 본문이 명시한 대로, Payment / Stock 등 *3곳 이상 사용처가 등장* 하고 *공통 catch 시나리오* 가 실제로 필요해진 시점에 베이스 클래스 추출을 재검토한다.

### 결과

**기대 효과**

- 사용처 1곳 추가에 대해 *과한 추상화 도입* 회피 (CLAUDE.md "불필요한 추상화와 과한 설계를 피합니다" 정합).

**감수할 trade-off**

- 미래에 사용처가 늘어나 베이스 클래스 추출이 필요해지면 *기존 도메인 예외의 부모 추가* 와 *사용처 import 조정* 을 함께 해야 한다. 변경 범위가 작고 IDE 지원 가능하므로 비용은 낮다.
