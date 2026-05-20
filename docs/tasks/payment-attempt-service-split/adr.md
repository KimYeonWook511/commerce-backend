# 태스크 ADR

---

## ADR-1: PaymentAttemptService를 흐름별로 분리하고 이 Repo 서비스 컨벤션을 따른다

### 배경

`PaymentAttemptService`의 클래스명은 prefix 뒤에 "유스케이스/흐름"이 아니라 엔티티명이 박혀 있어 이 Repo의 다른 서비스 컨벤션(`PaymentApprovalService`, `OrderCreateService`, `AuthLoginService` 등)에서 유일하게 이탈한다. APPROVE와 CANCEL 두 흐름이 한 클래스에 섞여 7개 메서드가 모여 있어 CLAUDE.md의 "Service는 유스케이스 단위 단일 행위" 룰을 위반한다.

### 결정

`PaymentApprovalAttemptService`(APPROVE 흐름)와 `PaymentCancellationAttemptService`(CANCEL 흐름)로 분리. 명사형 컨벤션(`<도메인><유스케이스>Service`)을 따른다.

### 근거

- payment 도메인은 이미 명사형 컨벤션(`PaymentApprovalService`, `PaymentReadyService`)이 정착되어 있어 명사형이 톤을 맞춘다.
- `failIfRequested`는 보상 흐름 전용이므로 `PaymentApprovalAttemptService`에만 둔다(YAGNI). `PaymentCancellationAttemptService`에는 현재 사용처 없음.
- 클래스 레벨 `@Transactional` 없이 메서드별 명시. `getOrCreate`는 `NOT_SUPPORTED`, `succeed`/`fail`/`failIfRequested`는 `REQUIRED`. 트랜잭션 경계가 메서드 시그니처에서 한 눈에 보인다.

### 결과

- 각 Service가 3-4개 메서드로 단순해진다.
- 메서드명에서 `Approve`/`Cancel` 접두어가 사라진다.
- 컴파일·테스트 모두 통과하는 일관된 중간 상태 — task B(`payment-compensation-to-domain`) 진행 기반.

---

## ADR-2: PaymentAttempt 도메인 메서드를 succeed/fail 두 개로 통합하고 type 가드를 제거한다

### 배경

mark 4개 메서드는 동작이 거의 동일한데 type 가드 중복으로 인해 접두어가 강제된다. 분리된 두 Service가 항상 올바른 type의 attempt만 조회·전달하므로 도메인 내 type 가드가 방어 가치를 잃는다. `status != REQUESTED` 가드는 상태 전이 불변식 보호에 여전히 유효하므로 유지한다(ADR-012 정책 보존).

### 결정

`succeed(respondedAt)` + `fail(failCode, detail, respondedAt)` 두 메서드로 통합. `if (this.type != ...) throw` type 가드 제거. `status != REQUESTED` 가드 유지. factory(`createApproveRequested`, `createCancelRequested`)는 그대로 유지.

### 근거

- 호출자(Service)가 type을 보장하므로 도메인에서 중복 검증할 이유 없음.
- ADR-012의 핵심("REQUESTED 외 전이 거부 + failCode 보호")은 status 가드로 보존.

### 결과

- `PaymentAttemptTest`의 type 가드 관련 케이스 4개 삭제(`markApproveSucceeded_whenTypeIsCancel_throwException` 등).
- `succeed`/`fail` 두 메서드로 단순해짐.
- ADR-012 본문을 sync-root-docs 단계에서 갱신 필요.

---

## ADR-3: validate 두 메서드를 PaymentAttempt.verifyApprovedResponse로 도메인에 통합한다

### 배경

`NaverPayApprovalService`의 `validateApprovedMerchantPayKeyOrThrow`/`validateApprovedAmountOrThrow`는 attempt의 자기 필드(`merchantPayKey`, `amount`)와 PG 응답값을 비교하는 순수 비교 + throw 로직이다. DB 접근이 없고 attempt 자신의 무결성 검증이므로 도메인 메서드로 옮기는 게 자연스럽다.

### 결정

`PaymentAttempt.verifyApprovedResponse(merchantPayKey, totalAmount)` 신설. 두 validate를 한 메서드로 통합. NaverPay 어댑터에서 두 메서드를 삭제하고 `attempt.verifyApprovedResponse(...)` 한 줄로 교체.

### 근거

- attempt의 필드 비교는 attempt 자신의 책임.
- 호출 컨텍스트(`NaverPayApprovalService.completeVerifiedApproval`)가 트랜잭션 없이 실행되고 attempt는 detached 상태라 트랜잭션 영향 없음.
- NaverPay 어댑터가 도메인 필드 접근(`attempt.getMerchantPayKey()`, `attempt.getAmount()`)을 직접 하는 대신 도메인 메서드를 통해 의도를 명확히 한다.

### 결과

- `NaverPayApprovalService.completeVerifiedApproval`의 validate 두 줄이 한 줄로 줄어든다.
- `PaymentAttemptTest`에 `verifyApprovedResponse` 케이스 추가 (키 불일치, 금액 불일치, 정상).
