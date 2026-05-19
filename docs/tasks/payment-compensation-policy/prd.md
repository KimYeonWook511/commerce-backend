# 태스크 PRD

## 태스크명

- `payment-compensation-policy`

## 배경

`NaverPayApprovalService.failApproveAndCancelApprovedPayment`는 보상 진행 여부를 `PaymentAttempt.status`를 기준으로 판단하지만, attempt에는 row lock이 없어 race-safe하지 않다. ADR-012(PR #112)가 mark 메서드에 선조건 검증을 추가한 뒤 임시 처방(ADR-D: try-catch 보호 한 곳)으로 race window를 처리했으나, 근본 문제가 남아 있다.

후속 작업으로 식별된 4개 이슈(#114, #115, #116, #117)는 같은 뿌리에서 나온 증상이다:
- **#114**: race window에서 attempt가 SUCCEEDED 상태임에도 PG cancel이 호출됨
- **#115**: `PaymentApprovalServiceConcurrencyTest` flaky
- **#116**: `completeVerifiedApproval` catch 분기 5개가 같은 보상을 다른 의미로 호출
- **#117**: ADR-012 절충안 — mark 멱등 자기 전이 허용 검토

## 목표

- 보상 진행 여부 판단을 `PaymentAttempt.status`(race-unsafe)에서 `Payment` 엔티티 존재 여부(race-safe)로 변경한다.
- 보상 시나리오별 의미를 명확히 분리하여 `completeVerifiedApproval`의 catch 분기 가독성을 높인다.
- 4개 후속 이슈(#114, #115, #116, #117)를 같은 뿌리에서 해소한다.
- DDD 관점에서 `Payment`와 `PaymentAttempt` 두 Aggregate의 독립적 불변식을 존중하는 cross-Aggregate 협력 패턴을 확립한다.

## 범위

### 포함

- `PaymentApprovalService`에 `isCompensationRequired(String merchantPayKey): boolean` 신설
- `NaverPayApprovalService.failApproveAndCancelApprovedPayment`에서 cancel 진행 전 `isCompensationRequired` 체크
- `completeVerifiedApproval`의 catch switch를 의미별 보상 메서드(`compensate*`)로 분리
- race 시나리오 및 concurrency 테스트 보강
- 루트 docs 동기화: ADR-014 신설, ADR-012 후속 노트, `exception-strategy.md`, `architecture.md` 갱신
- 회고 작성

### 제외

- DB 스키마 변경 없음
- 외부 API 응답 형식 변경 없음
- ArchUnit 도입 (mark 가시성 코드 강제) — 후속 작업
- Payment 도메인 서비스 분리/외부 API화 — 미래 작업
- `docs/tasks/payment-attempt-state-transition-policy/` 문서 수정 — 역사 기록, 사후 변경 금지

## 주요 시나리오

### 정상: 보상 cancel이 필요한 경우

```
PG 승인 성공 후 validateApprovedAmountOrThrow 실패
→ completeVerifiedApproval catch(PaymentException)
→ compensateAmountMismatch(attempt, responseTotalAmount)
→ isCompensationRequired(merchantPayKey) == true (Payment 미존재)
→ PG cancel 진행
```

### race: 정상 승인이 먼저 완료된 경우

```
Thread A: completeApprovedPayment → Order lock → succeedApproveAttempt → Payment 저장 완료
Thread B: completeVerifiedApproval catch 진입 → compensateUnexpected
→ isCompensationRequired(merchantPayKey) == false (Payment 이미 존재)
→ cancel skip (log.warn)
→ 외부 정합성 보존, #114 해결
```

### MERCHANT_KEY_MISMATCH: cancel 없이 실패 처리

```
validateApprovedMerchantPayKeyOrThrow 실패
→ catch(PaymentException, PAYMENT_MERCHANT_KEY_MISMATCH)
→ compensateMerchantKeyMismatch(attempt)
→ failApprove만, cancel 없음 (우리 시스템 키 오류이므로 PG 결제 자체가 없음)
```

## 요구사항

1. `PaymentApprovalService.isCompensationRequired(merchantPayKey)`: Payment 미존재 시 true 반환
2. `failApproveAndCancelApprovedPayment`: `isCompensationRequired`가 false면 warn 로그 후 return
3. `completeVerifiedApproval`의 catch 분기는 각 시나리오의 의미를 메서드 이름으로 드러냄
4. 기존 정상 흐름에서 cancel skip이 발생하지 않아야 함 (Payment 미존재 시 cancel은 기존처럼 진행)
5. 기존 `failApproveAttemptIfRequested` 호출은 유지 (ADR-013 패턴 보존)

## 제약사항

- `PaymentAttempt.mark*` 메서드 가시성 변경 없음 (Java 패키지 구조 제약). 정책은 ADR-014에 명문화.
- Payment Aggregate 직접 접근은 `PaymentApprovalService`(owner)를 통해서만 허용
- `isCompensationRequired`는 미래 Payment 도메인 분리 시 외부 API 메서드로 자연 승격 가능한 시그니처로 설계
