# PAID 취소의 주문 락은 fetch join 단일 쿼리 대신 단일 행 락 + 아이템 별도 로드로 분리한다

- Status: accepted
- Date: 2026-06-18

## Context

PR #258 review에서 `distinct … join fetch o.orderItems … FOR UPDATE`(부모+자식 한 쿼리) 조합의 락 안전성이 제기됐다. 재고 차감 기본 전략으로 비관적 락을 쓰는 기존 결정(→ PR#59)과 연계된다.

## Decision

취소 흐름에서 주문을 잠글 때 `distinct … join fetch o.orderItems … FOR UPDATE`(부모+자식 한 쿼리)를 쓰지 않고, 주문 행 하나만 잠근 뒤 orderItems는 aggregate를 통해 lazy 로드한다.

취소는 사용자 단발 동작(hot path 아님)이라 RTT 1회 추가는 미미하다. 반면 락 범위를 주문 행 하나로 좁히는 것은 동시성 안전·미래 데드락 예방에서 큰 메리트다(돈 정합성 직렬화 락이라 더욱). "약간의 RTT < 좁은 락 범위"로 2단계 분리를 택한다.

## Consequences

- fetch join 1쿼리의 장점은 주문+아이템을 한 번의 DB 왕복(RTT)으로 가져와 네트워크 라운드트립을 아끼는 것이다. 2단계 분리의 비용은 아이템 로드 쿼리가 한 번 더 생겨 RTT가 1회 추가되는 것이다.
- fetch join+FOR UPDATE의 단점은 락이 부모를 넘어 자식(order_item) 행까지, 실행계획·인덱스 순서에 의존해 잡혀 락 범위가 넓어지는 것이다. 추후 order_item에 락을 거는 기능이 추가되면 겹치는 행을 다른 순서로 잠글 여지가 생겨 데드락 위험이 커진다.
- distinct/NonUniqueResult, distinct+FOR UPDATE의 SQL 거동 의존, 자식 락 순서의 plan 의존성 같은 모호함이 사라져 락이 검증 가능하게 확실해진다(H2·MySQL·Hibernate 버전 무관).
- 락 순서·데드락 검증은 후속 #259로 남는다.
