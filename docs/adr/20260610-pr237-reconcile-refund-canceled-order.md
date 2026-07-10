# 대사가 승인 확정한 결제의 주문이 이미 취소됐으면 보상 환불한다

- Status: accepted
- Date: 2026-06-10

## Context

원천 차단(만료 배치의 미확정 결제 주문 제외, → PR#237)이 뚫리는 극단 경합에서, 이미 취소된 주문의 미확정 결제가 대사에서 성공으로 확정될 수 있다. 그냥 종결하면 돈은 받고 주문은 취소된 채 박제된다.

- **이유**: 원천 차단(A)으로 막고 사후 보상(C)으로 받치는 belt-and-suspenders가 돈 정합성에 가장 견고하다(희박해도 안전장치). 보상은 검증된 기존 경로를 재사용해 신규 위험을 줄인다.

## Decision

대사가 UNKNOWN→SUCCEEDED로 확정한 뒤 주문 완료가 CANCELED 상태로 거부되면, 보상 취소(PG 환불)를 실행하고 APPROVE 결제를 `FAILED` + failCode(`ORDER_CANCELED`) + CANCEL row로 종착시킨 뒤 통지한다. 보상된 APPROVE는 새 상태가 아니라 FAILED+failCode로 표현한다 — 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)을 준수한다. 보상 경로는 기존 보상 서비스/PG 취소를 재사용한다.

## Consequences

사후 환불 경로가 대사 flow에 추가된다. 보상 취소 자체가 실패하면 CANCEL row를 UNKNOWN 보존(재처리 후속) + 통지로 운영 개입에 위임한다.

관련: 보상된 APPROVE FAILED 유지 결정(→ PR#236), 만료 배치의 미확정 결제 주문 제외 결정(→ PR#237), #222, Epic #208(batch #3).
