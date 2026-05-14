# Architecture Decision Records

### ADR-001: JWT + Redis 기반 인증 유지
- **결정**: Access Token은 JWT로 처리하고 Refresh Token은 Redis에 저장한다.
- **이유**: 토큰 재발급 시 서버 검증과 강제 무효화가 가능하다.
- **트레이드오프**: 완전한 stateless 인증보다 저장소 관리 비용이 늘어난다.

### ADR-002: 주문 생성에 멱등 키 적용 (Redis 1차 방어선 + RDB unique 제약 최종 보장)
- **결정**: 주문 생성 요청은 멱등 키를 요구하며, Redis(1차)와 RDB unique 제약(최종)으로 이중 보장한다. `idempotencyKey`는 클라이언트가 생성한 UUID이며 HTTP Header(`Idempotency-Key`)로 전달한다.
- **멱등성 처리 흐름**: Redis `reserve()` 성공 시 주문 생성 → AFTER_COMMIT 이벤트로 Redis 캐싱 (ADR-005 구현). Redis MISS(TTL 만료 or Redis 장애) 시 바로 INSERT 시도 → `(member_id, idempotency_key)` unique 위반 시 기존 주문 반환.
- **Redis 장애 처리**: `reserve()`, `getCompletedOrderId()` 실패 시 Infrastructure 계층에서 fallback 값(`false`, `empty()`) 반환 → 주문 생성 경로로 자연스럽게 진입.
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
