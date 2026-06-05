# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 현재 문서 상태를 파악하라:

- `docs/tasks/payment-compensation-to-domain/prd.md`
- `docs/tasks/payment-compensation-to-domain/adr.md`
- `docs/adr.md`
- `docs/architecture.md`
- `docs/exception-strategy.md`
- `docs/testing-conventions.md`

## 작업

이 step은 코드 변경 없이 루트 docs만 동기화한다.

### `docs/adr.md` 수정

**ADR-015 신설** (ADR-014 다음에 추가):

```
### ADR-015: 보상 정책은 payment.application 책임이고, PG 어댑터는 cancel 콜백만 제공한다
- **결정**: `NaverPayApprovalService`에 있던 보상 dispatcher 4개와 공통 골격을 `PaymentApprovalCompensationService`(payment.application)로 이동한다. PG cancel 호출은 `PgCanceller` @FunctionalInterface 콜백으로 위임하고, PG 응답은 `CancelOutcome` record로 변환해 payment.application이 `NaverPayCancelResult`를 직접 import하지 않도록 한다.
- **배경**: 보상 정책(어떤 실패 → cancel 필요/불필요, cancel reason, cancel amount)은 PG-agnostic 결제 도메인 책임이다. PG-specific한 부분은 cancel API 호출과 NaverPayCancelResult 응답 해석뿐이다. NaverPayApprovalService가 보상 정책을 내장하면 레이어 의존이 역전되고 PG 변경 시 정책 코드도 함께 영향받는다.
- **이유**: `PgCanceller` 좁은 콜백은 PaymentGateway port 완전 inversion(PG 둘 이상 추가 시)보다 지금 필요한 최소 구조만 도입한다. NaverPayApprovalService가 메서드 참조(`this::pgCancel`)로 구현하므로 인터페이스 추가 없이 의존 역전이 성립한다.
- **트랜잭션 정책**: `PaymentApprovalCompensationService`에 클래스 레벨 `@Transactional` 없음. `isCompensationRequired`의 `REQUIRES_NEW` 격리(ADR-014)를 보존하기 위해 각 단계가 자기 트랜잭션을 가진다.
- **트레이드오프**: PG가 둘 이상 추가될 때 `PgCanceller` 주입 위치를 재설계해야 한다. 이때 PaymentGateway port 완전 inversion으로 자연 승격 가능하다.
```

**ADR-014 후속 노트 추가** (ADR-014 결정 마지막 줄 아래):

```
- **후속 (ADR-015, payment-compensation-to-domain task)**: 보상 owner가 `NaverPayApprovalService.failApproveAndCancelApprovedPayment`에서 payment.application의 `PaymentApprovalCompensationService.runPgCancel`로 이동했다. `isCompensationRequired` 호출자가 바뀌었을 뿐 정책 자체(Payment 존재 체크 → cancel skip)는 동일하게 유지된다.
```

### `docs/architecture.md` 수정

**도메인별 주요 서비스 테이블** (line 83-84 인근):

payment 행에 `PaymentApprovalCompensationService` 추가:

```
| payment | `PaymentReadyService`, `PaymentApprovalService`, `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`, `PaymentApprovalCompensationService` |
```

**결제 승인 데이터 흐름** (line 107-110 인근):

```
# 결제 승인 (네이버페이)
NaverPayController → NaverPayApprovalService
  → NaverPayGateway (PG 호출, 응답 코드 매핑)
  → PaymentApprovalService (결제 완료 반영, 보상 가능 여부 판단)
  → PaymentApprovalAttemptService (승인 시도 이력 기록)
  → PaymentCancellationAttemptService (취소 시도 이력 기록, 보상 흐름)
  → PaymentApprovalCompensationService (보상 dispatcher — catch 분기 시, this::pgCancel 콜백 주입)
```

### `docs/exception-strategy.md` 수정

**line 78-80 "적용 예" 섹션 갱신**:

`failApproveAndCancelApprovedPayment` → `PaymentApprovalCompensationService.runPgCancel`로 호출처 명칭 갱신:

```
- `PaymentApprovalAttemptService.failIfRequested`는 보상 흐름에서 "현재 상태가 REQUESTED면 실패 처리, 아니면 skip" 의도를 캡슐화해 호출처(`PaymentApprovalCompensationService.runPgCancel`)가 try-catch 없이 평탄하게 보상을 진행하도록 한다.
```

**"보상 catch 2차 예외 처리" 섹션** 끝에 PgCanceller 관련 내용 추가:

```
### PG cancel 콜백 (PgCanceller)

`PgCanceller.cancel(cancelAttempt, cancelReason) → CancelOutcome` 시그니처. PG-specific 응답(`NaverPayCancelResult.Status` 등)을 도메인 `CancelOutcome.Status`(SUCCESS/PROCESSING/FAILED)로 변환한 뒤 cancel attempt mark를 결정한다. `ALREADY_CANCELED`는 `SUCCESS`와 동일하게 매핑한다. `payment.application`이 `NaverPayCancelResult`를 직접 import하지 않아 레이어 의존 방향이 보존된다.
```

### `docs/testing-conventions.md` 수정

Application Layer 섹션에 `PgCanceller` Mock 패턴 예제 추가:

```
#### PgCanceller functional interface Mock 패턴

@FunctionalInterface를 Mockito로 Mock할 때는 일반 인터페이스와 동일하게 처리한다.

@Mock PgCanceller pgCanceller;

// stub 예시
given(pgCanceller.cancel(any(), any())).willReturn(CancelOutcome.success());
given(pgCanceller.cancel(any(), eq("취소 이유"))).willReturn(CancelOutcome.failed(failCode, "실패 상세"));

// 호출 여부 검증
then(pgCanceller).should().cancel(eq(cancelAttempt), eq("취소 이유"));
then(pgCanceller).should(never()).cancel(any(), any());
```

### 영향 없음 확인 (수정 불필요)

- `docs/prd.md` — 기능 범위 변동 없음
- `docs/api-spec.md` — 외부 API 변동 없음
- `docs/db-schema.md` — DB 스키마 변동 없음
- `docs/branch-conventions.md`, `docs/commit-conventions.md`, `docs/pr-conventions.md` — 규칙 문서
- `docs/claude-harness.md`, `docs/hooks/`, `docs/claude/`, `docs/skills/` — 개발 환경 문서
- `docs/ddd/` 하위 회고 — 역사 기록, 사후 수정 금지
- `docs/tasks/payment-compensation-policy/` — 역사 기록, 사후 수정 금지
- `docs/TEMP-TODO.md`/`docs/TODO.md` — 큰 로드맵 문서, 본 task로 close되는 항목 없음

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행해 회귀 없음을 확인한다 (문서 전용 step이므로 빌드만 통과하면 된다).
2. 아래를 확인한다:
   - ADR-015가 `docs/adr.md`에 추가됐는가?
   - ADR-014 후속 노트가 추가됐는가?
   - `docs/architecture.md`의 서비스 테이블과 데이터 흐름이 갱신됐는가?
   - `docs/exception-strategy.md`의 `failIfRequested` 호출처 명칭이 갱신됐는가?
   - `docs/testing-conventions.md`에 PgCanceller Mock 패턴이 추가됐는가?

## 금지사항

- `docs/ddd/` 하위 회고 문서를 수정하지 마라. 이유: 역사 기록이라 사후 소급 수정하지 않는다.
- `docs/tasks/payment-compensation-policy/` 하위 문서를 수정하지 마라. 이유: 동일한 이유.
- `commerce-workspace/docs/` 하위 문서를 수정하지 마라. 이유: backend 세션은 workspace 공유 문서를 수정하지 않는다. Frontend 세션 책임.
