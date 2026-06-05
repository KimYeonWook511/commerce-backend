# 제품 요구사항 정의서 (Product Requirements Document): Commerce Backend

## 비전

회원이 상품을 조회하고, 장바구니에 담고, 안전하게 주문·결제하는 핵심 구매 흐름을 제공하는 커머스 백엔드다. 정합성(재고·결제·멱등)과 장애 격리(외부 캐시·PG 장애가 핵심 흐름을 막지 않음)를 우선한다.

이 문서는 제품이 제공하는 기능의 상위 인덱스다. 각 기능의 상세 계약은 `docs/api-spec.md`, 설계 결정은 `docs/adr.md`, 기능별 작업 맥락은 각 task의 `docs/tasks/<task>/prd.md`를 가리킨다. 본 문서는 task PRD 본문을 흡수하지 않는다.

## 제품 기능

사용자(회원·관리자)가 실제로 사용하는 기능이다. 새 기능이 생기면 이 표에 한 행을 추가한다.

| 기능 | 설명 | 상세 (api-spec / ADR) | 관련 task |
| --- | --- | --- | --- |
| 인증 | 회원가입·로그인·토큰 재발급. JWT(access) + Redis(refresh). Redis 장애 시 strict 실패 | api-spec §인증, ADR-001/007/008 | `auth-redis-timing`, `auth-refresh-token-store-unavailable` |
| 상품 조회 | 공개 상품 목록·상세 조회 (비로그인 가능, 노출 조건·재고 조합) | api-spec §상품, ADR (product-query) | `product-query` |
| 상품 관리 | 관리자 상품 등록·수정·soft delete | api-spec §관리자 상품 | `product-management` |
| 재고 | 주문 경로 차감·복구, 관리자 초기 생성·수동 조정, 변경 이력 | api-spec §관리자 재고, ADR-003/004 | `stock-management` |
| 장바구니 | 담기(UPSERT)·조회(최신가 재조립·구매불가 마킹)·수량 변경·삭제 | api-spec §장바구니, ADR-020 | `cart` |
| 주문 | 생성·취소·만료 배치. `Idempotency-Key` 멱등 (Redis in-flight + DB unique) | api-spec §주문, ADR-002 | `order-idempotency`, `order-idempotency-cache-simplification` |
| 결제 | 외부 PG(네이버페이) 예약(reserve)·승인(approve). 두 테이블 분리(Reservation + Payment append-only), UNKNOWN 마킹, 보상 | api-spec §결제, ADR-026/010~015 | `payment-order-redesign`, `payment-attempt-idempotency`, `payment-attempt-service-split`, `payment-attempt-state-transition-policy`, `payment-compensation-policy`, `payment-compensation-to-domain` |

## 기반 기술

제품 기능은 아니지만 정합성·운영성을 떠받치는 기반이다. 카테고리별로 묶으며, 개별 task를 나열하지 않고 대표 ADR을 가리킨다. 상세·이력은 `docs/adr.md`가 단일 출처다.

- **아키텍처 정책**: cross-aggregate는 ID 참조, same-aggregate만 객체 참조 (ADR-020). 응용 트랜잭션은 method-level `@Transactional` (ADR-021), 영속화 명시 호출 (ADR-022), DB unique 위반은 find-first + 안전망 500 (ADR-011).
- **마이그레이션·스키마**: Flyway 도입, `ddl-auto: validate` (ADR-024). enum은 `@JdbcTypeCode(VARCHAR)` (ADR-018), enum CHECK 제약 미사용 (ADR-025), multi-column unique 컬럼 길이 명시 (ADR-023).
- **관측성**: 요청 단위 traceId 전파(HTTP·Kafka·Outbox 경계), MDC 키 통합, 도메인 이벤트/경계 로깅 표준. 상세는 `docs/logging-conventions.md` 및 관련 ADR(017/019).
- **이벤트·비동기**: 재고 복구는 Outbox 패턴 + Kafka 전달.

## MVP 제외 사항

- 배송
- 쿠폰, 프로모션
- 리뷰
- 정산, 운영 백오피스
- 다중 PG 연동
