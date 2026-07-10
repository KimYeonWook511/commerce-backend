# 이중결제 탐지를 application raw DAO catch에서 adapter 도메인 예외 매핑으로 전환한다

- Status: accepted
- Date: 2026-06-08

## Context

#225, PR #224 리뷰 #3. 이중결제 보상이 두 갈래였다 — live(`catch(DataIntegrityViolationException)` → cancel-first, find-first 결정(→ PR#109)을 위반하는 raw DAO 의존)와 호출 불가능한 dead(`case PAYMENT_DUPLICATE` → fail-first). cancel-first는 크래시 시 "approve REQUESTED + cancel" 잔여를 만들었다.

이 전환은 DB unique 위반을 find-first + 안전망 500으로 처리하기로 한 기존 결정(→ PR#109)이 허용한 try-save-catch carve-out(adapter에서 인프라 예외를 도메인 예외로 번역)에 해당한다. 보상이 필요한 case라 find-first+500보다 adapter 매핑이 적합하다. `saveAndFlush`의 조기 flush가 위반을 adapter 호출 안에서 확정하는 load-bearing 의존성이다. 매핑을 succeed-approve 전용 메서드 + 제약명 일치로 한정해 다른 무결성 위반의 오매핑을 막는다.

## Decision

application(`NaverPayApprovalService`)의 raw `catch(DataIntegrityViolationException)`을 제거한다. `PaymentRepositoryAdapter`의 succeed-approve 전용 저장 경로(`saveApproved`)가 `saveAndFlush` 위반을 `uk_payment_approved_order_key`인 경우에만 `PaymentException(PAYMENT_DUPLICATE)`로 매핑하고, 그 외 무결성 위반은 원 예외를 그대로 전파한다. application은 도메인 예외 `case PAYMENT_DUPLICATE`로 반응해 fail-first 단일 보상(`compensateDuplicatePayment`)을 수행하고, cancel-first 경로(`compensateDuplicateApproval`)는 제거한다.

**제약명 식별**: `JpaConfig`의 `SQLErrorCodeSQLExceptionTranslator` 때문에 unique 위반은 `DuplicateKeyException`(cause=JDBC `SQLException`)으로 변환되어 cause 체인에 Hibernate `ConstraintViolationException`이 남지 않는다. 따라서 제약명은 `getConstraintName()`이 아니라 `SQLException` 메시지의 단어 경계 매칭(`\b...\b`, 대소문자 무시 — prefix 공유 오탐 방지)으로 식별한다. translator를 제거해 `getConstraintName()` 경로를 되살리는 근본 단순화는 전역 예외 분류·로깅에 영향을 주는 별도 사안으로 #227에 분리했다.

## Consequences

이중결제 보상이 fail-first 단일 경로로 통일되어 "approve REQUESTED + cancel" 잔여가 사라진다(fail-first 잔여는 `APPROVED_CANCEL_COMPENSATION` 정책이 처리). 제약명 식별이 메시지 문자열에 의존한다(translator 유지 하).

이 결정의 제약명 식별 메커니즘(translator 유지 하 메시지 단어 경계 매칭)은 이후 갱신됐다(→ PR#228). carve-out 자체(adapter 도메인 예외 매핑·fail-first 보상)는 유효하다.

연계: find-first 패턴 결정(→ PR#109), 보상 진행 여부 판단(→ PR#118)·보상 정책 책임 배치(→ PR#125)·결제 도메인 재설계(→ PR#205), #225, #227, PR #224 리뷰 #3.
