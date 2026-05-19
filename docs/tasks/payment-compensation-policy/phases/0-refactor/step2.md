# Step 2: stabilize-concurrency-tests

## 읽어야 할 파일

먼저 아래 파일들을 읽고 현재 테스트 구조와 실패 지점을 파악하라:

- `/docs/tasks/payment-compensation-policy/prd.md`
- `/docs/tasks/payment-compensation-policy/architecture.md`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java` — step 0에서 변경됨
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` — step 0, 1에서 변경됨
- `src/test/java/com/commerce/payment/application/PaymentApprovalServiceConcurrencyTest.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceConcurrencyTest.java` (경로 확인 필요)

## 작업

### 1. PaymentApprovalServiceConcurrencyTest flaky 원인 파악

현재 flaky로 식별된 `PaymentApprovalServiceConcurrencyTest`의 assertion 실패 지점을 추적한다:
- race window에서 어떤 assertion이 불안정한지 파악
- step 0에서 추가한 Payment 존재 체크가 race를 흡수하는지 확인
- 테스트 자체의 timing dependency가 있다면 보강

### 2. NaverPayServiceConcurrencyTest race cancel skip 시나리오 보강

`NaverPayServiceConcurrencyTest`에 아래 케이스를 추가하거나 보강한다:
- Thread A가 먼저 Payment를 생성한 뒤 Thread B의 보상 흐름 진입 시 cancel이 skip되는 시나리오
- cancel skip 여부를 mock 또는 검증 가능한 방식으로 확인

### 3. 10회 반복 안정성 확인

변경 후 concurrency 테스트를 반복 실행해 flaky 회귀가 없는지 확인한다.

## 수정 가능 경로

- `src/test/java/com/commerce/payment/application/PaymentApprovalServiceConcurrencyTest.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceConcurrencyTest.java` (경로 확인 후 수정)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - concurrency 테스트 전체 통과
   - 가능하면 같은 테스트를 반복 실행해 flaky 재발 없음 확인
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 테스트를 단순히 삭제하거나 ignore 처리해서 통과시키지 마라. 이유: 동시성 테스트가 사라지면 미래 race 회귀를 감지할 수 없다.
- 테스트 timing을 `Thread.sleep`으로 늘려 통과시키지 마라. 이유: timing 의존은 CI 환경에서 불안정하다. race를 구조적으로 안전하게 만드는 것이 목적이다.
- 기존 테스트를 깨뜨리지 마라.
