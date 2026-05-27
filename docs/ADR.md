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
- **결정**: Application 계층 6곳(`MemberRegistrationService`, `PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `OrderCreateService`, `StockRestoreOutboxConsumeService`) 모두 `DB find → 없으면 insert → 충돌 시 500` 본질 흐름으로 통일한다. Application 과 Adapter 어디서도 `DuplicateKeyException` 을 catch 하지 않는다. `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러(`COMMON-500-2`) 를 추가해 DAO 카테고리 fallback 을 stack trace 와 함께 500 으로 처리한다. ADR-002 의 `(member_id, idempotency_key)` unique 위반 fallback 재조회 로직은 본 정책으로 대체되어, 정당한 멱등 재요청은 Redis reserve 성공 후 DB find 사전 체크로 흡수하고 race window 충돌은 안전망 500 으로 위임한다.
- **배경**: PR #106 (`docs/tasks/db-constraint-violation-handling/`) 에서 5곳을 `DuplicateKeyException` 좁은 catch 로 정리했으나 회고에서 "Application 이 인프라 예외 타입에 직접 의존한다" 는 부채가 분리되었다 (Issue #105). 후속 처리 옵션으로 (A) catch 를 Adapter 로 이동, (B) 5곳 모두 find-first 통일, (C) `Exception.class` fallback stack trace 보강만 검토했다. 옵션 A 는 5곳 처리 동작(멱등 흡수 / 도메인 예외 변환 / silent skip) 이 모두 달라 공통 변환 레이어가 의미 없고 도메인 매핑 지식이 Adapter 로 새는 문제가 있었다.
- **결정 근거**: 5곳의 unique 키는 모두 사용자 입력 식별자(email, merchantPayKey) 또는 idempotency key 기반이라 정상 흐름에서 동시 충돌 확률이 매우 낮다. 트랜잭션도 짧아 race window 가 좁다. find-first 패턴은 "트랜잭션 짧음 + 충돌 확률 낮음" 두 조건이 만족될 때 race window 비용이 안전망 500 처리로 충분히 흡수된다. 본 5곳은 이 조건을 만족한다. 충돌이 잦을 것으로 예상되는 시나리오(예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race) 에는 본 정책을 적용하지 않고 try-save-catch 패턴이 더 적합하며, 향후 새 unique 제약 도입 시 위 두 조건으로 패턴을 선택한다. `DataAccessException` 부모 핸들러 추가는 운영 모니터링에서 DAO 카테고리 예외를 일반 `Exception` fallback 과 구분 가능하게 한다.
- **결과**: PR #106 정책(`DuplicateKeyException` 좁은 catch + 5곳 도메인 매핑) 은 폐기된다. 행위 변경은 race window 한정이다 — Member 가입 race 와 PaymentApproval race 는 4xx → 500, PaymentAttempt 2곳 race 는 200(멱등 흡수) → 500, StockRestoreOutbox race 는 200(silent skip) → 500, OrderCreate 는 DB find 사전 체크 추가로 행위 변경 없음. 정상 멱등/중복 흐름은 모두 사전 `find` 분기로 보존된다. Application 이 `org.springframework.dao.*` 패키지에 의존하지 않게 되어 계층 의존 방향 부채가 함께 해소된다. 상세 옵션 비교와 5곳 매핑은 `docs/tasks/unique-find-first-policy/adr.md` 와 `docs/architecture.md` 의 예외 처리 섹션을 참조한다.
- **트레이드오프**: race 발생률이 매우 낮다는 전제 위에 정책이 성립한다. 만약 향후 어느 곳에서 race 가 잦아지면 본 ADR 의 "적용 조건" 이 깨지고 try-save-catch 로의 전환을 재검토해야 한다. `Exception.class` fallback 의 stack trace 로깅 누락은 본 ADR 에서 다루지 않는다 (DAO 카테고리는 부모 핸들러로 해결됐지만 NPE 등 일반 예외는 여전히 message-only 로깅, 별도 개선 과제).

### ADR-012: PaymentAttempt succeed/fail 메서드는 상태 전이를 도메인에서 검증한다
- **결정**: `PaymentAttempt`의 `succeed(respondedAt)` 및 `fail(failCode, detail, respondedAt)` 메서드는 호출 시점에 `status == REQUESTED` 조건을 검증한다. 위반 시 `PaymentException`(`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`, 500)으로 거부. 멱등 자기 전이도 거부.
- **배경**: 기존 mark 메서드 4개(`markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`)는 (1) `status == REQUESTED`, (2) `type`이 메서드 의도와 일치를 동시에 검증했다. 분리된 두 Service(`PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`)가 항상 올바른 type의 attempt만 조회·전달하므로 도메인 내 type 가드는 방어 가치를 잃어 제거됐다. mark 4개는 `succeed`/`fail` 2개로 통합됐다. ADR-012의 핵심 결정("REQUESTED 외 전이 거부 + failCode 보호")은 status 가드만으로 동일하게 보존된다.
- **이유**: 멱등성은 상위 레이어(`PaymentApprovalAttemptService.getOrCreate` + `NaverPayApprovalService.processApproveAttempt` switch)에서 처리되므로 `succeed`/`fail`은 멱등을 책임지지 않는다. Order 도메인의 명시적 선조건 검증 패턴과 일관. 도메인 무결성 위반은 내부 결함 신호라 외부 입력 mismatch(ADR-010, 409)와 구분되도록 500.
- **트레이드오프**: 새 검증 도입 시 catch 블록 안에서 `succeed`/`fail`이 호출되는 호출처(예: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`)는 race window에서 throw해도 보상 트랜잭션이 중단되지 않도록 적절히 보호해야 한다. 보상 catch 2차 예외 처리의 일반 원칙은 ADR-013으로 정의했다(`docs/exception-strategy.md` 참조). 상세는 `docs/tasks/payment-attempt-state-transition-policy/adr.md` 참조.
- **후속 (ADR-014, payment-compensation-policy task)**: ADR-D의 임시 처방(try-catch 보호 한 곳)이 ADR-014(Payment 존재 체크)로 대체됐다. race window에서 `succeed`/`fail`이 throw되는 경로 자체가 줄어들어 ADR-012의 엄격한 검증 원칙은 그대로 유지된다. #117(멱등 자기 전이 허용) close.

### ADR-013: 보상 catch 2차 예외 처리는 1차 예외 ERROR 로깅 + 의도 캡슐화 메서드 패턴을 따른다
- **결정**: 보상 흐름의 catch 블록은 (a) 진입 즉시 1차 예외를 `log.error`로 ERROR 레벨에 남기고, (b) 2차 시도가 던질 가능성이 있는 예외는 가급적 메서드 자체(`...IfRequested` 등)에서 캡슐화해 호출처에서 try-catch 없이 호출하도록 설계하고, (c) 그래도 던지는 경우 중요도에 따라 `log.warn` + 1차 예외 전파(덜 중요) 또는 Composite Exception(`addSuppressed`)으로 둘 다 전파(치명적) 한다. 의사결정 트리와 적용 예는 `docs/exception-strategy.md` "보상 catch 2차 예외 처리" 섹션 참조.
- **배경**: PR #112(ADR-012)에서 `PaymentAttempt` mark 메서드 선조건 검증이 추가되며 보상 흐름이 catch 안에서 mark 호출 시 race window에서 `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`를 만날 수 있게 됐다. 임시로 `failApproveAndCancelApprovedPayment` 안에 `try { failApprove(...) } catch (PaymentException markEx) { log.warn(...) }`를 넣었지만 catch 범위가 너무 넓어 `PAYMENT_ATTEMPT_NOT_FOUND` 같은 의도치 않은 예외까지 삼키는 문제가 있었다. 동시에 `completeVerifiedApproval`의 상위 catch 두 곳(`PaymentException`, `CustomException`)에 1차 예외 `log.error`가 누락돼 운영 원인 추적이 어려웠다.
- **이유**: catch 안에서 호출하는 메서드를 "예외 안 던지는 의도 캡슐화 메서드"로 만들면 호출처에서 try-catch가 사라지고 도메인 상태(예: `status == REQUESTED`) 검사가 application 레이어로 누출되지 않는다. 호출처는 의도(예: "가능하면 실패 처리, 아니면 skip")만 표현하고 도메인 규칙은 서비스 경계 안에 머문다. 1차 예외 ERROR 로깅은 운영 모니터링에서 근본 원인을 항상 보존하는 최소 보장이다. 로그 레벨은 1차 = ERROR, 2차 = WARN으로 구분해 1차 원인을 더 강하게 노출한다.
- **트레이드오프**: "Skip" 의도 캡슐화 메서드(`...IfRequested`)가 늘면 서비스 API 표면이 약간 넓어진다. 다만 호출처마다 try-catch 또는 if-status 검사가 흩어지는 것보다 응집도가 높다. Composite Exception(`addSuppressed`)은 치명적 케이스에서만 사용하고, 일반적으로는 catch 안 메서드를 "예외 안 던지게 설계"하는 쪽을 우선한다.

### ADR-014: 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다
- **결정**: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`는 PG cancel 진행 전 `PaymentApprovalService.isCompensationRequired(merchantPayKey)`를 호출해 Payment가 이미 존재하면 cancel을 skip한다.
- **배경**: 기존 구조는 `PaymentAttempt.status`로 보상 진행 여부를 판단했으나 attempt에 lock이 없어 race window에서 SUCCEEDED attempt에 cancel이 호출되는 결함(#114)이 있었다.
- **이유**: Payment는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique 제약이 있고 `completeApprovedPayment`가 Order FOR UPDATE 안에서 저장하므로 race-safe하다. DDD 관점에서 Payment Aggregate의 불변식을 cross-Aggregate 협력으로 활용한다. 미래 Payment 도메인 분리 시 `isCompensationRequired`는 외부 API로 자연 승격 가능하다.
- **트레이드오프**: Payment 조회 1회 추가되나 인덱스 조회라 성능 영향 미미하다.
- **PaymentAttempt Aggregate 캡슐화**: `PaymentAttempt.succeed`/`fail` 메서드는 `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService` 외부에서 직접 호출하지 않는다. 정책 강제는 코드가 아닌 ADR과 JavaDoc으로만 명시하며, ArchUnit 도입은 별도 후속 작업으로 분리한다.
- **후속 (ADR-015, payment-compensation-to-domain task)**: 보상 owner가 `NaverPayApprovalService.failApproveAndCancelApprovedPayment`에서 payment.application의 `PaymentApprovalCompensationService.runPgCancel`로 이동했다. `isCompensationRequired` 호출자가 바뀌었을 뿐 정책 자체(Payment 존재 체크 → cancel skip)는 동일하게 유지된다.

### ADR-015: 보상 정책은 payment.application 책임이고, PG 어댑터는 cancel 콜백만 제공한다
- **결정**: `NaverPayApprovalService`에 있던 보상 dispatcher 4개와 공통 골격을 `PaymentApprovalCompensationService`(payment.application)로 이동한다. PG cancel 호출은 `PgCanceller` @FunctionalInterface 콜백으로 위임하고, PG 응답은 `CancelOutcome` record로 변환해 payment.application이 `NaverPayCancelResult`를 직접 import하지 않도록 한다.
- **배경**: 보상 정책(어떤 실패 → cancel 필요/불필요, cancel reason, cancel amount)은 PG-agnostic 결제 도메인 책임이다. PG-specific한 부분은 cancel API 호출과 NaverPayCancelResult 응답 해석뿐이다. NaverPayApprovalService가 보상 정책을 내장하면 레이어 의존이 역전되고 PG 변경 시 정책 코드도 함께 영향받는다.
- **이유**: `PgCanceller` 좁은 콜백은 PaymentGateway port 완전 inversion(PG 둘 이상 추가 시)보다 지금 필요한 최소 구조만 도입한다. NaverPayApprovalService가 메서드 참조(`this::pgCancel`)로 구현하므로 인터페이스 추가 없이 의존 역전이 성립한다.
- **트랜잭션 정책**: `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional` 없음. `isCompensationRequired`의 `REQUIRES_NEW` 격리(ADR-014)를 보존하기 위해 각 단계가 자기 트랜잭션을 가진다.
- **트레이드오프**: PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치를 재설계해야 한다. 이때 PaymentGateway port 완전 inversion으로 자연 승격 가능하다.

### ADR-016: 부하 테스트 도구로 k6 + InfluxDB + Grafana 채택
- **결정**: 부하 테스트는 k6를 사용하고, 메트릭은 InfluxDB(1.8)에 저장해 Grafana로 시각화한다. 로컬 환경에서만 실행한다.
- **배경**: 주요 API의 성능을 정량적으로 측정한 데이터가 부재했고, 부하 시나리오의 정량 검증 수단이 필요했다. 운영 환경 모니터링·CI 통합은 별도 트랙으로 분리한다.
- **이유**: k6는 JavaScript로 시나리오를 표현해 가독성이 높고 `thresholds`로 SLO를 정량 검증할 수 있다. InfluxDB(1.8)는 k6 native output과 호환성이 검증돼 있으며(별도 xk6 빌드 불필요), Grafana 공식 k6 대시보드 템플릿(#2587)을 그대로 활용할 수 있어 시각화 도입 비용이 낮다. 대안 도구(JMeter, Gatling)는 GUI/XML 설정 부담 또는 Scala 학습 비용이 더 크다.
- **트레이드오프**: 부하 테스트 결과는 로컬 환경 사양에 의존하므로 절대 수치보다는 개선 전후의 상대 비교가 주된 활용 방식이다. CI 자동 실행·운영 환경 측정은 본 결정 범위 밖이며 후속 과제로 둔다.

### ADR-017: Kafka traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 구현한다
- **결정**: Kafka producer가 메시지를 발행할 때 `TraceIdKafkaProducerInterceptor`가 MDC `traceId`를 헤더 `X-Trace-Id`에 부착하고, consumer가 수신할 때 `TraceIdRecordInterceptor`가 헤더에서 traceId를 추출해 MDC에 push한다.
- **배경**: HTTP 요청 단위 traceId(이슈 #129, traceid-mdc-filter)가 Kafka 경계에서 단절되어 producer-consumer 흐름 추적이 불가능했다. 해결 방법으로 (A) 헤더 직접 부착(producer/consumer 코드 수정), (B) Spring Kafka 표준 확장점(ProducerInterceptor + RecordInterceptor)을 비교했다.
- **이유**: (B)가 producer/consumer 코드 시그니처를 무손상으로 유지하고, 향후 추가되는 producer/consumer에도 자동 적용된다. `DefaultKafkaProducerFactoryCustomizer` Bean 등록 방식은 `application.yml` 프로퍼티 방식 대비 프로파일별 누락 위험이 없다. `RecordInterceptor.afterRecord()` 콜백은 error handler·DLT 발행까지 완료된 이후 호출되므로 MDC 정리 시점이 보장된다.
- **트레이드오프**: outbox relay 스케줄러 → consumer 흐름에서 원 HTTP 요청 traceId와 consumer 로그가 연결되지 않는다. 이 연결은 OutboxEvent에 traceId 컬럼 추가가 필요하며 별도 후속 작업으로 분리된다. 상세는 `docs/tasks/kafka-trace-propagation/adr.md` 참조.

### ADR-018: Hibernate 6.x ENUM 매핑은 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한다
- **결정**: 모든 entity의 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다. 컬럼 길이는 명시하지 않고 Hibernate 기본값(VARCHAR(255))을 사용한다. 신규로 추가되는 entity의 `@Enumerated(EnumType.STRING)` 필드도 동일 패턴을 따른다.
- **배경**: Hibernate 6.x부터 MySQL dialect가 `@Enumerated(EnumType.STRING)`을 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑한다. MySQL ENUM은 INSERT 시 컬럼 생략 시 첫 번째 ENUM 값이 조용히 삽입되며, `ddl-auto: update`로 컬럼 추가 시 기존 row에 첫 번째 값이 자동 채워진다. VARCHAR였다면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM에서는 묻힌다. Hibernate 6.5 공식 마이그레이션 가이드는 `@JdbcTypeCode(SqlTypes.VARCHAR)` 또는 `@Column(columnDefinition = "varchar(N)")` 두 방식을 제시한다.
- **이유**: `@JdbcTypeCode`가 dialect-agnostic하고 선언적이며 `@Column(length=N)`과의 분리가 가능하다. `columnDefinition`은 raw SQL fragment를 박아 dialect 변경에 fragile하고 length 속성과 충돌한다. 또한 향후 native ENUM을 채택할 때 annotation 하나만 제거하면 되는 전환 비용이 낮다. 컬럼 길이를 명시하지 않는 이유는, enum 값은 개발자가 정의한 코드 상수만 저장되어 외부 입력 길이 제한 같은 보안/검증 의미가 없고, length를 명시하면 enum 추가 시 동기화 부담만 발생하기 때문이다.
- **트레이드오프**: JPA 표준에서 벗어나 Hibernate-specific annotation을 도입한다. 다만 entity 코드는 이미 Hibernate에 결합되어 추가 부담은 미미하다. 컬럼 길이 통제권은 약해지나 enum 특성상 통제 가치가 낮다.
- **한계**: Hibernate `ddl-auto: update`는 컬럼 타입 변경(ENUM → VARCHAR)을 보장하지 않는다. 본 코드 변경만으로는 운영 DB의 기존 ENUM 컬럼이 그대로 남을 가능성이 있다. 운영 DB ALTER는 Flyway 도입 시 일괄 마이그레이션 스크립트로 정리한다. ENUM 컬럼 생성 시점부터 본 fix 전까지 "첫 번째 enum 값이 조용히 삽입된" 의심 row 점검은 별도 후속 트랙이다.
- **참고**: Hibernate 6.5 Migration Guide, Hibernate Discourse "String Enum mapping for MySQL only". 상세는 `docs/tasks/hibernate-enum-jdbc-type-code/adr.md` 참조.

### ADR-019: 비동기/이벤트 경계 traceId 전파는 명시적 동봉 방식으로 구현한다
- **결정**: Spring Event 경계는 이벤트 객체에 traceId 필드를 동봉하고, Outbox 경계는 `tbl_outbox_event.trace_id` 컬럼에 저장한 뒤 relay 시 MDC로 복원한다. 두 경계 모두 publisher 시점의 MDC traceId를 명시적으로 전달한다. Outbox 스케줄러 자체에서는 traceId를 발급하지 않고, MDC에 유효한 traceId가 없거나 outbox.trace_id가 NULL이면 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback).
- **배경**: ADR-017(Kafka traceId 전파)로 Kafka 경계는 해결됐으나, `@TransactionalEventListener(AFTER_COMMIT)`과 Outbox relay 경계에서는 여전히 traceId가 단절되어 결제 승인 → outbox 발행 → kafka consume → 재고 복구 흐름과 주문 생성 → Redis 멱등성 캐시 흐름을 단일 traceId로 추적할 수 없었다. Spring Event는 (A) 이벤트 객체에 traceId 동봉, (B) `ApplicationEventMulticaster` wrapping을 비교했다. Outbox는 (A) 스케줄러 진입 시 신규 UUID 발급, (B) DB 컬럼에 원본 traceId 저장, (C) 현행 유지(Kafka 레벨 fallback만)를 비교했다.
- **이유**: Spring Event는 현재 사용처가 `OrderIdempotencyCacheEvent` 한 곳뿐이라 Multicaster wrapping은 한 군데에서만 쓰일 추상화로 과하다. Outbox는 (A) 스케줄러 단위 발급 시 한 실행에서 여러 독립 거래가 같은 traceId를 공유해 의미가 희석되고, (C) 현행 유지 시 Kafka 레벨에서 새 UUID가 발급되어 원 HTTP 요청과 단절된다. (B) DB 컬럼 저장만이 원본 HTTP 요청의 traceId를 consumer까지 전파한다.
- **트레이드오프**: Outbox 스케줄러 자체 로그는 traceId가 없다(운영 통계 로그 성격이므로 허용). 기존 outbox 데이터 및 MDC에 유효한 traceId가 없는 케이스는 outbox.trace_id를 NULL로 저장하고 relay 시 MDC 조작 없이 진행한다(Kafka 인터셉터가 신규 UUID fallback). Spring Event 객체마다 traceId 필드를 추가하는 반복 작업이 향후 필요할 수 있으며, 이벤트가 5개 이상 늘어나는 시점에 Multicaster wrapping으로 재검토한다. DB 스키마 변경(`tbl_outbox_event.trace_id VARCHAR(64) NULL`)이 필요하나 nullable이고 기존 인덱스에 영향이 없어 무중단 적용 가능하다.
- **참고**: 상세는 `docs/tasks/event-outbox-trace-propagation/adr.md` 참조.

### ADR-020: 신규 도메인의 cross-aggregate 참조는 ID로 한다
- **결정**: 본 phase의 `cart` 도메인을 기점으로, 이후 신설되는 모든 도메인은 다른 aggregate를 `Long` ID로만 참조한다. `@ManyToOne`, `@JoinColumn`, cross-aggregate `@OneToOne` 사용을 금지한다. `cart`의 `CartItem`은 `memberId`, `productId`를 원시 `Long`으로 저장하며 다른 aggregate를 객체로 참조하지 않는다.
- **배경**: 기존 도메인은 `Order.member`, `OrderItem.product`, `Stock.product` 등 `@ManyToOne` 객체 참조를 광범위하게 사용한다. 그러나 application 계층은 대부분 `memberId`, `productId` 등 ID 기반으로 흐름을 다루고 있어 도메인 모델과 application 인터페이스 사이에 이중 표현이 발생한다. 이로 인해 N+1 회피와 fetch join 부담, 도메인 결합도 증가, 단위 테스트에서의 객체 그래프 구성 부담, DDD "다른 aggregate는 ID로만 참조" 원칙 위반 등 누적 부채가 있었다. 신설 도메인부터라도 기본값을 ID 참조로 두자는 결정이다.
- **결정 근거**: DDD 정통(Eric Evans, "Reference Other Aggregates Only By Identity") 원칙에 부합한다. (a) 다른 aggregate와의 결합도가 감소해 도메인 변경 영향 반경이 좁아진다. (b) JPA lifecycle 함정(detached entity, cascade, lazy proxy)을 피할 수 있다. (c) 단위 테스트가 원시 ID로 단순화되어 객체 그래프 setup 부담이 사라진다. (d) 향후 마이크로서비스 분리 시 aggregate 경계가 서비스 경계와 자연스럽게 정렬된다. cart 조회 시 `productRepository.findAllById(productIds)`로 명시적으로 Product를 한 번 더 조회해 응답을 조립하는 비용은 PK 기반 인덱스 조회라 무시 가능하다.
- **트레이드오프**: DB 참조 무결성을 FK 제약이 보장하지 않는다. 대신 application 흐름·UNIQUE 제약·삭제 순서 정책이 정합성을 책임진다. 기존 Order/Stock/StockHistory 등의 `@ManyToOne` 참조는 호환성 부담이 크고 본 phase 범위가 아니므로 마이그레이션하지 않고 별도 트랙으로 분리한다.
- **적용 범위**: 본 ADR 이후 신설되는 모든 cross-aggregate 참조에 적용한다. 같은 aggregate 내 root-child 관계(예: `Order ↔ OrderItem` 같이 동일 aggregate 안의 collection)는 본 정책 대상이 아니며 기존대로 객체 참조를 허용한다. 기존 cross-aggregate 객체 참조의 ID 참조로의 마이그레이션은 별도 작업으로 다룬다.
- **참고**: 상세는 `docs/tasks/cart/adr.md` 결정 2 참조.

### ADR-021: 응용 Service의 `@Transactional`은 method-level에만 부착한다
- **결정**: 응용 Service(`com.commerce.<domain>.application.*Service`)에 class-level `@Transactional` 부착을 금지한다. 모든 트랜잭션 경계는 method-level `@Transactional`로만 표현한다. retry loop를 포함하는 outer Service는 어노테이션 없이 두고, 트랜잭션 경계는 별도 Processor 빈의 method-level `@Transactional`이 책임진다(`OrderCreateProcessor` 패턴, 본 cart phase의 `AddCartItemProcessor`/`UpdateCartItemQuantityProcessor` 등).
- **배경**: 기존 코드베이스는 class-level `@Transactional(readOnly = true)` 기본 + method-level `@Transactional` 쓰기 메서드 override 패턴이 광범위하다(`OrderCreateService`, `OrderCancelService`, `AuthLoginService` 등). 본 패턴은 (a) 메서드의 트랜잭션 정책이 한눈에 안 들어와 class 선언으로 시선이 이동해야 하고, (b) 새 메서드를 추가하면서 method-level 어노테이션을 누락하면 의도와 다른 정책(`readOnly`)이 silent로 적용되며, (c) 코드 리뷰 시 누락 여부가 표면에 드러나지 않는다.
- **결정 근거**: method-level만 사용하면 (a) 모든 메서드의 트랜잭션 정책이 코드 표면에 명시되고, (b) 누락은 곧 "트랜잭션 없음"으로 즉시 드러나며, (c) 메서드별 정책 차이가 한눈에 비교 가능하다. class-level "기본값 + override" 구조가 주는 코드 줄 수 절약 가치보다 명시성·실수 방지 가치가 더 크다는 판단이다.
- **트레이드오프**: 메서드 수만큼 어노테이션이 반복된다. 다만 어노테이션이 곧 정책 명세 역할을 하므로 가독성 손실이라기보다 의도 표현이다. 조회 전용 Service에서도 `@Transactional(readOnly = true)`를 메서드마다 부착해야 한다.
- **적용 범위**: 본 ADR 이후 신설되는 응용 Service에 적용한다. 본 cart phase의 4개 Service(`AddCartItemService`, `GetMyCartService`, `UpdateCartItemQuantityService`, `RemoveCartItemService`)에 적용된다. 기존 도메인(Order/Stock/Auth 등)의 class-level `@Transactional` 마이그레이션은 본 ADR의 후속 트랙으로 분리한다.
- **Processor 패턴과의 관계**: retry/멱등 등 트랜잭션 외부에서 처리해야 할 흐름을 가진 Service는 어노테이션 없이 outer 역할만 담당하고, 실제 트랜잭션은 별도 Processor 빈에 method-level `@Transactional`로 둔다. retry attempt마다 빈 경계를 넘어가며 새 트랜잭션·새 persistence context가 시작되고, self-invocation 함정이 회피된다.
