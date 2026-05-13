# NaverPay DDD Migration Retrospective

## 배경

이번 작업은 `payment.naverpay` 패키지를 다른 도메인과 동일한 DDD 레이어 구조로 전환하고, PG 호출과 내부 결제 상태 반영을 분리했다.

`payment-ddd-migration-retrospective.md`에서 "다음 DDD 작업"으로 명시했던 항목을 이번에 완료했다.

## 이번 작업에서 확정한 기준

### naverpay 패키지를 유지하며 내부 레이어 구조를 DDD로 정리한다

naverpay는 payment 도메인의 provider 구현이다. 자체 도메인 엔티티가 없으므로 `payment.domain`을 사용하고, 독립 패키지로 유지하되 내부 구조를 다른 도메인과 동일하게 맞춘다.

```
payment/naverpay/
├── application/   ← NaverPayApprovalService
├── infrastructure/ ← NaverPayGateway, NaverPayClient, 코드 enum, Result 타입
├── presentation/  ← NaverPayController
└── exception/     ← 기존 유지
```

### PG 호출과 응답 코드 매핑은 Gateway로 분리한다

기존 `NaverPayService`(520줄)는 PG 호출, 응답 코드 매핑, 흐름 조율을 모두 담당했다. 이번 작업에서 PG 호출과 매핑을 `NaverPayGateway`로 분리했다.

```
NaverPayGateway (naverpay.infrastructure)
  - NaverPayClient 호출
  - NaverPayException 처리
  - 응답 코드(NaverPayApproveCode 등) → 도메인 코드(PaymentAttemptFailCode, PaymentErrorCode) 매핑
  - NaverPayApproveResult / NaverPayHistoryResult / NaverPayCancelResult 반환

NaverPayApprovalService (naverpay.application)
  - 흐름 조율만 담당
  - Gateway Result를 switch로 분기
  - PaymentApprovalService, PaymentAttemptService, OrderQueryService 호출
```

### Gateway는 application layer에 절대 의존하지 않는다

Gateway가 `PaymentAttemptService`를 호출하면 infrastructure가 application을 역방향으로 의존하게 된다. `failApprove`, `succeedCancel` 같은 attempt 상태 반영은 NaverPayApprovalService에 유지한다.

### Gateway에 인터페이스를 두지 않는다

NaverPay provider가 하나뿐이므로 인터페이스를 만들어도 구현체가 하나다. TossPay 등 두 번째 PG를 추가할 시점에 공통 PaymentGateway 인터페이스를 도입한다.

### Result 타입 명명

Gateway가 반환하는 타입과 controller에 반환하는 타입을 구분해야 한다.

- `NaverPayApproveResult` (`naverpay.infrastructure.result`) → Gateway → Service 전달용
- `NaverPayApproveResponse` (`naverpay.application.result`) → Service → Controller 반환용

기존 `NaverPayApproveResult`(service.result)를 `NaverPayApproveResponse`로 rename했다.

### 테스트는 Gateway mock으로 전환한다

기존 단위 테스트와 통합 테스트는 `NaverPayClient`를 mock으로 사용하면서 `NaverPayResponse` body를 직접 조작했다. Gateway 분리 후에는 `NaverPayGateway`를 mock으로 사용하고 `NaverPayApproveResult.success(...)` 같은 정적 팩토리를 직접 반환한다.

Gateway 단위 테스트(`NaverPayGatewayTest`)는 이번에 작성하지 않았다. 코드 매핑 자체는 응답 코드 문자열과 enum 매핑이므로 NaverPayClientTest에서 충분히 검증된다고 판단했다.

## 두 번째 PG 추가 시 적용할 원칙

- `payment.application`에 공통 흐름을 조율하는 `PaymentApproveService`를 도입한다
- `payment.domain` 또는 `payment.application.port`에 공통 `PaymentProviderGateway` 인터페이스를 정의한다
- `NaverPayGateway`, `TossPayGateway` 등이 이 인터페이스를 구현한다
- `NaverPayApprovalService`의 공통 흐름 부분을 `PaymentApproveService`로 올린다

## 다음 legacy 삭제 작업

naverpay 경계 정리가 완료됐으므로 각 도메인 legacy 삭제를 진행한다.

| 도메인 | 확인 명령 |
|--------|----------|
| stock | `rg "com\.commerce\.stock\.(service|controller|repository)"` |
| order | `rg "com\.commerce\.order\.(service|controller|repository)"` |
| product | `rg "com\.commerce\.product\.(service|controller|repository)"` |
| payment | `rg "com\.commerce\.payment\.(service|controller|repository)"` |
| member | `rg "com\.commerce\.member\.repository\.MemberRepository"` |
| auth | 별도 확인 필요 |

권장 커밋 메시지:

```text
refactor: <domain> legacy 패키지를 정리한다
```
