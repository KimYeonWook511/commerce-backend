# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `payment-escalation`

## 배경

- PR #237(`unknown-reconciliation`)에서 UNKNOWN 결제 자동 대사를 도입하며, 6시간 초과 건은 무한 재시도 방지를 위해 대사 스캔 윈도우(`1분~6시간`) 상한으로 제외했다(ADR-047/L8).
- 그 결과 6시간 초과 UNKNOWN/REQUESTED APPROVE 결제가 `status=UNKNOWN`으로 영구히 남아 자동 스캔에서만 빠질 뿐 **운영 가시성이 없다**. 운영자가 능동 조회해야만 인지 가능하다. 현재 정책 분류값 `PaymentPostProcessTarget.MANUAL_REVIEW`로 가는 분기는 스캔이 6시간 초과를 가져오지 않아 사실상 dead다.
- 대사 중 주문이 없는(`order == null`) 정합성 오류 건도 `fail` 종착 + `log.error`만 하고 운영자 통지가 없다.
- ADR-044가 "escalation의 운영 가시성(통지·종착)과 '결론 났나/과금됐나' 축 분리는 후속 #238에서 재검토(ADR-039 재검토 trigger)"라고 본 Task를 명시적으로 지목한다.

## 목표

- 자동 대사로 결론을 못 낸 건을 **운영자에게 통지하고 종착 표시**해, "자동 처리 영역"과 "수동(운영) 영역"의 경계를 시스템에 명시한다.
- 통지는 한 번만 발생(멱등)하고, 종착 표시된 건은 자동 대사에서 영구 제외된다.

## 범위

- 포함 범위
  - APPROVE escalation: 6시간 초과 UNKNOWN/REQUESTED 건을 발견해 운영자 통지 + 종착 표시(`escalatedAt` 기록).
  - `order == null` 정합성 오류 건의 운영자 통지(기존 `fail` 종착은 유지).
  - escalation 종착 표현 방식 결정을 ADR로 기록(`escalatedAt` 직교 필드).
- 제외 범위
  - CANCEL 타입 escalation. 이유: CANCEL 결제의 대사 자체가 현재 미구현(`reconcile()`이 `CANCEL_RECONCILE`을 SKIPPED 처리)이라, escalation만 CANCEL로 넓히면 반쪽이 된다. CANCEL은 "대사 + escalation"을 한 묶음으로 별도 이슈에서 다룬다.
  - 실제 알림 채널(디스코드 웹훅 등) 연동. `NotificationPort` 추상화 + 로그 어댑터를 재사용하고, 채널은 후속 이슈(ADR-L6/ADR-046)에서 어댑터만 추가한다.
  - 별도 escalation 테이블. 이유: escalation이 "한 번 통지 + 종착"뿐이라 row 생성·조인·동기화 비용이 이득을 넘는다(YAGNI).
  - `PaymentStatus` 모델 변경. status는 `REQUESTED/SUCCEEDED/FAILED/UNKNOWN` 4개를 유지한다(ADR-044).

## 주요 시나리오

- **escalation 통지·종착**: 6시간 초과 UNKNOWN/REQUESTED APPROVE 결제 → escalation 스캔이 발견 → 조건부 UPDATE로 `escalatedAt` 기록(영향 행 1, 커밋) → 영향 행 1일 때만 운영자 통지(best-effort) → 다음 주기 스캔에서 `escalatedAt IS NULL` 필터로 제외(재통지 없음).
- **정합성 오류 통지**: 대사 승인 확정이 거부됐는데 `order == null` → `fail` 종착 + 운영자 통지.

## 요구사항

- `tbl_payment`에 `escalated_at` nullable 컬럼을 추가한다(직교 필드, status와 무관).
- escalation 종착은 조건부 UPDATE로 원자적으로 기록한다: `UPDATE ... SET escalated_at=:now WHERE id=:id AND escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`. 영향 행 수가 1일 때만 통지 주체로 본다. status는 바꾸지 않는다.
- escalation 스캔 쿼리(기존 `findStale...`와 별도): `type=APPROVE AND escalatedAt IS NULL AND ((status=UNKNOWN AND respondedAt < 6시간前) OR (status=REQUESTED AND createdAt < 6시간前))`.
- `reconcile()` 흐름에 escalation 처리를 통합한다(별도 스케줄러를 두지 않는다).
- escalation·order==null 통지는 기존 `NotificationPort.notifyManualReviewRequired`를 재사용한다.

## 제약사항

- `PaymentStatus`는 4개를 유지한다(ADR-044 준수, 새 status 미도입).
- `tbl_payment` append-only 원칙: 행 삭제 없이 상태 전이(UPDATE)만. `escalated_at` set은 `escalate()` 도메인 메서드 안에 캡슐화한다.
- 통지는 commit 이후 best-effort이며, 전송 실패가 트랜잭션을 막지 않는다(ADR-L6). 진실 원천은 `escalatedAt`/`status`이고 통지는 부가 push다.
- escalation 멱등은 조건부 UPDATE(`WHERE escalated_at IS NULL`)의 DB 레벨 원자성으로 보장한다. 스캔 쿼리의 `escalatedAt IS NULL` 필터는 1차 효율 필터이고, 동시 race에서도 조건부 UPDATE의 영향 행 수(정확히 1행)가 통지 주체를 결정한다. 메모리 객체 가드에 의존하지 않는다(`Payment`에 `@Version` 없음).
