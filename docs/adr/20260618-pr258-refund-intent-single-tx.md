# PAID 주문 취소의 환불 의도를 주문 취소와 단일 tx로 영속화한다

- Status: accepted
- Date: 2026-06-18

## Context

PG 취소는 외부 I/O라 주문 취소 tx에 넣을 수 없다(보상 정책은 payment.application 책임이고 PG 어댑터는 cancel 콜백만 제공한다는 기존 결정 → PR#125). "주문 CANCELED 커밋 → 그 다음 환불 트리거" 순서는 둘 사이 프로세스 중단 시 주문은 취소됐는데 환불 기록이 없는 상태를 낳는다.

환불 의도를 주문 취소와 원자적으로 영속화하면 어느 시점에 중단돼도 "환불해야 함"이라는 durable 기록이 남고, 그 마무리는 standalone CANCEL 대사 결정(→ PR#258)이 진다. 단일 DB 조건을 활용해 이벤트/Outbox 없이 cross-aggregate 정합을 확보한다.

## Decision

사용자가 PAID 주문을 취소하면, 조율 service가 한 RDB tx 안에서 `CANCEL 결제 REQUESTED(환불 의도) 영속화 + order.cancel() + 재고 복구`를 함께 커밋한다. 실제 PG 환불 호출은 tx 밖(커밋 이후)에서 best-effort로 실행한다.

## Consequences

조율 service의 단일 tx가 order·payment 두 aggregate 테이블을 함께 쓴다. 결합이 커지면(부분취소·다채널) Outbox 이벤트로 승격할 수 있고, 그때 CANCEL REQUESTED 행이 이벤트와 같은 역할을 한다.

관련: CANCEL 생성 멱등 unique 결정(→ PR#258).
