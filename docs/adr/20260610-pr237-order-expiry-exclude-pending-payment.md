# 주문 만료 배치는 미확정 결제가 걸린 주문을 만료 대상에서 제외한다

- Status: accepted
- Date: 2026-06-10

## Context

미확정 결제가 걸린 주문이 만료 취소·재고복구된 뒤 대사에서 그 결제가 SUCCEEDED로 확정되면, 돈은 받고 주문은 취소된 상태로 정합성이 붕괴한다(#222). 만료 배치는 본래 `status=INIT`만 본다.

- **이유**: 충돌을 사후가 아니라 원천에서 막는 편이 견고하다. 의존 역전은 order→payment 직접 의존을 만들지 않고 기존 경계·의존 방향을 보존한다. cross-aggregate FK·직접 join은 cross-aggregate 참조를 ID로 하는 기존 결정(→ PR#166)을 위반하므로 배제한다.

## Decision

미확정(UNKNOWN, 그리고 응답 저장 전 끊긴 stale REQUESTED) APPROVE 결제가 걸린 INIT 주문을 만료 배치가 만료 대상에서 제외한다(원천 차단). 결제 상태 조회는 order가 소유한 query port를 payment adapter가 구현하는 **의존 역전**으로 풀고(`CartItemRemover` 선례), 만료 reader가 chunk의 orderId들을 IN으로 한 번에 조회해 N+1을 피한다.

## Consequences

만료 조회에 결제 상태 결합 비용(chunk당 1쿼리)이 추가된다. 미확정이 풀리기 전엔 주문이 만료되지 않으므로, 대사가 결국 그 결제를 종결시켜 차단을 풀어줘야 정상 만료된다(대사와 짝).

관련: cross-aggregate ID 참조 결정(→ PR#166), 대사를 `@Scheduled` 서비스 루프로 구현하는 결정(→ PR#237), #222, Epic #208.
