# Architecture Decision Records

### ADR-001: JWT + Redis 기반 인증 유지
- **결정**: Access Token은 JWT로 처리하고 Refresh Token은 Redis에 저장한다.
- **이유**: 토큰 재발급 시 서버 검증과 강제 무효화가 가능하다.
- **트레이드오프**: 완전한 stateless 인증보다 저장소 관리 비용이 늘어난다.

### ADR-002: 주문 생성에 멱등 키 적용 (Redis 1차 방어선 + RDB unique 제약 최종 보장)
- **결정**: 주문 생성 요청은 멱등 키를 요구하며, Redis(1차)와 RDB unique 제약(최종)으로 이중 보장한다. `idempotencyKey`는 클라이언트가 생성한 UUID이며 HTTP Header(`Idempotency-Key`)로 전달한다.
- **멱등성 처리 흐름**: Redis `reserve()` 성공 시 주문 생성 → AFTER_COMMIT 이벤트로 Redis 캐싱 (ADR-005 구현). Redis MISS(TTL 만료 or Redis 장애) 시 바로 INSERT 시도 → `(member_id, idempotency_key)` unique 위반 시 기존 주문을 조회하여 `complete()`로 Redis 갱신 후 반환. 기존 주문을 찾지 못하면 멱등키 외 다른 제약 위반이므로 `log.error` 기록 후 `ORDER_NOT_FOUND` 반환.
- **Redis 장애 처리**: `reserve()`, `getCompletedOrderId()`, `complete()`, `clear()`, `handle()` 실패 시 모두 Infrastructure 계층에서 예외를 catch. `reserve()`→`false`, `getCompletedOrderId()`→`empty()` fallback으로 주문 생성 경로 진입. 나머지는 warn 로그 후 무시하여 주문 반환에 영향 없음.
- **이유**: Redis TTL 만료 후 중복 주문 생성 방지 및 Redis 장애 시에도 주문 가능성 보장.
- **트레이드오프**: TTL 만료 후 재요청 시 재고 차감 → unique 위반 → 롤백이 드물게 발생할 수 있다. 정확성에는 문제 없다.

### ADR-003: 재고 차감 기본 전략으로 비관적 락 사용
- **결정**: 주문 경로의 재고 차감은 비관적 락 기반 흐름을 기본으로 사용한다.
- **이유**: 동시 주문 상황에서 재고 정합성을 단순하고 명확하게 보장하기 쉽다.
- **트레이드오프**: 높은 경쟁 상황에서 락 대기와 DB 부담이 커질 수 있다.

### ADR-004: 관리자 재고 관리와 변경 이력 분리
- **결정**: 관리자 초기 재고 생성과 수동 증가/감소는 상품 API와 분리된 재고 API로 제공하고, 관리자 변경 이력은 `tbl_stock_history`에 저장한다.
- **이유**: 상품 등록/수정 책임과 재고 운영 책임을 분리하고, 변경 수량·사유·관리자 member id·시점을 감사 데이터로 보존할 수 있다.
- **트레이드오프**: 상품 생성 후 초기 재고 생성을 별도 호출해야 하며, 첫 버전의 이력 조회는 pagination 없이 상품별 전체 목록을 반환한다.

### ADR-005: Redis 캐싱은 RDB 커밋 이후 실행
- **결정**: Redis 작업은 기본적으로 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 분리하여 RDB 트랜잭션 커밋 이후에 실행한다.
- **이유**: Redis 장애 시 RDB 롤백을 방지한다. 멱등성 캐싱은 정합성이 아닌 편의 목적이므로 RDB 커밋이 완료된 뒤 별도 실행해도 무방하다. `@TransactionalEventListener`는 DDD 레이어 경계를 유지하며 Application이 Infrastructure를 직접 알지 않아도 되므로 `TransactionSynchronizationManager`보다 자연스럽다.
- **트레이드오프**: RDB 커밋 완료 ~ Redis 캐싱 완료 사이의 짧은 gap에서 동일 키 요청이 오면 캐시 MISS로 처리되어 중복 실행 가능성이 있다.
- **기능별 판단 기준**: 기본값은 AFTER_COMMIT 분리다. Redis 장애 시 RDB도 롤백해야 하는 정합성 최우선 상황에서는 동일 트랜잭션을 택하고, 해당 기능 ADR에 이유를 명시한다.
- **주의사항**: AFTER_COMMIT 시점은 트랜잭션이 이미 종료된 이후다. 핸들러 안에서 추가 DB 작업이 필요하다면 `Propagation.REQUIRES_NEW`로 새 트랜잭션을 열어야 한다. Redis만 다루는 경우라면 불필요하다.

### ADR-006: application 계층 클래스명은 Service suffix를 사용한다
- **결정**: 유스케이스 단일 책임 구조를 유지하되, 클래스 suffix는 `UseCase` 대신 `Service`로 명명한다.
- **이유**: Spring 기반 프로젝트 관습과의 일관성을 유지하고, 기존 코드베이스의 네이밍과 통일한다. 구조적으로는 UseCase 패턴과 동일하다 (`CreateOrderService` = `CreateOrderUseCase`).
- **트레이드오프**: DDD 순수론 관점에서 `UseCase`가 더 명확한 의도를 드러내나, 현재는 친숙한 네이밍을 우선한다.

### ADR-007: 인증 토큰 Redis 저장 실패 정책 — strict
- **결정**: Redis 저장/조회 실패 시 `AuthException(INTERNAL_ERROR)`을 던진다. Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다.
- **배경**: Redis 장애 시 soft fail(로깅만, access token은 발급)을 선택하면 클라이언트에 refresh token을 발급했으나 Redis에 없는 상태가 된다. 사용자는 access token 만료 시 재발급을 시도하다가 예상치 못한 "token not found" 에러를 받게 된다. 이는 "동작하는 것처럼 보이지만 실제로는 망가진" 상태로, 더 나쁜 사용자 경험을 유발한다.
- **이유**: refresh token은 Redis가 저장소 자체다. Redis 없이 발급된 refresh token은 반드시 실패한다. 명확한 즉각 실패가 지연된 묵시적 실패보다 사용자 경험이 낫다. 기존 로그인 사용자(유효한 access token 보유)는 Redis 장애에 영향받지 않는다. Redis 장애는 인프라 레벨(HA)에서 해결해야 할 문제다.
- **트레이드오프**: Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다. 기존 세션(유효한 access token)은 영향받지 않는다. 향후 과제: Redis 단일 장애점 해소를 위해 Sentinel 또는 Cluster 구성 필요.

### ADR-008: 회원가입 트랜잭션 분리 — `Propagation.NOT_SUPPORTED`
- **결정**: `AuthSignUpService.signUp()` method-level annotation을 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 교체한다.
- **배경**: `signUp()`이 `@Transactional`로 외부 트랜잭션을 열면 `MemberRegistrationService.register()`가 `REQUIRED` 전파로 합류한다. Spring에서 commit은 트랜잭션을 시작한 메서드가 종료될 때 발생하므로 `register()` 반환 후에도 DB는 미커밋 상태다. 그 사이에 `issue()`가 Redis에 저장하면 DB commit 전 Redis 저장 불일치가 발생한다. 단순히 method-level `@Transactional`을 제거하면 class-level `@Transactional(readOnly = true)`가 적용되어 readOnly 트랜잭션에 합류하며 Hibernate가 flush mode를 MANUAL로 설정하므로 의도와 다르게 동작한다. `@TransactionalEventListener(AFTER_COMMIT)` 방식은 응답 반환 후 이벤트가 실행되므로 Redis 저장 실패를 클라이언트에 전달할 수 없어 ADR-007 strict 정책과 양립 불가하다.
- **이유**: `NOT_SUPPORTED`는 class-level `readOnly = true`를 명시적으로 override한다. `signUp()`이 트랜잭션 없이 실행되면 `register()`가 자체 트랜잭션으로 commit 후 반환한다. 이후 `issue()` 호출 = DB commit 이후 Redis 저장 보장. 기존 `OrderCreateService.createOrder()`가 동일 패턴을 사용한다 (ADR 일관성).
- **트레이드오프**: DB commit 이후 Redis 저장 순서가 보장된다. Redis 저장 실패 시 strict 예외 처리와 결합하면 부분 실패 시나리오가 명확해진다 (member는 DB에 생성됐으나 auth 실패 → 다음 요청에서 DUPLICATE_EMAIL 또는 로그인 성공).

### ADR-009: `RefreshTokenStore.delete()` 제거
- **결정**: `RefreshTokenStore` 인터페이스와 `RedisRefreshTokenStore` 구현체에서 `delete()` 제거.
- **배경**: `RefreshTokenStore` 인터페이스에 `delete(Long memberId)`가 정의되어 있으나, 현재 로그아웃 서비스가 구현되어 있지 않아 어디서도 호출되지 않는다. 사용되지 않는 인터페이스 메서드는 CLAUDE.md 원칙("불필요한 추상화와 과한 설계를 피한다")에 어긋난다.
- **이유**: 호출부가 없는 코드를 유지하는 것은 잠재적 혼란을 유발한다. Git 히스토리가 이 메서드의 존재와 제거 이유를 기록한다. 로그아웃 구현 시 그 PR에서 `delete()`를 재추가하고 Redis 실패 정책을 함께 설계하는 것이 더 안전하다.
- **트레이드오프**: 인터페이스가 실제 사용 범위로 좁혀진다. 향후 과제: 로그아웃 기능 구현 시 `delete()` 재추가 및 Redis 실패 정책 결정 필요. 로그아웃은 보안 목적이므로 strict / soft 정책 선택이 신중히 검토되어야 한다.

### ADR-010: PaymentAttempt 멱등 재요청 amount mismatch는 명시적 예외로 거부
- **결정**: `(merchantPayKey, provider, paymentId, type)` 멱등 키에 대한 재요청이 기존 attempt의 amount와 다르면 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409 Conflict)를 던진다. 기존 attempt 상태(REQUESTED/FAILED/SUCCEEDED)와 무관하게 적용한다.
- **배경**: 기존에는 unique 제약 충돌 시 catch 블록에서 기존 attempt를 그대로 반환했다. amount가 다른 경우에도 침묵 처리되어 호출자 측 산출 오류나 PG 응답 검증/보상 취소 흐름에서 어떤 amount를 기준으로 삼을지 모호해진다. 멱등성 계약("같은 요청 → 같은 결과") 위반이 가시화되지 않는 문제다.
- **이유**: 호출자 측 mismatch(내부 원인)는 PG 응답 mismatch(`PAYMENT_AMOUNT_MISMATCH`, 400, 외부 원인)와 의미·모니터링 기준이 다르다. 별도 코드로 분리하면 알람/대시보드에서 원인 추적이 가능하다. 409 Conflict는 "이미 기록된 상태와 충돌한다"는 의미가 정확하다. amount 변경이 필요하면 새 `merchantPayKey`로 새 요청을 발급하는 게 정상 흐름이다.
- **트레이드오프**: 호출자가 잘못된 amount로 재시도하면 즉시 4xx로 실패한다. 기존에는 침묵 처리되어 후속 흐름에서 뒤늦게 발견될 수 있었다.

### ADR-011: DB unique 위반은 안전망 500 으로 위임하고 정상 흐름은 사전 `find` 로 처리한다 (find-first 패턴 통일)
- **결정**: Application 계층 5곳(`MemberRegistrationService`, `PaymentApprovalService`, `PaymentAttemptService`, `OrderCreateService`, `StockRestoreOutboxConsumeService`) 모두 `DB find → 없으면 insert → 충돌 시 500` 본질 흐름으로 통일한다. Application 과 Adapter 어디서도 `DuplicateKeyException` 을 catch 하지 않는다. `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러(`COMMON-500-2`) 를 추가해 DAO 카테고리 fallback 을 stack trace 와 함께 500 으로 처리한다. ADR-002 의 `(member_id, idempotency_key)` unique 위반 fallback 재조회 로직은 본 정책으로 대체되어, 정당한 멱등 재요청은 Redis reserve 성공 후 DB find 사전 체크로 흡수하고 race window 충돌은 안전망 500 으로 위임한다.
- **배경**: PR #106 (`docs/tasks/db-constraint-violation-handling/`) 에서 5곳을 `DuplicateKeyException` 좁은 catch 로 정리했으나 회고에서 "Application 이 인프라 예외 타입에 직접 의존한다" 는 부채가 분리되었다 (Issue #105). 후속 처리 옵션으로 (A) catch 를 Adapter 로 이동, (B) 5곳 모두 find-first 통일, (C) `Exception.class` fallback stack trace 보강만 검토했다. 옵션 A 는 5곳 처리 동작(멱등 흡수 / 도메인 예외 변환 / silent skip) 이 모두 달라 공통 변환 레이어가 의미 없고 도메인 매핑 지식이 Adapter 로 새는 문제가 있었다.
- **결정 근거**: 5곳의 unique 키는 모두 사용자 입력 식별자(email, merchantPayKey) 또는 idempotency key 기반이라 정상 흐름에서 동시 충돌 확률이 매우 낮다. 트랜잭션도 짧아 race window 가 좁다. find-first 패턴은 "트랜잭션 짧음 + 충돌 확률 낮음" 두 조건이 만족될 때 race window 비용이 안전망 500 처리로 충분히 흡수된다. 본 5곳은 이 조건을 만족한다. 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) 에는 본 정책을 적용하지 않고 try-save-catch 패턴이 더 적합하며, 향후 새 unique 제약 도입 시 위 두 조건으로 패턴을 선택한다. `DataAccessException` 부모 핸들러 추가는 운영 모니터링에서 DAO 카테고리 예외를 일반 `Exception` fallback 과 구분 가능하게 한다.
- **결과**: PR #106 정책(`DuplicateKeyException` 좁은 catch + 5곳 도메인 매핑) 은 폐기된다. 행위 변경은 race window 한정이다 — Member 가입 race 와 PaymentApproval race 는 4xx → 500, PaymentAttempt 2곳 race 는 200(멱등 흡수) → 500, StockRestoreOutbox race 는 200(silent skip) → 500, OrderCreate 는 DB find 사전 체크 추가로 행위 변경 없음. 정상 멱등/중복 흐름은 모두 사전 `find` 분기로 보존된다. Application 이 `org.springframework.dao.*` 패키지에 의존하지 않게 되어 계층 의존 방향 부채가 함께 해소된다. 상세 옵션 비교와 5곳 매핑은 `docs/tasks/unique-find-first-policy/adr.md` 와 `docs/architecture.md` 의 예외 처리 섹션을 참조한다.
- **트레이드오프**: race 발생률이 매우 낮다는 전제 위에 정책이 성립한다. 만약 향후 어느 곳에서 race 가 잦아지면 본 ADR 의 "적용 조건" 이 깨지고 try-save-catch 로의 전환을 재검토해야 한다. `Exception.class` fallback 의 stack trace 로깅 누락은 본 ADR 에서 다루지 않는다 (DAO 카테고리는 부모 핸들러로 해결됐지만 NPE 등 일반 예외는 여전히 message-only 로깅, 별도 개선 과제).
