# DDD 문서 운영 가이드

이 디렉터리는 기능 단위 PRD가 아니라 도메인 구조 개선, DDD 마이그레이션 전략, 도메인별 회고 문서를 관리한다.

## 문서 기준

### 전략 문서

- `ddd-migration-plan.md`: DDD 마이그레이션의 공통 방향, 패키지 구조 기준, 설계 원칙 (항상 최신 상태 유지)

### 도메인별 마이그레이션 회고

진행 순서대로 나열한다. 각 회고에는 실제 변경 내용, 확정된 기준, 남은 legacy 삭제 범위를 담는다.

- `stock-ddd-migration-retrospective.md`: Stock — application service 분리, DDD 구조 도입 첫 적용
- `order-ddd-migration-retrospective.md`: Order — batch와 application 역할 분리, OrderItem 경계 이동
- `product-ddd-migration-retrospective.md`: Product — AdminProductService / ProductQueryService 분리
- `payment-ddd-migration-retrospective.md`: Payment core — PaymentReadyService / PaymentApprovalService / PaymentAttemptService 분리
- `member-ddd-migration-retrospective.md`: Member — MemberRegistrationService / MemberQueryService 분리, auth API 책임 분리
- `auth-ddd-migration-retrospective.md`: Auth — security 웹 adapter 분리, JWT 구현을 infrastructure에 위치
- `repository-adapter-boundary-retrospective.md`: 전 도메인 Repository Adapter 경계 일관성 정리, 테스트 패키지 구조 확정
- `naverpay-ddd-migration-retrospective.md`: naverpay — application/infrastructure/presentation 레이어 전환, NaverPayGateway 경계 분리
- `outbox-ddd-migration-retrospective.md`: Outbox — DDD 구조 전환, StockRestoreOutboxService 분리(Create/Relay), 전 도메인 완료

## 작성 원칙

- 여러 도메인에 공통으로 적용되는 구조 기준은 `docs/ddd/`에 둔다.
- 마이그레이션 회고에는 실제 변경 내용, 남은 legacy 제거 범위, 다음 도메인 작업에 적용할 기준을 남긴다.
- 시행착오 끝에 확정된 기준은 `ddd-migration-plan.md`에 반영한다.
