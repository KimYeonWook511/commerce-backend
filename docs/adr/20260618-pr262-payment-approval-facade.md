# 결제 승인 확정 조율을 provider 중립 facade로 모으고 결제→주문 단방향 결합으로 정리한다

- Status: accepted
- Date: 2026-06-18

## Context

결제 대사·승인이 `order.getStatus()` 분기로 주문 상태머신을 결제 쪽에서 돌려, 주문 상태가 늘면 결제 분기가 폭발했다(PR #237 H1·M1·dead 분기의 산물, #240). Order↔Payment 경계를 분리한 결제 도메인 재설계의 기존 결정(→ PR#205) 위에 있다.

조율자를 한 점에 두면 각 도메인은 자기 일만 한다. 트리거(PG 승인 응답·대사 스캔)가 모두 결제 쪽이라 조율자도 payment.application에 둔다. provider 중립 위치라 두 번째 provider 진입점도 같은 facade를 재사용한다.

## Decision

여러 도메인을 엮는 승인 확정 흐름(승인 사실 확정 + 거부 보상)을 `payment.application`의 provider 중립 조율 UseCase로 모은다. 실시간 승인·대사 두 진입점이 이 facade를 공유한다. facade는 tx를 열지 않는 orchestrator이며, usecase는 tx 없이 흐름을 조립하고 tx 단위작업은 service가 갖는 기존 결정(→ PR#248)에 따라 단위작업을 기존 service에 위임한다. 결합은 facade 한 점에만 격리한다 — `payment.domain`은 order를, `order.domain`은 payment를 모른다.

## Consequences

- facade가 order errorCode에 의존하지만(거부 사유 해석) 그 의존은 한 점에 격리된다.
- 별도 조율 패키지는 흐름이 하나뿐이라 YAGNI — 적립·쿠폰 등이 더 엮이면 그때 승격한다.
- 거부 사유 errorCode 세분화, PAID 성공-주체 분기 제거, gateway resolver 미도입은 같은 PR의 별도 결정으로 이어진다.
