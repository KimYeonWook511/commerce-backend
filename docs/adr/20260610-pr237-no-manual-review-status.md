# 대사 종착에 새 결제 상태(MANUAL_REVIEW)를 도입하지 않는다

- Status: accepted
- Date: 2026-06-10

## Context

새 상태는 보상 종착(결론 남)과 escalation 종착(결론 미상)을 한 값에 뭉쳐, 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)이 경계한 "한 상태가 두 현실을 뭉갬"을 반복한다. 그 구분을 소비하는 운영 기능도 아직 없다.

- **이유**: `status`는 "결제에 일어난 사실"만 담고, 후처리 대상 분류(대사/보상/수동/없음)는 정책이 `(status + failCode + 시간 + CANCEL row)`로 매번 계산한다 — 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)의 정신이다. 분류 결과를 status에 박으면 사실과 파생이 섞인다.

## Decision

escalation·보상 종착을 표현하려 초기 설계에 넣었던 `PaymentStatus.MANUAL_REVIEW`를 철회하고, 보상된 APPROVE 결제 상태를 FAILED로 유지하는 기존 결정(→ PR#236)을 따른다. `PaymentStatus`는 `REQUESTED/SUCCEEDED/FAILED/UNKNOWN` 4개만 유지한다. 보상된 APPROVE는 `FAILED`+failCode+CANCEL row(→ PR#236, 취소 주문 보상 환불 결정 → PR#237)로, escalation은 새 상태 없이 스캔 윈도우 상한(→ PR#237)으로 자동 제외하고 `UNKNOWN`으로 둔다.

## Consequences

상태 enum이 단순하게 유지된다. 정책 분류값 `PaymentPostProcessTarget.MANUAL_REVIEW`(대사 임계·MANUAL_REVIEW 격리 결정, → PR#224)는 status가 아니라 정책의 후처리 분류값이므로 그대로 유지된다 — 본 결정은 그 분류를 status로 승격하지 않는다는 것이다. escalation의 운영 가시성(통지·종착)과 "결론 났나/과금됐나" 축 분리는 그 구분을 소비하는 기능이 생기는 후속 #238에서 재검토한다(FAILED 유지 결정의 재검토 trigger, → PR#236).

관련: 보상된 APPROVE FAILED 유지 결정(→ PR#236), MANUAL_REVIEW 정책 분류값 결정(→ PR#224, 유지), escalation 스캔 시간 윈도우 상한 결정(→ PR#237), #238.
