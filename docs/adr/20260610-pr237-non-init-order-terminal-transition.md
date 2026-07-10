# 대사 중 주문이 비-INIT이면 건너뛰지 않고 종착 상태로 전이한다

- Status: accepted
- Date: 2026-06-10

## Context

비-INIT 거부를 그냥 건너뛰면 결제가 `UNKNOWN`으로 남아 매 주기 무한 재시도된다(PR #237 리뷰).

- **이유**: 종착 상태(SUCCEEDED/FAILED)로 전이해야 스캔 대상에서 빠진다. `PAID`에서 중복 여부를 판별해 정당한 결제의 오환불을 막는다. 새 상태 없이 기존 상태+failCode로 표현한다 — 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)을 따른다.

## Decision

대사가 승인 확정을 시도할 때 주문 완료가 비-INIT 상태로 거부되면, 건너뛰지(미확정 유지) 않고 주문 상태별로 종착시킨다 — `CANCELED`→보상 환불(FAILED+failCode, 취소 주문 보상 환불 결정 → PR#237), `PAID`→이미 다른 성공 결제 존재 여부로 판별(있으면 중복 결제→보상, 없으면 이 건이 성공 주체→SUCCEEDED 맞춤), 주문 없음→ERROR 로그 + FAILED. 어떤 경로든 다음 주기에 재스캔되지 않게 한다.

## Consequences

비-INIT 경합 건이 결정적으로 종착된다. `PAID` 분기는 주문 기준 성공 결제 존재 조회를 추가로 수행한다.

이 결정의 PAID 성공-주체→SUCCEEDED 확정 분기와 `order.getStatus()` 상태 분기는 이후 facade errorCode 분기로 전환되며 제거됐다(→ PR#262). 비-INIT 종착·취소/중복 보상 정신은 유지된다.

관련: 보상된 APPROVE FAILED 유지 결정(→ PR#236), 취소 주문 보상 환불 결정(→ PR#237), escalation 시간 윈도우 결정(→ PR#237), #237.
