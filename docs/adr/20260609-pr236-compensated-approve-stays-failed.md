# 보상된 APPROVE 결제 상태는 FAILED 로 유지하고 새 상태 도입은 의도적으로 미룬다

- Status: accepted
- Date: 2026-06-09

## Context

#232. FAILED 가 두 가지 다른 현실을 한 상태로 뭉갠다 — (a) PG 호출 자체 실패로 과금이 일어나지 않은 진짜 실패(merchantKeyMismatch), (b) PG 가 승인(과금)했으나 중복/금액불일치로 우리가 거부하고 보상(취소)한 건(duplicate, amount-mismatch). 회계/감사 언어로 (a)는 "거래 미발생", (b)는 "거래 발생 후 취소(charge→refund)"로 본질이 다르다. APPROVE row 단독으로는 이 둘을 구분할 수 없다.

- **고려한 대안**:
  - (A) 새 상태 도입 — `approved_order_key` 를 점유하지 않는 `SUPERSEDED`/`COMPENSATING` 상태로 "과금됨 + 보상대상"을 표현. 그러나 이 상태는 도메인 개념에서 자연스럽게 도출된 게 아니라 "SUCCEEDED 로 두고 싶지만 `uk_payment_approved_order_key`(NULL 트릭 partial unique) 위반 때문에 둘 수 없는" 제약 회피용 절충 상태다. 어색함 하나를 "왜 SUCCEEDED 가 아니고 SUPERSEDED 냐"라는 새 설명 부채로 바꾸는 셈이다.
  - (B) 경량 보강(조회 뷰·failCode 분류 헬퍼) — 소비처가 없어 사용처 없는 코드가 된다.
- **이유**:
  - **정보 무손실**: "과금-후-보상" 여부는 `failCode IN (DUPLICATE_PAYMENT, AMOUNT_MISMATCH)` + 같은 pgPaymentId 의 CANCEL row 존재로 결정적으로 복원된다. FAILED 가 가독성을 해칠 뿐 데이터를 잃지는 않는다.
  - **YAGNI**: 코드베이스에 "clean fail vs 과금-후-보상"을 구분해 소비하는 실제 대사(reconciliation)·분쟁 자동화 워크플로가 없다. 새 상태는 가정된 미래 요구를 위한 추상화다(사용처 없는 코드·과한 설계 회피 원칙).
  - **되돌리기 쉬운 결정**: 새 상태를 나중에 추가하는 비용이 지금 추가하는 비용과 거의 같다(enum 값 + 보상 dispatcher mark 교체 + `switch`/`exists*` 소비처 재검토). 미루어도 이자가 붙지 않는다. 과거 데이터도 `status=FAILED ∧ failCode IN (...)` 일괄 UPDATE 로 결정적 backfill 이 가능해 미래 전환이 막히지 않는다.
  - **정합성 무관**: 이는 표현/감사 가독성 문제이지 금전 정합성 문제가 아니다. 주문 이중결제·이중청구 차단은 #230(`uk_payment_approved_order_key`)·#233(보상 PG 취소 누락)·#235(예약 동시성)·승인 진입 사전 차단 결정(→ PR#235)에서 이미 보장된다.

## Decision

duplicate·amount-mismatch 보상으로 거부된 APPROVE 결제는 현행대로 `failIfRequested` 로 `status=FAILED` + `failCode`(DUPLICATE_PAYMENT/AMOUNT_MISMATCH) 로 마킹하고, 같은 pgPaymentId 의 CANCEL row 로 PG 취소를 표현한다. "과금됨 + 보상대상"을 정직하게 표현하는 새 상태(`SUPERSEDED`/`COMPENSATING` 등) 도입은 **기각**한다.

## Consequences

APPROVE row 상태 의미는 변경 없이 유지된다. FAILED 의 의미론적 부정확성(과금된 거부 건을 실패로 표기)은 알려진·수용된 한계로 명시한다.

- **재검토 trigger**: 실제 reconciliation/분쟁 자동화 기능이 도입되어 APPROVE row 단독으로 "과금-후-보상"을 구분해야 하는 소비처가 생기면, 그 요구에 맞춰 새 상태 도입을 재검토한다.

관련: #227, PR #228, #230(PR #233), #231(PR #235), #232, 보상 진행 여부를 Payment 존재로 판단하는 결정(→ PR#118)과 보상 완료 가드 제거 결정(→ PR#233), 승인 진입 사전 차단 결정(→ PR#235).
