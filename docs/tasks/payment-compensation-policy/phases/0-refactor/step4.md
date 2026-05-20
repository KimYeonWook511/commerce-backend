# Step 5: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 갱신이 필요한 부분을 파악하라:

- `/docs/tasks/payment-compensation-policy/prd.md`
- `/docs/tasks/payment-compensation-policy/adr.md`
- `/docs/ADR.md` — 현재 ADR-012, ADR-013 내용 확인
- `/docs/exception-strategy.md` — 보상 catch 섹션 적용 예 확인
- `/docs/architecture.md` — 결제 승인 흐름 섹션 확인

## 작업

### 1. docs/ADR.md — ADR-014 신설

ADR-014를 추가한다:

```
### ADR-014: 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다
- **결정**: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`는 PG cancel 진행 전 `PaymentApprovalService.isCompensationRequired(merchantPayKey)`를 호출해 Payment가 이미 존재하면 cancel을 skip한다.
- **배경**: 기존 구조는 `PaymentAttempt.status`로 보상 진행 여부를 판단했으나 attempt에 lock이 없어 race window에서 SUCCEEDED attempt에 cancel이 호출되는 결함(#114)이 있었다.
- **이유**: Payment는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique 제약이 있고 `completeApprovedPayment`가 Order FOR UPDATE 안에서 저장하므로 race-safe하다. DDD 관점에서 Payment Aggregate의 불변식을 cross-Aggregate 협력으로 활용한다. 미래 Payment 도메인 분리 시 `isCompensationRequired`는 외부 API로 자연 승격 가능하다.
- **트레이드오프**: Payment 조회 1회 추가되나 인덱스 조회라 성능 영향 미미하다.
```

### 2. docs/ADR.md — ADR-012 후속 노트 추가

ADR-012 트레이드오프 섹션 또는 끝에 후속 노트를 추가한다:

```
**후속 (ADR-014, payment-compensation-policy task)**: ADR-D의 임시 처방(try-catch 보호 한 곳)이 ADR-014(Payment 존재 체크)로 대체됐다. race window에서 mark가 throw되는 경로 자체가 줄어들어 ADR-012의 엄격한 검증 원칙은 그대로 유지된다. #117(멱등 자기 전이 허용) close.
```

### 3. docs/exception-strategy.md — 보상 catch 2차 예외 처리 적용 예 갱신

"적용 예" 섹션에 `isCompensationRequired` 패턴을 추가한다:

```
- `PaymentApprovalService.isCompensationRequired`는 보상 진행 여부를 Payment Aggregate 소유자가 결정하도록 캡슐화해 NaverPay adapter가 Payment 저장소에 직접 접근하지 않도록 한다.
```

### 4. docs/architecture.md — 결제 승인 흐름 갱신

결제 승인 흐름의 보상 단계에 Payment 체크 패턴 반영:

```
# 결제 승인 (네이버페이)
NaverPayController → NaverPayApprovalService
  → NaverPayGateway (PG 호출, 응답 코드 매핑)
  → PaymentApprovalService (결제 완료 반영, 보상 가능 여부 판단)
  → PaymentAttemptService (시도 이력 기록)
```

### 5. docs/ADR.md — PaymentAttempt mark 호출 정책 명시

ADR-014에 아래 항목을 추가한다:

```
- **PaymentAttempt Aggregate 캡슐화**: `PaymentAttempt.mark*` 메서드는 `PaymentAttemptService` 외부에서 직접 호출하지 않는다. 정책 강제는 코드가 아닌 ADR과 JavaDoc으로만 명시하며, ArchUnit 도입은 별도 후속 작업으로 분리한다.
```

## 수정 가능 경로

- `docs/ADR.md`
- `docs/exception-strategy.md`
- `docs/architecture.md`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다 (문서 변경이므로 회귀 없음 확인).
2. 아래를 확인한다:
   - ADR-014가 기존 ADR 목록에 자연스럽게 이어지는지
   - ADR-012 후속 노트가 ADR-D 내용과 연결되는지
   - architecture.md 흐름이 실제 코드와 일치하는지
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/tasks/payment-attempt-state-transition-policy/` 하위 문서를 수정하지 마라. 이유: 완료된 task의 역사 기록이므로 사후 소급 수정하지 않는다.
- `docs/ddd/` 하위 문서를 수정하지 마라. 이유: DDD 마이그레이션 회고 전용이며 사후 수정 금지 정책이 있다.
- `commerce-workspace/docs/` 하위 문서를 수정하지 마라. 이유: workspace 공유 문서는 backend 세션에서 다루지 않는다.
- 기존 ADR 내용을 삭제하거나 수정하지 마라. 이유: 각 ADR은 당시 맥락과 트레이드오프를 담은 결정 기록이다. 새 정보는 후속 노트로 추가한다.
