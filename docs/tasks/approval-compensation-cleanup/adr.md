# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: 정상 승인 후 기록 실패는 환불하지 않고 REQUESTED로 두어 reconcile이 완료시킨다(완료 우선)

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `completeVerifiedApproval`은 PG SUCCESS(캡처 완료) + `verifyApprovedResponse`(키·금액 일치) 통과 **뒤** 호출된다. 이 시점의 결제는 *맞는 결제*다.
- 기존 catch 흐름은 `succeedApproval`이 unmapped `PaymentException` / `CustomException` / 일반 `Exception`으로 실패하면 `compensateUnexpected`가 approve를 `FAILED(APPROVE_PROCESS_FAILED)`로 마킹하고 PG 환불했다.
- 그 결과 DB 데드락 등 *transient* 기록 실패에도 정당한 매출을 취소·환불했고, "완료가 맞는 상황 / FAILED가 맞는 상황 / 버그"를 한 status로 싸잡았다.

### 고려한 대안

- **현행 유지(환불)**: 정상 매출을 transient 실패로 박제·환불하는 문제가 남는다. 기각.
- **transient만 골라 재시도**: 실시간 경로에서 transient/영구를 신뢰성 있게 가르기 어렵고, #221 정책이 이미 stale REQUESTED self-heal 경로를 제공한다. 정책과 중복. 기각.

### 결정 내용

- `compensateUnexpected` 환불을 제거한다. unmapped 예외는 **전파(500)**하고 approve는 `REQUESTED`로 남긴다.
- `REQUESTED` 잔여는 배치 reconcile(#221 정책 `APPROVE_RECONCILE` + PG `PG_APPROVED` → 완료)이 self-heal한다.
- 진짜 버그도 전파(500)로 가시화한다.
- 명시적 비정상(`MERCHANT_KEY_MISMATCH`/`AMOUNT_MISMATCH`)은 *틀린 결제*라 현행 보상(환불)을 유지한다.

### 근거

- 금전 정합성: 정상 매출을 transient로 취소하지 않는다(돈은 극저확률 경합도 안전 우선).
- 책임 분리: 실시간은 "완료 또는 흔적(REQUESTED) 남김"까지, 결과 확정·복구는 배치 reconcile이 맡는다(ADR-027 결과 불명 보존과 같은 방향).

### 결과

- transient 기록 실패 시 환불이 사라지고 reconcile이 완료를 책임진다.
- unmapped 예외가 status로 둔갑하지 않고 500으로 드러나 운영 가시성이 올라간다.
- 호출처가 사라진 `compensateUnexpected`는 제거된다.

---

## ADR-L2: 이중결제 탐지를 adapter 도메인 예외 매핑으로 전환한다(ADR-011 carve-out)

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 이중결제 보상이 두 갈래였다 — live(`catch(DataIntegrityViolationException)` → `compensateDuplicateApproval`, cancel-first)와 dead(`case PAYMENT_DUPLICATE` → `compensateDuplicatePayment`, fail-first). 후자는 try에서 `PAYMENT_DUPLICATE`가 던져지지 않아 호출 불가능한 dead code였다.
- live 경로는 application(`NaverPayApprovalService`)이 `DataIntegrityViolationException`을 직접 catch해 ADR-011(app/adapter raw DAO 예외 직접 의존 금지)을 위반했다. cancel-first 순서는 크래시 시 "approve REQUESTED + cancel" 잔여(PR #224 리뷰 #3)를 만들었다.

### 고려한 대안

- **find-first + 안전망 500(ADR-011 기본형)**: 이중결제는 보상(PG 환불)이 필요한 case라 단순 500 위임으로는 부족하다. 기각.
- **application에서 raw catch 유지**: ADR-011 위반이 그대로 남는다. 기각.
- **전용 메서드에서 무조건 매핑(constraint name 미확인)**: succeed-approve UPDATE에서 사실상 `uk_payment_approved_order_key`만 위반 가능하지만, 다른 무결성 위반(FK/NOT NULL/타 unique)을 오매핑할 잔여 위험이 있다. 더 정밀한 안을 채택.

### 결정 내용

- application의 raw `catch(DataIntegrityViolationException)`을 제거한다.
- `PaymentRepository`에 succeed-approve 전용 저장 메서드(`saveApproved`)를 추가한다. adapter는 `saveAndFlush`가 던지는 `DataIntegrityViolationException`의 cause 체인에서 Hibernate `ConstraintViolationException.getConstraintName()`이 `uk_payment_approved_order_key`일 때만 `PaymentException(PAYMENT_DUPLICATE)`로 매핑하고, 그 외는 원 예외 그대로 전파한다.
- 매핑을 전용 메서드 + constraint name으로 이중 한정해, 다른 무결성 위반을 `PAYMENT_DUPLICATE`로 오매핑하지 않는다. 범용 `save()`는 매핑하지 않는다.
- application은 도메인 예외 `case PAYMENT_DUPLICATE`로 반응한다(dead → live). `compensateDuplicatePayment`(fail-first)가 live 경로가 되고 `compensateDuplicateApproval`(cancel-first)은 제거한다.

### 근거

- ADR-011은 try-save-catch 선택 시 *인프라 예외 타입에 직접 의존하지 않도록 adapter에서 처리*하는 carve-out을 허용한다. 보상이 필요한 case라 find-first+500보다 adapter 매핑이 적합하다.
- 동시 두 승인(같은 orderId, 다른 pgPaymentId)은 InnoDB가 unique 충돌 레코드에 S-lock을 걸어 상대 commit까지 대기 → 그 후 위반 확정한다. 즉시든 대기 후든 위반이 adapter의 `saveAndFlush` 호출 안에서 드러나므로 거기서 번역 가능하다. 단순 INSERT/UPDATE라 gap lock으로 무관한 주문(다른 orderId)을 막지 않는다.
- `saveAndFlush`의 조기 flush가 트랜잭션 경계 전에 위반을 adapter 호출 안으로 끌어와 확정하는 load-bearing 의존성이다. 이 호출을 일반 `save`로 바꾸거나 flush 타이밍을 미루면 매핑이 동작하지 않는다.
- constraint name 확인은 H2(슬라이스)보다 MySQL(Testcontainers/운영)에서 정확하므로, 이중결제·오매핑 검증은 MySQL 통합/동시성 테스트로 보장한다.

### 결과

- application이 raw DAO 예외에 의존하지 않게 되어 ADR-011 정합으로 복귀한다.
- 이중결제 보상이 fail-first 단일 경로(`compensateDuplicatePayment`)로 통일되어 "approve REQUESTED + cancel" 잔여가 사라진다(fail-first 잔여는 정책의 `APPROVED_CANCEL_COMPENSATION`이 처리).
- adapter가 Hibernate `ConstraintViolationException` 타입에 의존한다(인프라 계층 내부라 허용 범위). constraint name이 추출되지 않으면 매핑하지 않고 원 예외를 전파한다.
