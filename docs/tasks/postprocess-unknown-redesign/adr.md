# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: 후처리 대상 식별을 failCode 열거에서 status(UNKNOWN / stale REQUESTED) 중심으로 전환한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 기존 `PaymentPostProcessTargetPolicy`는 approve "결과 불명"을 `FAILED` + failCode 열거(`PG_NETWORK_ERROR`/`PG_SERVER_ERROR`/`PG_INVALID_RESPONSE`/`APPROVE_PROCESS_FAILED`)로 식별했다.
- 루트 ADR-027/028(#206·#219) 이후 "결과 불명(재시도 안전성 측면에서 PG 처리 가능성 있음)"은 `FAILED`가 아니라 `status=UNKNOWN`으로 보존된다. 그 결과 옛 failCode 열거 분기는 더 이상 실제 상태와 매칭되지 않는다.

### 고려한 대안

- failCode 열거를 유지·확장: 현재 모델에서 그 failCode 조합으로는 "결과 불명" 케이스가 더 이상 생기지 않으므로 죽은 분기가 된다. 기각.

### 결정 내용

- 후처리 대상 식별 키를 **status 중심**으로 전환한다.
  - APPROVE `UNKNOWN` ∨ stale `REQUESTED` → `APPROVE_RECONCILE`
  - CANCEL `UNKNOWN` ∨ stale `REQUESTED` ∨ 재시도 가능 `FAILED`(CANCEL_PROCESS_FAILED·PG_INVALID_RESPONSE) → `CANCEL_RECONCILE`
  - approve `FAILED`(AMOUNT_MISMATCH·DUPLICATE_PAYMENT) ∧ cancel 기록 없음(실시간 보상 잔여) → `APPROVED_CANCEL_COMPENSATION`
  - SUCCEEDED / 확정 FAILED(TIME_EXPIRED·INVALID_MERCHANT 등) → `NONE`
- 루트 ADR-027/028의 분류축("재시도 안전성 = PG 처리 가능성")을 후처리 정책에서도 그대로 쓴다: UNKNOWN/stale = 대사, 확정 FAILED = NONE.

### 근거

- 식별 키가 현재 도메인 모델(UNKNOWN 일급)과 일치해야 정책이 실제 상태를 정확히 분류한다.
- ADR-028이 "누락=UNKNOWN, mismatch=FAILED"를 이미 확정했으므로, 정책은 그 status를 신뢰해 분기하면 된다.

### 결과

- approve 결과 불명 failCode 열거 분기가 사라진다. failCode 분기는 cancel 측 재시도 가능 분류와 mismatch 격리에만 축소되어 남는다.
- 동시 UNKNOWN(approve+cancel)은 approve를 먼저 확정한 뒤 cancel을 판단한다(검사 순서로 인코딩).

## ADR-L2: 대사 시작 임계를 NaverPay 승인 가능 시간(10분)에서 파생하고, UNKNOWN과 stale REQUESTED를 분리하며, 장기 미해소는 MANUAL로 승급한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 기존 정책은 approve/cancel 공통으로 5분(`APPROVE_REQUEST_DELAY` 등) 단일 임계를 썼다.
- NaverPay는 인증(pgPaymentId 발급) 후 **10분 안에 승인(capture)** 해야 하며 초과 시 `TimeExpired`다(`NaverPayApproveCode.TIME_EXPIRED`, HTTP read timeout 60초). 5분은 이 승인 윈도우 한가운데라, 정상 진행 중(AlreadyOnGoing)인 REQUESTED를 stale로 오판할 수 있다.

### 고려한 대안

- 단일 임계 유지(통합): Epic의 batch #1+#2 통합 방침과는 맞지만, UNKNOWN으로 차단된 사용자가 "capture 후 ack 유실"인 경우에도 10분+ 묶인다. 기각.
- count 기반 escalation: Payment에 시도 횟수 필드가 없어 새 persistence가 필요하다. 정책 범위(순수 함수) 밖이라 기각, 시간 기반 채택.

### 결정 내용

- 임계를 두 가지로 분리하고 NaverPay 시간에서 파생한다.
  - `UNKNOWN_RECONCILE_DELAY` ≈ 1분: UNKNOWN은 빨리 폴링. "capture 후 ack 유실"이면 `getApprovalHistory`가 즉시 APPROVED를 줘 빠르게 복구·차단 해제된다.
  - `REQUESTED_STALE_DELAY` ≈ 15분(승인 가능 시간 10분 + 마진 5분): 윈도우가 닫힌 뒤에만 reconcile해 오판을 막는다.
  - `ESCALATION_DELAY`(시간 단위): reconcile 대상이 임계를 넘도록 PG가 결론을 못 내면(PENDING/오류 지속) `MANUAL_REVIEW`로 승급한다.
- 경과 측정 기준 시각: REQUESTED는 `createdAt`, UNKNOWN/FAILED는 `respondedAt`(상태가 찍힌 시점).
- 임계 값은 정책 내 `Duration` 상수로 두되, 운영 config 승격(Epic #208)을 전제로 주석에 남긴다.
- cancel 측은 시간 임계가 아니라 NaverPay 응답 코드로 갈린다: `CancelDeadlineExpired`는 시간 무관 즉시 `MANUAL_REVIEW`, `CancelNotComplete`(NaverPay 자동 재처리)·`AlreadyOnGoing`은 폴링(`KEEP_WAITING`).

### 근거

- 임계는 매직넘버가 아니라 PG의 결제 처리 시간에서 파생되어야 false positive(정상 지연 오판)와 과도한 차단을 동시에 막는다(Epic #208 "stale 판단 시간 = PG 처리 시간 + 마진").
- UNKNOWN은 사용자를 차단(`existsUnknownByOrderId`)하므로 빠른 복구 가치가 크고, REQUESTED는 차단이 아니며 일찍 물어도 "진행 중"만 나오므로 대기가 낫다.

### 결과

- UNKNOWN 차단 사용자의 복구가 빨라지고, REQUESTED 오판이 사라진다.
- 장기 미해소(PG 장애 등)는 무한 KEEP_WAITING 대신 MANUAL로 격리된다.
- 트레이드오프: UNKNOWN을 빨리 폴링해 PG 조회 부하가 약간 늘지만, 배치 주기가 실효 간격을 지배한다. escalation은 poll-count가 아닌 age 기반 근사다.

## ADR-L3: merchantPayKey mismatch와 자동 포기 케이스를 MANUAL_REVIEW로 격리하고 RelatedOrderStatus를 제거한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- ADR-028은 merchantPayKey가 "존재하나 우리 키와 다른" mismatch를 `FAILED`(MERCHANT_PAY_KEY_MISMATCH)로 확정 종결한다. 이는 정상 사용자에게 발생하지 않는 신호(공격 시도 또는 심각한 데이터 정합성 위반)다.
- 기존 FlowPolicy는 mismatch를 위해 `RelatedOrderStatus`(PAID/INIT/NOT_FOUND) 3-arg 분기를 뒀다.

### 고려한 대안

- mismatch → NONE(실시간 종결로 충분): 조용한 FAILED 종결이라 보안/정합성 신호가 묻힌다. 기각.
- mismatch → UNKNOWN으로 합치기: "결과 불명"과 "확정적 정합성 위반"이 한 통에 섞여 대사 배치가 혼동된다. 기각.

### 결정 내용

- 다음을 모두 `MANUAL_REVIEW`로 격리한다.
  - approve `FAILED`(MERCHANT_PAY_KEY_MISMATCH)
  - cancel `FAILED`(PG_REQUEST_REJECTED) — 자동 취소 불가, 가맹점 자체 환불 필요
  - reconcile 대상의 escalation 초과(ADR-L2)
- `MANUAL_REVIEW`는 단일 enum 값으로 두고, 사유는 Payment 상태에서 도출한다(운영 alert가 구분).
- 사용처가 사라진 `RelatedOrderStatus`와 FlowPolicy의 3-arg `resolveFlow`를 제거한다.

### 근거

- mismatch는 자동 재시도/대사로 풀리지 않는 사람-개입 신호다. 명시 격리가 조용한 종결보다 안전하다(돈/보안 관련은 저확률 엣지도 사람 앞에 도달해야 한다).
- "SUCCEEDED 확정인데 관련 주문이 이미 CANCELED" 같은 order 상태 결합 판단은 #222(만료↔대사 타이밍)의 옵션 결정으로 분리되므로, 이번 정책에서 `RelatedOrderStatus`는 사용처가 없다(CLAUDE.md "사용처 없는 코드 안 남김").

### 결과

- mismatch·환불 거절·대사 장기 미해소가 단일 MANUAL_REVIEW로 모여 운영 알림 대상이 된다.
- `RelatedOrderStatus`와 3-arg flow가 제거되어 정책 표면이 단순해진다. #222가 order 상태 결합을 도입하면 그때 재설계한다.
