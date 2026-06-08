# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `postprocess-unknown-redesign`

## 배경

- 결제 후처리(대사/재시도) 결정 정책이 `src/test/java/com/commerce/payment/postprocess/`에 Target/Flow 정책 클래스 + enum + 테스트로만 존재한다(운영 배치는 미구현).
- 이 정책은 `PaymentStatus.UNKNOWN` 도입 이전 모델 기준이라 "결과 불명"을 `FAILED` + 특정 failCode(PG_NETWORK_ERROR 등) 열거로 식별한다.
- #205·#218·#220(루트 ADR-026/027/028)으로 "결과 불명"이 `status=UNKNOWN`(APPROVE·CANCEL 양쪽)으로 일급 표현되도록 바뀌어, 정책의 식별 키가 현재 도메인 모델과 어긋난다.
- Epic #208(결제 도메인 배치/스케줄러 처리 대상)의 batch #1(UNKNOWN 대사)·#2(stale REQUESTED) 결정 로직의 기반이다. 이슈 #221.

## 목표

- 후처리 결정 정책을 현재 도메인 모델(UNKNOWN 일급)에 정합하게 재설계한다.
- 이후 어떤 전달 메커니즘(배치/스케줄러/이벤트)이 와도 재사용 가능한 **순수 결정 로직**으로 유지한다.

## 범위

- 포함: `postprocess` 패키지의 Target/Flow 정책 enum·클래스·테스트 재작성.
- 제외: 운영 배치/스케줄러/이벤트 전달 메커니즘(별도 sub-issue), 리포지토리 status 스캔 쿼리, PG 조회 연동 wiring, 만료↔대사 타이밍 정합성(#222), 정기 대사·과거 데이터 마이그레이션.

## 주요 시나리오

- APPROVE UNKNOWN → PG 조회로 SUCCEEDED/FAILED 확정(재결제 차단 해제).
- stale REQUESTED → NaverPay 승인 윈도우가 닫힌 뒤 PG 조회로 확정.
- CANCEL UNKNOWN/미완 → PG 조회로 취소 완료/재시도 판정.
- mismatch / 환불 거절 / 대사 장기 미해소 → MANUAL_REVIEW.

## 요구사항

- 후처리 대상 식별을 status(UNKNOWN / stale REQUESTED) 중심으로 한다. approve "결과 불명" failCode 열거 분기는 제거한다.
- UNKNOWN과 stale REQUESTED의 대사 시작 임계를 분리한다(UNKNOWN은 빠르게, REQUESTED는 승인 윈도우 이후).
- 임계는 NaverPay 승인 가능 시간(10분)에서 파생한다.
- 대사가 장기 미해소면 MANUAL_REVIEW로 승급한다(escalation).
- merchantPayKey mismatch·자동 포기(PG_REQUEST_REJECTED)는 MANUAL_REVIEW로 격리한다.
- 사용처가 사라진 `RelatedOrderStatus`는 제거한다.

## 제약사항

- 정책은 순수 결정 함수로 유지한다(새 persistence·외부 호출 없음). 임계는 상수로 두되 #208이 운영 config로 승격할 수 있도록 한다.
- `api-spec.md` / `db-schema.md` / `architecture.md`는 생성하지 않는다(API·스키마 무변경, 운영 구조 변화 없는 test-side 정책 한정).
- `payment-order-redesign` 등 머지된 task 문서와 루트 ADR-026/027/028은 거슬러 수정하지 않는다(루트 동기화는 Stage 8에서 새 ADR append).
