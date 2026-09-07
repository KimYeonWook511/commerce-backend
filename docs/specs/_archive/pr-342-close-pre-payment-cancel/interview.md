# 인터뷰 기록 — 결제 전 주문 취소를 사용자 경로에서 닫는다

- 일시: 2026-09-06 02:36
- 대상: 기존 코드 수정 (issue #292)
- 축 점수: 목표 0.90 · 제약 0.85 · 완료 기준 0.85 · 기존 맥락 0.90 → 모호함 0.125
- 활성 방법론: ddd(0.1.0) · bdd(0.1.0). 엔진 6.0.1이 두 min_engine_version(1.5.0 / 3.0.0)을 모두 만족하고 requires·conflicts_with 선언이 비어 충돌 없음.

## 확정된 것

- **무엇을 하나**: `INIT`(결제 전) 주문에 대한 `POST /orders/{orderId}/cancel`을 거부한다. 결제 전 주문 정리는 만료 배치(`OrderExpirationJobScheduler` → `ExpireOrderService`)가 전담한다. `PAID` 취소는 그대로 둔다.
- **왜 하나**: 취소된 주문에 승인이 도착했을 때의 보상(`ConfirmApprovalUseCase` → `ClosePaymentUseCase.rejectOrderNotPayable`)은 이미 구현되어 있어 데이터는 수렴한다. 문제는 그 예외 상황용 경로가 정상 흐름에서 발생해, 회원 카드 명세에 승인과 취소가 나란히 남고 결제사 호출이 두 번 낭비된다는 것이다.
- **왜 방어 추가가 아닌가**: 가드로 닫으려면 취소 경로와 결제 시작 경로 두 곳이 필요하다. `StartPaymentUseCase`는 트랜잭션을 열지 않고 주문 행을 잠그지도 않은 채 `order.checkPayable()`만 읽어, 취소가 "활성 결제 없음"을 확인한 직후 결제가 시작되면 같은 상태가 다시 만들어진다. 그렇게 해서 얻는 것은 "결제를 시작하지 않았거나 종결된 주문의 재고를 만료 전에 돌려받는 것" 하나뿐이다.
- **`CancelOrderService` 처리** (질문 → 답): 프로덕션 사용처가 `CancelOrderUseCase` 하나뿐이라 삭제한다. 사용처 없는 코드를 남기지 않는다는 저장소 원칙을 따른다.
- **데드락 테스트 처리** (질문 → 답): "주문 생성과 주문 취소가 동시에 일어나도 데드락이 발생하지 않는다"를 `CancelPaidOrderService` 기반으로 옮겨 검증 대상을 보존한다. 그 테스트가 검증하는 "재고 행 락을 상품 ID 오름차순으로 잡는다"는 계약이 `CancelPaidOrderService.restoreStock`에 그대로 있기 때문이다.

## 읽어서 확정된 사실

- 실질 주문 상태는 `INIT` / `PAID` / `CANCELED` 셋이다. `RECEIVED`·`COMPLETED`는 enum에만 있고 프로덕션 전이가 없다(테스트에서 reflection으로만 세팅).
- order 도메인의 엔드포인트는 `POST /orders`와 `POST /orders/{orderId}/cancel` 둘뿐이고 `GetMapping`이 없다. 회원이 결제 전 주문을 조회할 수단이 없다.
- `docs/api-spec.md`의 취소 절이 "`INIT`과 `PAID` 주문을 취소할 수 있습니다 / `INIT` 취소: 재고만 복구합니다"를 명시하고 있어 갱신 대상이 분명하다.
- `CancelOrderService`를 쓰는 테스트는 7개이고 성격이 둘로 갈린다.
  - 결제 전 취소 자체를 검증: `OrderApplicationServiceTest` 4건, `OrderCancelUseCaseTest` 2건, `OrderConcurrencyServiceTest` "같은 주문에 취소 요청이 동시에 와도 한 번만 취소된다"
  - 취소를 도구로 사용: `OrderApplicationServiceIntegrationTest` "재고가 부족하면 주문이 실패하고 취소 후 다시 주문할 수 있다", `OrderConcurrencyServiceDeadlockMysqlTest` "주문 생성과 주문 취소가 동시에 일어나도 데드락이 발생하지 않는다", `OrderConcurrencyServiceDebugTest`(learning 태그)
- 결제된 주문 취소 쪽 동시성 테스트(`CancelOrderUseCaseConcurrencyTest`, `PartialCancelOrderUseCaseConcurrencyTest`)는 전부 `PAID` 대상이라 영향받지 않는다.
- 데드락 테스트는 `@Tag("concurrency")`라 현재 CI에서 돌지 않는다.

## 드러난 가정

- API 경로 자체는 유지하고 `INIT` 분기만 거부한다. 경로를 없애거나 새 주소를 두지 않는다.
- 이 API를 쓰는 외부 클라이언트의 호환성은 고려하지 않는다.

## 남은 미확정

- `INIT` 취소 거부의 응답 — 기존 `OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED`를 쓸지 새 코드를 둘지, HTTP 상태를 무엇으로 할지. (위험영역 아님 — API 계약 세부라 Design에서 확정)
- 취소를 도구로 쓰던 나머지 2건의 대체 수단 — 통합 테스트의 재고 복구를 무엇으로 대신할지, learning 태그 디버그 테스트를 지울지. (Design·Steps에서 확정)
- `CANCELED`로 종착한 주문에 취소 요청이 왔을 때의 동작을 지금 그대로 유지하는지 — 현재는 결제된 주문 취소 경로로 보내 그 트랜잭션이 멱등 재생을 판정한다. (상태 전이라 위험영역 — Specify에서 마커로 남겨 Clarify에서 확정)
