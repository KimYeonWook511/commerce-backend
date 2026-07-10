# mismatch·자동 포기·대사 장기 미해소를 MANUAL_REVIEW로 격리하고 RelatedOrderStatus를 제거한다

- Status: accepted
- Date: 2026-06-08

## Context

UNKNOWN 보존을 history 재확인 경로로 확장한 기존 결정(→ PR#220)이 merchantPayKey가 *존재하나 우리 키와 다른* mismatch를 `FAILED`(MERCHANT_PAY_KEY_MISMATCH)로 확정했다. 이는 정상 사용자에게 발생하지 않는 신호(공격 시도 또는 데이터 정합성 위반)다.

mismatch는 자동 대사/재시도로 풀리지 않는 사람-개입 신호이므로 조용한 종결보다 명시 격리가 안전하다(돈/보안은 저확률 엣지도 사람 앞에 도달해야 한다). "SUCCEEDED 확정인데 관련 주문이 이미 CANCELED" 같은 order 상태 결합 판단은 #222로 분리되어, 이번 정책에서 `RelatedOrderStatus`는 사용처가 없다(CLAUDE.md "사용처 없는 코드 안 남김").

## Decision

`approve FAILED(MERCHANT_PAY_KEY_MISMATCH)`, `cancel FAILED(PG_REQUEST_REJECTED)`, reconcile escalation 초과를 단일 `MANUAL_REVIEW`로 격리한다(사유는 Payment 상태에서 도출, enum을 쪼개지 않음). 사용처가 사라진 `RelatedOrderStatus`와 FlowPolicy의 3-arg `resolveFlow`를 제거한다.

## Consequences

mismatch의 자동 회수가 수동으로 바뀐다. #222 도입 전까지 그 사이 발생분은 MANUAL 큐에 쌓인다.

연계: UNKNOWN 보존 확장 결정(→ PR#220), #222(order 상태 결합 도입 시 재설계), Epic #208.
