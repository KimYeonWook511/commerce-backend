# 태스크 ADR

---

## ADR-1: 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다

### 배경

기존 `failApproveAndCancelApprovedPayment`는 cancel 진행 여부를 `PaymentAttempt.status`로 판단한다. `failApproveAttemptIfRequested`가 REQUESTED 상태가 아니면 mark를 skip하는데, cancel 진행 결정은 이 skip과 무관하게 계속된다. attempt에 row lock이 없어 race window에서 Thread A가 SUCCEEDED로 mark한 뒤 Thread B가 같은 attempt에 대해 cancel을 진행하는 경로가 열려 있다.

옵션 비교:
- **옵션 A (채택)**: Payment 엔티티 존재 여부로 판단. `isCompensationRequired(merchantPayKey)` — Payment unique 제약이 DB 레벨에서 race-safe를 보장한다.
- **옵션 B**: PaymentAttempt에 낙관적 락(@Version) 추가. DB 스키마 변경(운영 마이그레이션) + attempt 수준 락 범위 문제.
- **옵션 C**: PaymentAttempt에 FOR UPDATE 적용. attempt 조회 lock 추가 + Order FOR UPDATE와의 순서 조율 필요.

### 결정 내용

cancel 진행 결정은 `PaymentApprovalService.isCompensationRequired(merchantPayKey)`로 위임한다. 내부적으로 `paymentRepository.findByMerchantPayKey(merchantPayKey).isEmpty()`로 판단한다.

### 근거

- `Payment`는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique. `completeApprovedPayment`가 Order FOR UPDATE 안에서 Payment를 생성하므로 Payment 존재는 DB 레벨에서 race-safe하게 확인 가능하다.
- DB 스키마 변경 없음. 운영 마이그레이션 불필요.
- DDD 관점에서 두 별도 Aggregate(`Payment`, `PaymentAttempt`)의 독립 불변식을 cross-Aggregate 협력으로 존중하는 패턴이다.
- 미래 Payment 도메인 분리 시 `isCompensationRequired`를 Payment 서비스 외부 API로 자연 승격 가능하다. `PaymentAttempt` 기반 판단은 분리 후에도 Payment DB 안에서 유효하지 않다.

### 결과

- #114 (race window cancel skip 결함) 근본 해결
- attempt lock 추가 불필요
- `failApproveAttemptIfRequested`는 그대로 유지 (ADR-013 패턴 보존)

---

## ADR-2: PaymentApprovalService가 isCompensationRequired 소유권을 가진다

### 배경

보상 가능 여부 판단을 어느 계층에서 내릴 것인가:
- **옵션 A (채택)**: `PaymentApprovalService`가 `isCompensationRequired` 메서드 노출. NaverPay adapter는 이 메서드만 호출.
- **옵션 B**: NaverPay adapter가 직접 `paymentRepository.findByMerchantPayKey`를 호출.

### 결정 내용

`PaymentApprovalService`에 `isCompensationRequired(String merchantPayKey): boolean`을 추가하고, `NaverPayApprovalService`는 이 메서드만 호출한다.

### 근거

- `NaverPayApprovalService`가 `paymentRepository`를 직접 참조하면 Payment Aggregate 소유권이 adapter로 새어나온다.
- `PaymentApprovalService`가 이미 Payment Aggregate의 application service. 보상 판단 책임이 자연스럽게 귀속된다.
- 미래 Payment 서비스 분리 시 `isCompensationRequired`가 외부 API로 승격되어도 NaverPay adapter의 코드는 변경 없이 유지된다.

### 결과

- Payment Aggregate 접근 경로 단일화
- `NaverPayApprovalService` → `PaymentApprovalService` 기존 의존 라인에 메서드 하나 추가만으로 해결

---

## ADR-3: completeVerifiedApproval catch 분기를 의미별 보상 메서드로 분리한다

### 배경

기존 `completeVerifiedApproval`는 `catch(PaymentException)` + `catch(CustomException)` + `catch(Exception)` 세 블록에서 `failApproveAndCancelApprovedPayment`를 반복 호출한다. 같은 보상 메서드가 다른 의미(금액 불일치 취소, 중복 결제 취소, 키 불일치 실패 처리, 예상치 못한 예외 취소)로 쓰인다.

옵션 비교:
- **옵션 A (채택)**: 의미별 메서드 분리 (`compensate*`). catch는 호출만.
- **옵션 B**: Strategy 패턴으로 보상 정책 추상화. PG가 NaverPay 하나뿐인 현 시점에 over-design.

### 결정 내용

```
compensateMerchantKeyMismatch(attempt)         — failApprove만, cancel 없음
compensateAmountMismatch(attempt, amount)       — isCompensationRequired 체크 후 cancel
compensateDuplicatePayment(attempt, ex)         — isCompensationRequired 체크 후 cancel
compensateUnexpected(attempt, ex, code, msg)    — isCompensationRequired 체크 후 cancel
```

### 근거

- 메서드 이름으로 시나리오 의미가 드러난다. 코드 리뷰와 디버깅에서 "어떤 실패 시 cancel이 일어나는가"가 명확해진다.
- `compensateMerchantKeyMismatch`에서 cancel을 하지 않는 이유가 코드 구조로 표현된다 (cancel 없이 failApprove만).
- 각 보상 메서드를 독립적으로 테스트 가능하다.

### 결과

- #116 (catch 분기 정리) 해결
- 가독성 향상, 테스트 커버리지 향상

---

## ADR-4: PaymentAttempt mark 메서드 호출 정책 명문화 (ADR-012 후속)

### 배경

ADR-012(payment-attempt-state-transition-policy task)에서 `PaymentAttempt.mark*` 메서드 4개에 선조건 검증이 추가됐다. 외부 직접 호출은 현재 없지만 Java `public` 접근자 때문에 컴파일러 수준에서 강제할 수 없다.

옵션 비교:
- **옵션 A (채택)**: ADR 정책 명문화 + 메서드 JavaDoc 호출 정책 명시. 코드 변경 없음.
- **옵션 B**: ArchUnit 테스트로 CI에서 차단. 도입 비용 발생. 후속 작업.
- **옵션 C**: 패키지 구조 변경 (PaymentAttempt와 PaymentAttemptService를 같은 패키지로). 대규모 변경.

### 결정 내용

`PaymentAttempt.mark*` 메서드는 `PaymentAttemptService` 외부에서 직접 호출하지 않는다. 이 정책을 ADR-014(루트 docs)와 각 메서드 JavaDoc에 명시한다. ArchUnit 도입은 후속 작업으로 분리한다.

### 근거

- `PaymentAttemptService`가 이미 유일한 호출처이므로 현재 상태에서 위반 경로가 없다.
- ADR 정책 명문화로 새 기여자가 mark를 직접 호출하는 실수를 방지할 수 있다.
- ArchUnit 도입 타이밍은 별도로 결정 (다른 도메인 아키텍처 테스트와 함께 도입이 더 자연스럽다).

### 결과

- ADR-012의 임시 처방(ADR-D의 try-catch 보호)이 ADR-1(Payment 존재 체크)로 대체되어 더 깔끔한 구조가 됨
- #117 (ADR-012 멱등 자기 전이 허용) close — ADR-1 도입으로 race window에서 mark throw 경로 자체가 줄어듦
