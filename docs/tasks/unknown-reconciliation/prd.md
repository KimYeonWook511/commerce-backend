# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `unknown-reconciliation`

## 배경

- 결제 도메인에는 "시간이 지나면 누군가 정리해줘야 하는 상태"가 존재한다. 그중 가장 위험한 것이 `Payment UNKNOWN`이다 (PG 호출 결과 불명). UNKNOWN 행이 있는 주문은 reserve/approve가 차단되므로(ADR-6), 풀어주지 않으면 사용자가 그 주문에서 영영 결제할 수 없고, 실제로 PG가 승인했다면 **돈은 빠졌는데 미결제로 박제**된다.
- 이 UNKNOWN을 PG 조회로 SUCCEEDED/FAILED로 확정하는 **대사(reconciliation) 배치**는 `Epic #208`의 batch #1로 예약돼 있으나 아직 main 코드에 없다. 후처리 결정 로직(Target/Flow Policy)만 `#221`에서 재설계되어 `src/test/.../postprocess/`에 정책 + 테스트로 존재한다.
- 또한 주문 만료 배치(`OrderExpirationService`, 기본 60분)는 `status=INIT`만 보고 만료시키며 결제 상태를 보지 않는다. UNKNOWN 결제가 걸린 INIT 주문이 먼저 만료 취소·재고복구된 뒤 대사에서 그 결제가 뒤늦게 SUCCEEDED로 확정되면 **돈은 받았는데 주문은 취소·재고 복구됨**(`#222`). 확률은 희박하나 금전 정합성이라 안전장치가 필요하다.

## 목표

- stale UNKNOWN/REQUESTED 결제를 PG 조회로 자동 확정해 사용자 차단을 해소하고 돈 박제를 막는다.
- 만료 취소와 대사 확정이 겹쳐 발생하는 정합성 붕괴(돈은 받고 주문은 취소)를 원천 차단(A)하고, 뚫리더라도 자동 환불로 복구(C)한다.

## 범위

- 포함 범위
  - 후처리 정책(`PaymentPostProcessTargetPolicy`/`PaymentPostProcessFlowPolicy`/관련 enum)을 `src/test`에서 `src/main`으로 승격.
  - stale UNKNOWN/REQUESTED 스캔 쿼리 + `PaymentReconciliationService`(PG 조회 → 상태 확정 → Order PAID 반영, 자동 처리 상한 초과 시 MANUAL_REVIEW 승급).
  - `@Scheduled` 기반 대사 스케줄러.
  - `PaymentStatus.MANUAL_REVIEW` 상태 신설 + UNKNOWN/REQUESTED → 확정/승급 도메인 전이.
  - (A) 주문 만료 배치가 UNKNOWN 결제 걸린 주문을 만료 대상에서 제외 (order 소유 query port + payment adapter 의존 역전, reader chunk 배치 필터).
  - (C) 대사가 SUCCEEDED 확정 시 주문이 이미 CANCELED면 보상 취소(환불) + MANUAL_REVIEW + 통지.
  - `NotificationPort`(알림 추상화) 인터페이스 + no-op(로그) 구현.
- 제외 범위
  - 디스코드/슬랙/메일 등 실제 알림 채널 adapter 구현 (후속 이슈). 이번엔 `NotificationPort` + no-op까지만.
  - 분산 락(ShedLock) 도입 (다중 인스턴스 운영 진입 시 후속).
  - Epic #208의 나머지 batch(보상 취소 실패 재처리 #3, 만료 RESERVED 청소 #4, 정기 대사 #5).
  - 정기 결제 대사·과거 데이터 마이그레이션.

## 주요 시나리오

- **UNKNOWN 자동 복구**: 승인 호출이 timeout → Payment UNKNOWN, Order INIT. 1분 뒤 대사가 PG 조회 → 실제 SUCCEEDED → Payment 확정 + Order PAID + 차단 해제. 실제 실패면 FAILED 확정 + 차단 해제.
- **만료-대사 경합 차단(A)**: UNKNOWN 결제가 걸린 INIT 주문은 만료 배치가 만료 대상에서 제외한다. 대사가 FAILED로 확정해 차단이 풀린 뒤에야 다음 만료 사이클에서 정상 만료된다.
- **최후 안전망(C)**: A가 뚫려 이미 CANCELED된 주문의 UNKNOWN 결제가 대사에서 SUCCEEDED로 확정되면, succeed로 종결하지 않고 PG 보상 취소(환불) + MANUAL_REVIEW + 통지.
- **자동 처리 포기**: 대사 대상이 상한(6시간)을 넘도록 PG가 결론을 못 내면 MANUAL_REVIEW로 승급해 자동 재시도를 멈추고 운영자에게 통지한다.

## 요구사항

- 대사는 **멱등**하다. 같은 건을 두 번 처리해도 안전하다(`uk_payment_approved_order_key` 이중 SUCCEEDED 차단, 상태 전이는 멱등 가드).
- 만료 판단 주체를 섞지 않는다. 대사는 PG 조회로 결론을 내고, 시간은 "언제 PG에 물어볼지"만 정한다.
- 대사·보상은 commit 이후 best-effort로 통지한다. 통지 실패가 대사/보상 트랜잭션을 막지 않는다.
- order→payment 직접 의존을 만들지 않는다. A의 결제 상태 조회는 order가 소유한 port를 payment adapter가 구현한다.

## 제약사항

- 현재 모든 스케줄러가 분산 락 없이 동작(단일 인스턴스 전제). 이번에도 동일 패턴을 따르고, 멱등성으로 이중 처리를 방어한다.
- PG 조회는 `NaverPayGateway.getApprovalHistory`(기존)를 사용한다.
- 외부 호출(PG 조회·취소)은 DB 트랜잭션 경계 밖에서 수행한다.
