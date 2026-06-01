# 태스크 ADR

## ADR-T1: PgCanceller 콜백 방식으로 보상 정책을 payment.application으로 이동

### 배경

`NaverPayApprovalService`는 보상 정책(dispatcher 4개 + 골격)과 PG cancel 호출을 함께 담고 있어 ~330줄이 넘는다.
보상 정책은 PG-agnostic한 결제 도메인 책임(어떤 실패 → cancel 필요 여부, cancel reason, cancel amount)이고, PG-specific은 cancel API 호출과 응답 해석뿐이다.

세 가지 설계 선택지가 있었다:

1. **PgCanceller 좁은 콜백** (채택): `@FunctionalInterface PgCanceller` + `CancelOutcome` record 도입. payment.application이 NaverPayCancelResult를 직접 import하지 않는 최소 구조.
2. **PaymentGateway port 완전 inversion**: PG-agnostic approve/cancel 통합 port. PG가 둘 이상이 될 때 자연스러운 방향이나, 지금은 over-engineering.
3. **Strategy 패턴**: PG별 보상 전략 객체. PG가 하나인 현 시점에 premature.

### 결정 내용

옵션 1 채택: `PgCanceller` @FunctionalInterface + `CancelOutcome` record.

### 근거

- 보상 정책 코드가 payment.application으로 이동해 레이어 의존이 올바른 방향(application → port → infrastructure)을 회복한다.
- `PgCanceller`는 NaverPay가 메서드 참조(`this::pgCancel`)로 구현하므로, 지금 가장 좁은 경계를 만들면서 PaymentGateway port 추가 시 자연 승격 가능하다.
- `CancelOutcome` record로 payment.application이 `NaverPayCancelResult`를 직접 import하지 않아 도메인 오염 없다.

### 결과

- `PaymentApprovalCompensationService`가 보상 정책을 소유. NaverPayApprovalService는 main flow + `pgCancel` 콜백만 남는다.
- `NaverPayApprovalService` 라인 수가 ~330줄에서 ~150줄 이하로 감소 예상.
- trade-off: PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치(NaverPayApprovalService 필드 → 공통 팩토리)를 재설계해야 한다.
- `compensateMerchantKeyMismatch`만 `PgCanceller`를 파라미터로 받지 않는다. PG 결제 자체가 발생하지 않은 케이스(우리 시스템 키 오류)이므로 cancel 호출이 불필요하다.
- `compensateUnexpected`의 `failCode` 파라미터는 현재 호출처 3곳 모두 `APPROVE_PROCESS_FAILED`를 전달한다. 후속에 다른 failCode가 필요한 케이스가 생길 때를 대비해 인자로 열어뒀다.

---

## ADR-T2: PaymentApprovalCompensationService에 클래스 레벨 @Transactional 금지

### 배경

`runPgCancel` 의 단계들(`failIfRequested`, `isCompensationRequired`, `getOrCreate`, `succeed`/`fail`)은 각자 자기 `@Transactional` 어노테이션을 가져 독립 commit 된다.

### 결정 내용

`PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional`을 붙이지 않는다. 각 단계의 메서드가 자기 트랜잭션 어노테이션을 그대로 사용한다.

### 근거

보상 흐름은 PG 응답과의 정합성을 단계별로 맞추는 작업이다. 단일 트랜잭션으로 묶이면 한 단계 실패가 이전 단계의 진행까지 롤백시켜 부분 진행 보존이 불가능해진다. 단계별 독립 commit 이 유지되어야 일부 실패에도 진행한 부분(예: `failIfRequested` 로 상태 전이된 approve attempt, `getOrCreate` 로 생성된 cancel attempt)이 보존된다.

또한 `isCompensationRequired` 는 외부 트랜잭션 없이 호출되는 위치이므로 자체 `@Transactional(readOnly = true)` 만으로 항상 커밋된 DB 상태를 기준으로 race-safe 판단이 가능하다 (ADR-014).

### 결과

- `runPgCancel`을 `@Transactional` 없이 호출. 각 단계가 자기 트랜잭션으로 동작.
- 부분 진행 보존 정책 유지.
- race-safe 보상 판단 (ADR-014) 유지.
