# step2 — cancel-payment-reconciliation

## 목표

standalone CANCEL 결제(REQUESTED/UNKNOWN)를 대사해 종착시키는 경로를 신설한다(ADR-L4). 이것이
PAID 취소 환불의 **안전망**이다 — 인라인 PG 호출이 실패·UNKNOWN으로 끝나거나 호출 전 프로세스가
중단돼 CANCEL이 stale로 남으면, 이 대사가 집어 마무리한다. payment 단독으로 검증·머지 가능하다.

## 배경·맥락 (중요)

- 현재 대사(`ReconcilePaymentUseCase`)는 `type='APPROVE'`만 스캔하고, `processOne`은
  `resolvePostProcessTarget(payment, null, now)`로 호출해 `CANCEL_RECONCILE` target을 SKIP한다
  (`ReconcilePaymentUseCase.java:170, 181-184`). **standalone CANCEL을 구동하는 경로가 없다.**
- 그러나 정책 뼈대는 이미 있다: `PaymentPostProcessTargetPolicy`의 CANCEL 분기는 cancelPayment가
  non-null이면 `CANCEL_RECONCILE`을 반환하고, `PaymentPostProcessFlowPolicy`는
  `CANCEL_RECONCILE + PG_CANCELED → 이미취소 처리`, `CANCEL_RECONCILE + PG_APPROVED → 취소 재시도`
  매트릭스를 갖는다. **배선만 죽어 있다.** 이 step은 그 배선을 채워 죽은 정책을 live로 만든다.
- 새 정책·새 PG 로직을 만들지 않는다. 기존 cancel 상태전이 service들·`PgCanceller`·
  `getApprovalHistory`를 재사용한다.

## 구현 지시

### 1) 스캔 쿼리 추가

- `JpaPaymentRepository`에 `findStaleCancelPaymentsForReconciliation`를 추가한다. APPROVE 스캔
  (`findStaleApprovePaymentsForReconciliation`)을 미러링:
  - `type='CANCEL'`, `status IN ('UNKNOWN','REQUESTED')`
  - UNKNOWN은 `respondedAt`, REQUESTED는 `createdAt` 기준으로 staleCutoff·escalationCutoff 윈도우 적용
  - `ORDER BY p.id ASC`, 동일 페이징(batch size)
- `PaymentRepository` 포트에도 메서드를 노출한다.

### 2) reconcile 루프에 CANCEL 처리 분기 추가

- `ReconcilePaymentUseCase.reconcile()`이 APPROVE 스캔에 더해 CANCEL stale 후보도 스캔해 각각
  처리하도록 확장한다. 각 CANCEL 후보 처리:
  - `resolvePostProcessTarget(approvePayment=null, cancelPayment=p, now)` → `CANCEL_RECONCILE`
    (이제 non-null로 호출 → 죽은 분기 live화).
  - PG 조회(tx 밖): `naverPayGateway.getApprovalHistory(pgPaymentId)` → verificationStatus.
  - `flowPolicy.resolveFlow(CANCEL_RECONCILE, verificationStatus)`:
    - **PG 취소됨(ALREADY_CANCELED)** → `SucceedCancelPaymentService.succeed(...)` (환불 확정)
    - **PG 승인 유지(CANCEL_RETRY)** → `PgCanceller.cancel(...)` 재시도 → 결과 반영
      (SUCCESS→succeed / FAILED→fail / UNKNOWN→markUnknown)
    - **PENDING·NOT_FOUND** → KEEP_WAITING (상태 변경 없이 다음 주기)
  - 상태전이 충돌·가드 예외는 tx 밖에서 흡수(skip)한다(기존 대사의 SKIPPABLE 패턴과 동일).

### 3) escalation (stale 초과 + FAILED)

- **stale 초과**: CANCEL이 6시간 초과로 종착 못 하면 APPROVE와 동일하게 escalation 통지 대상에
  포함한다(기존 escalation 후보 조회·통지 흐름을 CANCEL까지 확장하거나 병렬 처리). 통지 중복 차단은
  기존 `escalatedAt` 메커니즘을 따른다.
- **FAILED 환불**: CANCEL이 FAILED(확정적 환불 실패)로 종착하면 자동 재시도하지 않고 escalation
  통지로 사람에게 넘긴다(ADR-L4). FAILED를 조용히 종착시키면 환불 미집행이 묻히므로, 통지로 surface
  한다. 통지 중복 차단은 `escalatedAt` 메커니즘과 동일하게 1회만 발화하도록 한다. 재시도+백오프
  자동 엔진은 이번 범위 밖(#208 item-3)이다.

## 하지 마라

- 새 상태 전이 정책이나 새 PG 호출 로직을 만들지 마라. 이유: 정책 뼈대는 이미 있고 이 step은
  배선만 채운다. 새로 만들면 기존 APPROVE 대사와 정책이 갈라진다.
- APPROVE 대사 동작을 바꾸지 마라. 이유: 이번 추가는 CANCEL 스캔·처리뿐이다. APPROVE 경로 회귀를
  만들지 않는다.
- 시간이 결론을 내게 하지 마라. 이유: stale 판단은 "언제 PG에 물어볼지"만 정하고, SUCCEEDED/재시도
  결론은 PG 조회(`getApprovalHistory`)가 낸다(기존 대사 원칙).

## 관련 파일

- `src/main/java/com/commerce/payment/infrastructure/persistence/JpaPaymentRepository.java` (APPROVE 스캔 쿼리 미러링)
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/application/usecase/ReconcilePaymentUseCase.java` (processOne / reconcile 루프)
- `src/main/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java` (CANCEL 분기 — 이미 존재)
- `src/main/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlowPolicy.java` (CANCEL 매트릭스 — 이미 존재)
- `src/main/java/com/commerce/payment/application/service/` (Succeed/Fail/MarkUnknown CancelPaymentService)
- step1의 환불 실행 경로(재시도 실행 공유 가능)

## Acceptance Criteria

```bash
./gradlew test --tests "*CancelReconcil*"
./gradlew test --tests "*Reconcil*"
```
