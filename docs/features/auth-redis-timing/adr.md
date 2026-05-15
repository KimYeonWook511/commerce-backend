# 기능 ADR

## ADR-1: 인증 토큰 Redis 저장 실패 정책 — strict

### 배경

Redis 장애 시 선택지:
- **strict**: Redis 저장/조회 실패 = 예외 처리. 로그인/회원가입 불가.
- **soft**: Redis 저장 실패 = 로깅만. access token 발급은 성공, refresh token은 미저장.

soft fail을 선택하면 클라이언트에 refresh token을 발급했으나 Redis에 없는 상태가 된다.
사용자는 access token 만료 시 재발급을 시도하다가 예상치 못한 "token not found" 에러를 받게 된다.
이는 "동작하는 것처럼 보이지만 실제로는 망가진" 상태로, 더 나쁜 사용자 경험을 유발한다.

### 결정 내용

**strict 정책 채택**: Redis 저장/조회 실패 시 `AuthException(INTERNAL_ERROR)`을 던진다.

### 근거

- refresh token은 Redis가 저장소 자체다. Redis 없이 발급된 refresh token은 반드시 실패한다.
- 명확한 즉각 실패가 지연된 묵시적 실패보다 사용자 경험이 낫다.
- 기존 로그인 사용자(유효한 access token 보유)는 Redis 장애에 영향받지 않는다.
- Redis 장애는 인프라 레벨(HA)에서 해결해야 할 문제다. 코드 정책으로 우회하는 것은 한계가 있다.

### 결과

- Redis 장애 시 신규 로그인 / 회원가입이 일시적으로 불가하다.
- 기존 세션(유효한 access token)은 영향받지 않는다.
- **향후 과제**: Redis 단일 장애점 해소를 위해 Sentinel 또는 Cluster 구성 필요.

---

## ADR-2: 회원가입 트랜잭션 분리 — `Propagation.NOT_SUPPORTED`

### 배경

`AuthSignUpService.signUp()`이 `@Transactional`로 외부 트랜잭션을 열면,
`MemberRegistrationService.register()`가 `REQUIRED` 전파로 합류한다.
Spring에서 commit은 트랜잭션을 시작한 메서드가 종료될 때 발생하므로,
`register()`가 반환한 뒤에도 DB는 미커밋 상태다.
그 사이에 `issue()`가 Redis에 저장하면 DB commit 전 Redis 저장 불일치가 발생한다.

단순히 method-level `@Transactional`을 제거하면 class-level `@Transactional(readOnly = true)`가 적용되어
`register()`가 readOnly 트랜잭션에 합류한다. Hibernate가 flush mode를 MANUAL로 설정하므로 의도와 다르게 동작한다.

`@TransactionalEventListener(AFTER_COMMIT)` 방식은 응답 반환 후 이벤트가 실행되므로
Redis 저장 실패를 클라이언트에 전달할 수 없다. strict 정책과 양립 불가하다.

### 결정 내용

`signUp()` method-level annotation을 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 교체.

### 근거

- `NOT_SUPPORTED`는 class-level `readOnly = true`를 명시적으로 override한다.
- `signUp()`이 트랜잭션 없이 실행되면 `register()`가 자체 트랜잭션으로 commit 후 반환한다.
- 이후 `issue()` 호출 = DB commit 이후 Redis 저장 보장.
- 기존 `OrderCreateService.createOrder()`가 동일 패턴을 사용한다 (ADR 일관성).

### 결과

- DB commit 이후 Redis 저장 순서가 보장된다.
- Redis 저장 실패 시 strict 예외 처리와 결합하면 부분 실패 시나리오가 명확해진다.
  (member는 DB에 생성됐으나 auth 실패 → 다음 요청에서 DUPLICATE_EMAIL 또는 로그인 성공)

---

## ADR-3: `RefreshTokenStore.delete()` 제거

### 배경

`RefreshTokenStore` 인터페이스에 `delete(Long memberId)`가 정의되어 있으나,
현재 로그아웃 서비스가 구현되어 있지 않아 어디서도 호출되지 않는다.
사용되지 않는 인터페이스 메서드는 CLAUDE.md 원칙("불필요한 추상화와 과한 설계를 피한다")에 어긋난다.

### 결정 내용

`RefreshTokenStore` 인터페이스와 `RedisRefreshTokenStore` 구현체에서 `delete()` 제거.

### 근거

- 호출부가 없는 코드를 유지하는 것은 잠재적 혼란을 유발한다.
- Git 히스토리가 이 메서드의 존재와 제거 이유를 기록한다.
- 로그아웃 구현 시 그 PR에서 `delete()`를 재추가하고 Redis 실패 정책을 함께 설계하는 것이 더 안전하다.

### 결과

- 인터페이스가 실제 사용 범위로 좁혀진다.
- **향후 과제**: 로그아웃 기능 구현 시 `delete()` 재추가 및 Redis 실패 정책 결정 필요.
  로그아웃은 보안 목적이므로 strict / soft 정책 선택이 신중히 검토되어야 한다.
