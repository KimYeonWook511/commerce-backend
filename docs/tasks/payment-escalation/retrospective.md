# 회고록: payment-escalation

## 1. 작업 요약

- 자동 대사가 6시간 안에 결론을 못 낸 미확정 APPROVE 결제(UNKNOWN/REQUESTED)를 운영자에게 통지하고 종착 표시(`escalatedAt`)해, 그동안 가시성 없이 묻히던 escalation 건의 운영 가시성을 확보했다.
- 대사 중 주문이 없는(`order == null`) 정합성 오류 건도 `FAILED` 종착 이후 운영자에게 통지하도록 보강했다.
- escalation 종착을 새 status 없이 `escalatedAt` 직교 필드로 표현하고, 중복 통지는 조건부 UPDATE(CAS)의 영향 행 수로 막았다.

## 2. 결정한 정책 (ADR-049)

- escalation 종착·통지를 새 status(`ESCALATED`)가 아니라 `Payment.escalatedAt` 직교 필드로 표현한다. status는 사실(UNKNOWN/REQUESTED)을 유지하고 "운영자에게 위임됐나"는 별 축으로 분리(ADR-039/044 연장).
- 중복 통지는 조건부 UPDATE(`WHERE escalated_at IS NULL AND status IN (UNKNOWN,REQUESTED)`)의 영향 행 수로 보장한다. 영향 행 1인 호출만 통지한다 — `uk_payment_approved_order_key` unique가 이중 SUCCEEDED를 막는 것과 같은 DB 레벨 멱등.
- escalation 스캔은 기존 자동 대사 스캔(1분~6시간)과 별도 쿼리로 두고, `reconcile()` 말미에 통합 처리(별도 스케줄러 미신설).
- 범위: APPROVE만. CANCEL escalation은 CANCEL 대사 자체가 미구현이라 별도 이슈로 분리.

## 3. 주요 발견 및 논의

### "race 방어" 표현과 메모리 가드의 불일치 → CAS로 교정

File Drafting 단계에서 멱등을 "2겹 방어: 스캔 필터(1차) + 도메인 `escalate()` 가드(2차, race 방어)"로 설계했으나, 도메인 가드는 로드한 객체의 `escalatedAt == null`을 검사하는 **메모리 가드**라 동시 트랜잭션 race를 막지 못한다(`Payment`에 `@Version` 없음). 구현 후 사용자의 "A(조건부 UPDATE)로 계획한 것 아니었냐"는 질문에서 이 불일치가 드러나, escalation 종착을 **조건부 UPDATE(CAS) + 영향 행 수 판단**으로 교정했다.
- 교훈: "race 방어"를 주장하려면 실제 race를 막는 DB 레벨 메커니즘(CAS/낙관 락)이 있어야 한다. 메모리 상태 가드는 단일 트랜잭션 재진입만 막는다. 동시성 용어를 문서에 쓸 때 실제 메커니즘과 대조하는 self-check가 필요했다.

### 빈틈 사전 점검으로 step 자기완결성 확보

File Drafting 전에 6개 빈틈(escalation 후보 조회 방식 / 커밋 후 통지 순서 / age 기준(UNKNOWN `respondedAt` vs REQUESTED `createdAt`) / APPROVE 한정 / 통합·동시성 테스트 / 인덱스 보류)을 사전 해소해 step 문서에 박았다. 구현 agent가 임의 판단 없이 진행했다.

### PR 리뷰: Gemini reject, codex → #243

- **Gemini**(`escalateIfPending`에 `REQUIRES_NEW`): 호출처(`processEscalations`)가 무트랜잭션이라 `@Transactional`(REQUIRED)로도 이미 독립 커밋되어 "커밋 후 통지"가 보장된다. `REQUIRES_NEW`는 repository에 전파 정책을 박는 레이어 오염 + 상위 트랜잭션은 현재 없는 가정(YAGNI)이라 reject.
- **codex**(order-null 통지 중복): 같은 행 동시 `fail()`이 둘 다 성공해 중복 통지될 수 있다는 지적. lost update의 증상이라 표면 패치 대신 근본(#243)으로 위임.

### Payment `@Version` 누락 발견 → #243

codex 지적을 추적하다, 프로젝트가 lost update를 `@Version`으로 Order/PaymentReservation/CartItem/Stock에 적용해 왔는데 **`Payment`에만 빠진** 것을 발견했다. Payment는 경로별 개별 메커니즘(reservation `@Version` / order 비관 락 / unique / CAS)으로 생성·승인·취소·escalation을 막지만, 종착 전이(`fail`/`markUnknown`)의 행 단위 lost update만 빈 곳으로 남아 있다. 발현은 비현실적(succeed/fail 갈림 + 단일 인스턴스)이라 #242 머지를 막지 않고 #243으로 분리했다.
- 교훈: "동시성을 고려했나"가 아니라 "어떤 형태로 고려했나"를 봐야 한다. 중복·이중·진입은 막았어도 행 단위 lost update가 한 엔티티에만 빠질 수 있다. 같은 PR 안에서 escalation엔 멱등을 넣으면서 order-null/종착 전이엔 안 넣은 비일관을 처음부터 점검했어야 한다.

## 4. 변경 범위 정리

- 스키마: `V8__add_payment_escalated_at.sql` — `tbl_payment.escalated_at` 추가
- 도메인: `Payment.escalatedAt` 필드
- repository: escalation 후보 스캔 쿼리 + 조건부 UPDATE(`escalateIfPending`)
- application: `PaymentReconciliationService.reconcile()`에 `processEscalations()` 통합(영향 행 1일 때만 통지), `handleOrderNotCompletable`의 order-null 분기에 통지 추가
- 테스트: `EscalationScanQueryIntegrationTest`(통합), `PaymentEscalationConcurrencyTest`(동시성), `PaymentReconciliationServiceTest`(order-null 단위)
- 루트 문서: ADR-049, `db-schema.md` `escalated_at`

## 5. 미결 과제

- **#243**: `Payment`에 `@Version` 낙관 락 도입 — 종착 전이의 lost update 방어, 다른 엔티티와 일관성 복구.
- **CANCEL escalation**: CANCEL 대사 자체가 미구현(별도 이슈에서 대사 + escalation 묶음).
- **실제 알림 채널 adapter**: 현재 `NotificationPort` + 로그 어댑터만. 디스코드 등 실채널은 ADR-045 후속.

## 6. 회고

### 잘된 점

- 빈틈 6개 사전 점검으로 구현 단계에서 막힘이 없었다.
- 사용자의 질문이 "race 방어" 설계 불일치를 머지 전에 잡았고, codex 지적을 표면 패치가 아니라 근본(#243 `@Version`)으로 승화시켰다.
- PR 리뷰(Gemini)와 외부 리뷰(codex)를 축이 다른 문제로 분리해, 무관한 지적(Gemini)을 근본 이슈(#243)에 잘못 엮지 않았다.

### 개선할 점

- File Drafting에서 동시성 용어("race 방어")를 실제 메커니즘(메모리 가드)과 대조하지 않고 적었다. 동시성 설계 문장은 작성 시점에 "이게 정말 동시 race를 막나"를 self-check해야 한다.
- 같은 PR 안에서 escalation엔 CAS 멱등을 넣으면서 order-null 통지엔 안 넣은 비일관을, 리뷰가 짚기 전에 스스로 점검했어야 한다.
