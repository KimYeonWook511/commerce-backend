# 회고록: payment-attempt-service-split

## 1. 작업 요약

### 무엇을 변경했는가

`PaymentAttemptService`를 APPROVE/CANCEL 흐름별 두 Service로 분리하고, `PaymentAttempt` 도메인 메서드를 통합·정리했다.

변경 범위:

**삭제된 파일:**
- `payment/application/PaymentAttemptService.java`
- `test/.../PaymentAttemptServiceTest.java`
- `test/.../PaymentAttemptServiceConcurrencyTest.java` (기존 통합 파일)

**신설된 파일:**
- `payment/application/PaymentApprovalAttemptService.java`
- `payment/application/PaymentCancellationAttemptService.java`
- `test/.../PaymentApprovalAttemptServiceTest.java`
- `test/.../PaymentCancellationAttemptServiceTest.java`
- `test/.../PaymentApprovalAttemptServiceConcurrencyTest.java`
- `test/.../PaymentCancellationAttemptServiceConcurrencyTest.java`

**도메인 변경:**
- `PaymentAttempt`: mark 4개 메서드 → `succeed(respondedAt)` + `fail(failCode, detail, respondedAt)` 통합, `verifyApprovedResponse(merchantPayKey, totalAmount)` 신설, type 가드 제거, status 가드 유지

**갱신된 호출처:**
- `PaymentApprovalService`: `paymentAttemptService` → `paymentApprovalAttemptService` 교체
- `NaverPayApprovalService`: `paymentAttemptService` → `paymentApprovalAttemptService` + `paymentCancellationAttemptService` 교체, `validateApproved*` private 메서드 삭제 후 `attempt.verifyApprovedResponse(...)` 한 줄로 교체

**갱신된 루트 docs:**
- `docs/adr.md`: ADR-011 적용 대상, ADR-012 제목·본문, ADR-014 갱신
- `docs/architecture.md`: 서비스 테이블 및 결제 승인 흐름
- `docs/exception-strategy.md`: find-first 적용 대상 목록, failIfRequested 언급 갱신

---

## 2. 설계 결정 요약

### ADR-1: PaymentAttemptService를 흐름별로 분리하고 이 Repo 서비스 컨벤션을 따른다

`PaymentApprovalAttemptService`(APPROVE 흐름)와 `PaymentCancellationAttemptService`(CANCEL 흐름)로 분리. 명사형 컨벤션(`<도메인><유스케이스>Service`)을 따른다.

APPROVE/CANCEL 두 흐름이 7개 메서드로 혼재한 기존 클래스는 CLAUDE.md의 "Service는 유스케이스 단위 단일 행위" 룰을 위반했다. payment 도메인의 기존 컨벤션(`PaymentApprovalService`, `PaymentReadyService`)이 명사형으로 정착돼 있어 명사형이 톤을 맞춘다. 클래스 레벨 `@Transactional` 없이 메서드별 명시(`getOrCreate`: `NOT_SUPPORTED`, `succeed`/`fail`/`failIfRequested`: `REQUIRED`)해 트랜잭션 경계를 메서드 시그니처에서 한 눈에 확인할 수 있게 했다. 분리 결과 각 Service가 3–4개 메서드로 단순해지고, 메서드명에서 `Approve`/`Cancel` 접두어가 사라졌다.

### ADR-2: PaymentAttempt 도메인 메서드를 succeed/fail 두 개로 통합하고 type 가드를 제거한다

mark 4개 메서드를 `succeed`/`fail` 두 개로 통합. `if (this.type != ...) throw` type 가드 제거. `status != REQUESTED` 가드 유지.

분리된 두 Service가 항상 올바른 type의 attempt만 조회·전달하므로 도메인 내 type 가드는 방어 가치를 잃는다. ADR-012(이전 task)의 핵심("REQUESTED 외 전이 거부 + failCode 보호")은 status 가드로 그대로 보존된다. type 가드 제거로 `PaymentAttemptTest`의 type 가드 관련 케이스 4개(`markApproveSucceeded_whenTypeIsCancel_throwException` 등)가 삭제됐다.

### ADR-3: validate 두 메서드를 PaymentAttempt.verifyApprovedResponse로 도메인에 통합한다

`NaverPayApprovalService`의 `validateApprovedMerchantPayKeyOrThrow`/`validateApprovedAmountOrThrow`를 `PaymentAttempt.verifyApprovedResponse(merchantPayKey, totalAmount)`로 통합. merchantPayKey 불일치 시 `PAYMENT_MERCHANT_KEY_MISMATCH`, amount 불일치 시 `PAYMENT_AMOUNT_MISMATCH` throw.

두 validate 메서드는 attempt의 자기 필드와 PG 응답을 비교하는 순수 무결성 검증이다. DB 접근이 없고 attempt 자신의 책임 범위에 속하므로 도메인 메서드로 옮기는 것이 자연스럽다. 호출 컨텍스트(`NaverPayApprovalService.completeVerifiedApproval`)가 트랜잭션 없이 실행되고 attempt는 detached 상태라 트랜잭션 영향이 없다. `NaverPayApprovalService.completeVerifiedApproval`의 validate 두 줄이 `attempt.verifyApprovedResponse(...)` 한 줄로 줄었다.

---

## 3. 발견한 것

### type 가드 제거 시 삭제된 테스트 케이스 수

`PaymentAttemptTest`의 type 가드 관련 케이스는 정확히 4개였다(`markApproveSucceeded_whenTypeIsCancel`, `markApproveFailed_whenTypeIsCancel`, `markCancelSucceeded_whenTypeIsApprove`, `markCancelFailed_whenTypeIsApprove`). 삭제된 케이스 수가 예상과 일치했으며, status 가드 케이스는 그대로 `succeed`/`fail` 메서드에 재사용할 수 있어 테스트 감소 폭이 의외로 적었다.

### ConcurrencyTest 분할 구조

`PaymentAttemptServiceConcurrencyTest`는 APPROVE 관련 2개 케이스와 CANCEL 관련 2개 케이스로 이미 의미상 분리되어 있었다. 분할이 기계적으로 깔끔하게 이루어졌으며, `PaymentApprovalAttemptServiceConcurrencyTest`와 `PaymentCancellationAttemptServiceConcurrencyTest` 각각 2개 케이스로 나뉘었다.

### NaverPayApprovalService 보상 메서드의 의존성 교체 범위

`NaverPayApprovalService` 내 보상 메서드(`failApproveAndCancelApprovedPayment`, `compensate*`, `processCancelRequest`)는 이 task에서 변경하지 않는 범위였음에도 내부적으로 `paymentApprovalAttemptService`와 `paymentCancellationAttemptService` 두 의존성을 참조해야 하는 구조가 됐다. 보상 메서드 자체의 시그니처는 바뀌지 않았지만, 주입 대상이 두 개로 늘어나 생성자가 길어졌다. task B에서 보상 dispatcher를 이동하면 이 의존성은 줄어들 것이다.

### PaymentApprovalService의 클래스 레벨 @Transactional 제거

architecture.md에 명시된 대로 `PaymentApprovalService`의 클래스 레벨 `@Transactional`을 이 task에서 제거하고 메서드별로 명시했다. 이 변경이 step 0 산출물에 포함됐고, 이후 step에서 별도 충돌 없이 유지됐다.

---

## 4. 미결 과제

### task B: payment-compensation-to-domain에서 처리할 내용

- **보상 dispatcher 이동**: `NaverPayApprovalService` 내 `compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`, `compensateUnexpected` 메서드를 `payment.application`으로 이동
- **`PgCanceller`/`CancelOutcome` 신설**: PG cancel 결과를 표현하는 타입 추가
- **`NaverPayApprovalService` 보상 골격 정리**: 보상 메서드 이동 후 NaverPay 어댑터의 보상 관련 코드 정리

### 후속 검토 가능성

- **PaymentGateway port 완전 inversion**: NaverPay 어댑터를 도메인 port 뒤로 완전히 숨기는 작업. 현 시점에서는 범위가 크다.
- **ArchUnit 가시성 강제**: `PaymentAttempt.succeed`/`fail` 메서드의 직접 호출을 CI에서 차단하는 규칙 추가. 다른 도메인 아키텍처 테스트와 함께 도입하는 것이 자연스럽다.
- **PaymentReference Value Object 도입**: `merchantPayKey`가 두 Aggregate 간 협력 키로 String 원시 타입으로 흐른다. Payment 도메인 분리 논의 시 함께 검토할 가치가 있다.

---

## 5. 개선 제안

### 도메인 상태 전이 표 문서화

`PaymentAttempt`의 허용 전이 규칙이 `succeed`/`fail` 메서드 내부 코드로만 표현되어 있다. `PaymentAttemptStatus` enum이나 별도 문서에 상태 전이 표를 명시하면 향후 메서드 추가 시 설계 기준이 명확해진다. `payment-attempt-state-transition-policy` 회고에서도 같은 제안이 있었으므로, Order 도메인 상태 전이 규칙과 함께 정리하면 일관성이 높아진다.

### verifyApprovedResponse 테스트를 PaymentAttemptTest에 집중

`verifyApprovedResponse`는 순수 도메인 로직이므로 `NaverPayApprovalServiceTest`에서 중복으로 검증하기보다 `PaymentAttemptTest`에서 완전히 커버하는 것이 더 명확한 책임 분리다. NaverPay 어댑터 테스트에서는 `verifyApprovedResponse` 호출 자체만 확인하고 세부 검증 시나리오는 도메인 테스트에 위임하는 방향을 권장한다.

### step 설계 시 변경 여파 파악 우선

이 task는 단일 Service를 둘로 나누는 비교적 단순한 분리였으나, `NaverPayApprovalService`의 보상 메서드들이 두 서비스에 동시 의존하는 구조가 됐다. step 설계 시 호출처 그래프(call graph)를 먼저 확인하면 예상 밖의 의존성 교체 범위를 사전에 파악할 수 있다.
