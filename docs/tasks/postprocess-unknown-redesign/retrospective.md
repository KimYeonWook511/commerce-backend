# 회고록: postprocess-unknown-redesign

## 1. 작업 요약

결제 후처리(대사/재시도) 결정 정책(test-side `postprocess` 패키지)을 `PaymentStatus.UNKNOWN` 도입 이후 모델에 맞춰 status 중심으로 재설계했다. `PaymentPostProcessTarget` enum 을 5값(`APPROVE_RECONCILE`·`CANCEL_RECONCILE`·`APPROVED_CANCEL_COMPENSATION`·`MANUAL_REVIEW`·`NONE`)으로 재정의하고, `PaymentPostProcessTargetPolicy` 의 식별 키를 failCode 열거에서 status(UNKNOWN / stale REQUESTED) 중심으로 전환했다. 대사 시작 임계를 NaverPay 승인 가능 시간(10분)에서 파생해 UNKNOWN(1분)/REQUESTED(15분)로 분리하고 장기 미해소 escalation→MANUAL 을 도입했다. `PaymentPostProcessFlowPolicy` 는 CANCEL_RECONCILE 분기를 분리하고 사용처가 사라진 `RelatedOrderStatus` 와 3-arg `resolveFlow` 를 제거했다. 2 step(redesign-target-policy, redesign-flow-policy)으로 구현하고, 코드 리뷰 후 경계·중간 구간 테스트와 의도 주석을 보강했다. production 코드·API·DB 스키마는 변경하지 않았다.

---

## 2. 설계 결정

자세한 결정 본문은 [task ADR](./adr.md)(staging L1~L3) 및 루트 ADR-029~031 참조.

| ADR | 핵심 결정 |
|---|---|
| ADR-029 (L1) | 후처리 대상 식별을 failCode 열거 → status(UNKNOWN / stale REQUESTED) 중심으로 전환. ADR-027/028 의 분류축(재시도 안전성)을 계승. 동시 UNKNOWN 은 approve 우선. |
| ADR-030 (L2) | 대사 임계를 NaverPay 승인 가능 시간(10분)에서 파생. UNKNOWN(빠른 폴링 1분)/REQUESTED(윈도우 이후 15분) 분리, escalation(시간 단위)→MANUAL. 경과 기준 시각 REQUESTED=createdAt, UNKNOWN/FAILED=respondedAt. cancel 은 응답 코드로 분기. |
| ADR-031 (L3) | mismatch·자동 포기(PG_REQUEST_REJECTED)·escalation 초과를 단일 MANUAL_REVIEW 로 격리. order 상태 결합은 #222 로 분리되어 `RelatedOrderStatus` 제거. |

---

## 3. 발견

### 시간 임계는 매직넘버가 아니라 외부 PG 시간 상수에서 파생돼야 한다

기존 정책의 5분 단일 임계는 NaverPay 의 **승인 가능 시간 10분**(`NaverPayApproveCode.TIME_EXPIRED`) 한가운데라, 정상 진행 중(AlreadyOnGoing)인 REQUESTED 를 stale 로 오판한다. 임계는 "PG 처리 시간 + 마진"에서 파생돼야 false positive 를 막는다. 또한 UNKNOWN(재결제 차단 + capture 후 ack 유실이면 즉시 복구 가능)과 REQUESTED(차단 아님 + 일찍 물어도 진행 중만 나옴)는 복구 가치가 달라 임계를 분리하는 것이 정합적이다.

### 후처리 정책은 그 잔여를 만드는 "실시간 보상 흐름"과 함께 봐야 한다

정책만 독립적으로 보면 견고해 보였으나, 코드 리뷰에서 실시간 보상의 잔여 상태 가정이 어긋난 지점들이 드러났다. (a) `compensateUnexpected` 가 *정상 승인된 결제(PG SUCCESS + verify 통과)인데 기록만 실패*한 건을 `FAILED(APPROVE_PROCESS_FAILED)`+환불로 처리해, 정책이 이를 `NONE` 으로 흘리면 돈이 박제된다. (b) `compensateDuplicateApproval` 이 다른 보상과 달리 *cancel-먼저→approve FAILED-나중* 순서라, 크래시 시 "approve REQUESTED + cancel" 이라는 정책이 가정하지 않은 잔여가 생긴다. 정책의 입력 상태를 "누가 어떤 순서로 만드는가"까지 추적해야 했다.

### `completeVerifiedApproval` 이 예외 전략(ADR-011)을 위반하고 dead code 가 쌓여 있다

이중결제 처리를 추적하다 — application(`NaverPayApprovalService`)이 `DataIntegrityViolationException` 을 catch 하는 것이 ADR-011(*app/adapter 에서 DAO 예외 catch 금지, find-first + 안전망 500*)과 정면 충돌함을 발견했다(payment-order-redesign 이 ADR-011 이후 환불을 위해 재도입). 동시에 `case PAYMENT_DUPLICATE → compensateDuplicatePayment` 는 try 에서 해당 예외가 던져지지 않아 **호출 불가능한 dead code** 였고, 그 dead 경로(fail-first)가 오히려 live 경로(cancel-first)보다 일관된 형태였다. 즉 이중결제 보상이 *(ADR 위반+cancel-first 라 쓰이는 쪽)* 과 *(더 깔끔하지만 안 쓰이는 쪽)* 으로 갈라져 있었다.

### "정상 승인 후 기록 실패 → 환불"은 모델 자체가 위험하다

`compensateUnexpected` 경로는 이미 `verifyApprovedResponse`(키·금액 일치)를 통과한 *맞는 결제*라, DB 데드락 같은 transient 실패에도 정당한 매출을 환불·취소한다. 올바른 모델은 "완료 재시도"이고, 이번에 만든 reconcile 경로(`APPROVE_RECONCILE` + PG_APPROVED → 완료)가 이미 그 일을 한다 — 실시간이 `FAILED` 로 마킹하지 않고 `REQUESTED` 로 두면 self-heal 된다.

---

## 4. 미결 과제

| 항목 | 상태 | 승격/결정 조건 |
|---|---|---|
| `completeVerifiedApproval` 보상·예외 처리 정리 (환불→완료 / `DataIntegrityViolationException` catch 제거·find-first / dead code·이중결제 순서 일관화 / ADR-011 재정합) | 후속 이슈 #225 | production + ADR |
| 주문 만료 취소 ↔ 결제 UNKNOWN 대사 타이밍 정합성 (SUCCEEDED 확정인데 주문 CANCELED) | 후속 이슈 #222 | 옵션(제외/시간정렬/보상) 결정 + ADR |
| 운영 전달 메커니즘(배치/스케줄러/이벤트), 리포지토리 status 스캔, PG 조회 wiring, race/잔여 멱등 재처리 | Epic #208 | 배치 도입 시 |
| `APPROVED_CANCEL_COMPENSATION` 5분 race 마진 제거(리뷰 #2) | #208 배치 도입 시 | 멱등키·지연과 함께 |

---

## 5. 개선 제안

**후처리/대사 정책은 그 입력 상태를 만드는 실시간 흐름과 한 묶음으로 본다.** 정책만 독립 재설계하면 실시간 보상의 잔여 가정(어떤 status 로, 어떤 순서로 남는가)이 어긋난 채로 굳는다. 정책 설계 시 "이 대상 상태를 누가·어떤 트랜잭션 경계로 만드는가"를 같이 추적하면 #225 류 갭(정상결제 환불·보상 순서 잔여)을 설계 단계에서 잡는다.

**시간 임계는 외부 PG 시간 상수에서 파생하고 근거를 주석/ADR에 박는다.** 5분 매직넘버가 NaverPay 10분 윈도우와 어긋났던 것처럼, 파생 근거가 없으면 false positive/과차단이 숨는다.

**경계·중간 구간 테스트를 기본 포함한다.** "well inside / well outside"만 검증하면 임계 duration 오타나 reconcile↔escalation 순서 뒤바뀜이 경계·중간(예: 3시간)에서 빠져나간다.

**코드 리뷰에서 드러난 인접 부채는 즉석 패치보다 별도 이슈로 정확히 분리한다.** 이번 리뷰는 정책 한 줄이 아니라 실시간 보상·예외 전략(ADR-011) 전반의 부채를 드러냈다. 섣불리 #224 에 환불 분기를 추가했다면 잘못된 모델을 굳혔을 것이다 — 발견을 #225/#222 로 분리하고 #224 는 정책 본체만 깨끗이 머지한 것이 옳았다.
