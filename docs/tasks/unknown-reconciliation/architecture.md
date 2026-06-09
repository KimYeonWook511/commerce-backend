# Task 아키텍처

> 이 문서는 이번 Task 시점의 **변경 제안 스냅샷**이다.
> 시스템의 현재 진실은 루트 `docs/architecture.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- payment 도메인에 **대사(reconciliation) 후처리 흐름**을 신설한다. stale UNKNOWN/REQUESTED 결제를 PG 조회로 확정해 사용자 차단을 해소하고, order 도메인의 만료 배치와의 타이밍 정합성을 확보한다.

## 변경 대상

- `payment.application`: `PaymentReconciliationService`(신규), `NotificationPort`(신규 port) + no-op 구현.
- `payment.postprocess`(신규 main 패키지): `src/test`의 Target/Flow Policy + enum 승격.
- `payment.domain`: `PaymentStatus.MANUAL_REVIEW` 추가, UNKNOWN/REQUESTED → SUCCEEDED/FAILED/MANUAL_REVIEW 확정 전이 메서드. `PaymentRepository` stale 스캔 메서드 + orderId IN 조회 메서드.
- `payment.<provider>` 또는 스케줄러 패키지: `@Scheduled` 대사 트리거.
- `order.application.port`(신규): 결제 상태 query port(order 소유). payment infrastructure가 구현(의존 역전).
- `order.batch`: 만료 reader가 chunk orderId 배치로 UNKNOWN 결제 주문 제외.

## 설계 방향

- **의존 방향 보존**: payment→order 의존은 이미 존재(`PaymentApprovalService`가 `order.completePayment` 호출). order→payment 직접 의존은 만들지 않고, A의 결제 상태 조회는 order가 소유한 query port를 payment adapter가 구현한다(`CartItemRemover` 선례와 동일한 의존 역전).
- **정책과 실행 분리**: "무엇을 할지"(target/flow 결정)는 승격된 정책이, "수집·PG조회·실행·상태전이"는 `PaymentReconciliationService`가 담당한다.
- **트랜잭션 경계**: stale 후보 조회 → (트랜잭션 밖) PG 조회 → 건별 단건 트랜잭션으로 상태 확정. 외부 호출을 트랜잭션에 묶지 않는다.
- **멱등성**: 이중 SUCCEEDED는 `uk_payment_approved_order_key`가 차단하고 상태 전이는 멱등 가드를 둔다. 분산 락은 두지 않는다(ADR-L2).

## 데이터 흐름

- **대사**: 스케줄러 → `PaymentReconciliationService.reconcile()` → stale 후보 스캔 → 건별: TargetPolicy로 target 결정 → `getApprovalHistory` PG 조회 → FlowPolicy로 flow 결정 → flow 실행(SUCCEEDED 확정+Order PAID / FAILED 확정 / KEEP_WAITING / MANUAL_REVIEW 승급).
- **A (만료 가드)**: 만료 reader가 INIT 후보 chunk를 읽음 → chunk orderId들을 query port로 IN 조회 → UNKNOWN 결제 걸린 orderId 제외 → 나머지만 `expireOrder`.
- **C (사후 보상)**: 대사 SUCCEEDED 확정 flow에서 `Order.completePayment()`가 CANCELED로 거부 → 보상 취소(`pgCancel` 재사용) → MANUAL_REVIEW 마킹 → `NotificationPort` 통지.

## 예외 및 실패 처리

- PG 조회가 PENDING/HISTORY_NOT_FOUND면 KEEP_WAITING(다음 주기 재시도).
- 자동 처리 상한(6시간) 초과 시 MANUAL_REVIEW 승급 → 자동 재시도 중단 + 통지.
- 보상 취소(C) 실패 시 MANUAL_REVIEW + 통지로 운영 개입 위임.
- 통지(`NotificationPort`)는 commit 이후 best-effort. 전송 실패가 대사/보상 트랜잭션을 막지 않는다.

## 테스트 포인트

- UNKNOWN→SUCCEEDED/FAILED 확정 및 Order PAID 반영, 차단 해제.
- stale REQUESTED 대사, escalation(6시간) → MANUAL_REVIEW 승급.
- 대사 멱등: 같은 건 두 번 처리해도 이중 SUCCEEDED/이중 환불 없음.
- A: UNKNOWN 결제 걸린 INIT 주문이 만료 대상에서 제외됨. UNKNOWN 풀린 뒤 정상 만료.
- C: 만료-취소-후-지연-승인 시나리오에서 보상 취소(환불) + MANUAL_REVIEW + 통지. 돈/주문/재고 정합성 보장.
- 만료 reader chunk 배치 조회가 N+1을 만들지 않음.
