# Step 1 — payment-close-transition-without-throwing

## 목표

결제가 "승인을 아직 부르지 않았으면 종결하고, 부른 뒤면 종결하지 않고 그 사실을 돌려주는" 전이를 갖는다. 그 종결의 원인을 나타내는 종결 코드도 함께 더한다.

## 관련 문서

- `docs/specs/order-expiry-closes-ready-payment/spec.md` — 상태 전이 절, FR-006
- `docs/specs/order-expiry-closes-ready-payment/plan.md` — 구조 결정 3
- `docs/specs/order-expiry-closes-ready-payment/adr.md` — "종결 가능 여부 판정을 결제 도메인이 답하게 한다"
- `docs/package-structure-conventions.md` — 엔티티 생성·상태 전이 규칙
- `docs/test-code-conventions.md`

## 검증 대상

- SCN-004 주문 만료 종결이 구분되는 종결 코드로 기록된다 (FR-006)

## 구현 지시

1. 위 검증 대상 시나리오를 도메인 수준으로 옮기는 것부터 시작한다 — `tryExpire(ORDER_EXPIRED)`가 그 종결 코드를 남기는지. 배치가 실제로 그 코드를 넘기는지는 step 2가 확인한다. 시나리오가 적은 상태·값을 그대로 쓴다. 종결 코드만 봐도 대체 종결·결제 세션이 지난 종결과 가릴 수 있어야 한다.
2. `PaymentCloseCode`에 `ORDER_EXPIRED`를 더한다. 종착 상태는 `EXPIRED`로 기존 만료 종결들과 같다. 값의 뜻을 한 줄로 적는다 — 승인을 부르기 전에 주문 만료로 닫혔다는 것이 드러나, 승인 결과를 받은 뒤 주문이 결제 불가로 확인된 `ORDER_NOT_PAYABLE`과 갈려야 한다.
3. `Payment`에 종결 전이 `tryExpire(closeCode)`를 더한다. `READY`면 종결하고 `true`를 돌려준다. 승인을 부른 뒤의 결제(`IN_PROGRESS`·`UNKNOWN`·`SUCCEEDED`)면 종결하지 않고 `false`를 돌려준다. 종결된 결제(`FAILED`·`REJECTED`·`EXPIRED`)는 `false`에 섞지 않고 기존 `expire`처럼 전이 불가 예외를 던진다. `SUCCEEDED`는 코드상 종착 상태(`isTerminal()`)지만 활성 슬롯을 쥐므로 `false`로 답한다(`isTerminal()`로 가르지 않는다). `false`는 "승인을 부른 뒤라 종결하지 않았다"만 뜻해야 부르는 쪽이 멈출지를 바로 정할 수 있다. 종결 코드는 인자로 받는다.
4. `tryExpire`도 기존 `expire`처럼 받은 종결 코드가 `EXPIRED`에 속하는지 검사한다.
5. 종결되면 활성 슬롯이 비워지는 것은 상태를 바꾸는 기존 자리가 이미 한다. 따로 다루지 않는다.

## 주의사항

- 하지 마라: 메서드 이름에 주문 어휘를 넣는 것. 이유: 종결 코드를 밖에서 받으므로 결제는 왜 종결되는지 모른다. 이름에 주문을 박으면 결제가 주문의 사건을 알게 된다.
- 하지 마라: 승인을 부른 뒤라 종결할 수 없을 때 예외를 던지는 것. 이유: 종결하지 못하는 것은 오류가 아니라 부르는 쪽이 자기 흐름을 정하는 갈림이다. 예외로 던지면 배치가 그것을 건너뛰기로 세고 한도를 넘기면 정상 상황이 배치를 실패시킨다.
- 하지 마라: 기존 종결 전이를 지우거나 고치는 것. 이유: 결제 만료 배치가 그것을 계속 쓴다.
- 하지 마라: 이 문서의 시나리오 식별자를 테스트 이름·주석에 남기는 것. 이유: spec은 작업 후 아카이브되고 코드만 남아 그 식별자가 읽는 사람에게 의미를 잃는다.

## Acceptance Criteria

```bash
./gradlew test
```
