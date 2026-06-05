# 기능 아키텍처

## 개요

`payment-attempt-idempotency`는 결제 시도 멱등 처리 흐름에 amount 불일치 검증 정책을 추가한다.
application 계층(`PaymentAttemptService`)의 catch 블록 보강이 핵심이며, DB 스키마 변경은 없다.

## 변경 대상

| 레이어 | 파일 | 변경 유형 |
|---|---|---|
| exception | `payment/exception/PaymentErrorCode.java` | 신규 enum 값 추가 |
| application | `payment/application/PaymentAttemptService.java` | catch 블록 보강, 파라미터 명명 통일 |
| test (unit) | `payment/application/PaymentAttemptServiceTest.java` | 신규 케이스 추가, 기존 케이스 수정 |
| test (concurrency) | `payment/application/concurrency/PaymentAttemptServiceConcurrencyTest.java` | 신규 케이스 추가 |
| docs | `docs/adr.md` | ADR-010 추가 |

## 설계 방향

### amount 검증 위치: catch 블록 한 곳만

`getOrCreateApproveAttempt`와 `getOrCreateCancelAttempt`는 `Propagation.NOT_SUPPORTED`로 실행된다.
`save()` 직후 commit이 이뤄지므로 unique 위반은 catch에서 잡힌다.

검증 위치를 catch 블록으로 한정하는 이유:
- `save()` 성공 경로는 충돌이 없었던 케이스 → 검증 대상이 없음
- `save()` 전 pre-check(select)를 추가하면 일반 경로에 불필요한 쿼리가 발생

### 에러 코드 분리

기존 `PAYMENT_AMOUNT_MISMATCH`(400)는 `NaverPayApprovalService`에서 PG 응답 금액 검증에 사용 중이다.
이번에 추가하는 mismatch(호출자 측 원인)는 의미와 모니터링 기준이 다르므로 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409)로 분리한다.

### infrastructure 예외 변환

기존 `.orElseThrow(() -> ex)`는 `DataIntegrityViolationException`을 그대로 Presentation까지 누수시킨다.
`CLAUDE.md` 규칙에 따라 `PaymentException(PAYMENT_ATTEMPT_NOT_FOUND)`로 변환한다.

## 데이터 흐름

```
getOrCreateApproveAttempt(merchantPayKey, provider, paymentId, amount)
  ↓
paymentAttemptRepository.save(PaymentAttempt.createApproveRequested(...))
  ↓ unique 충돌
catch(DataIntegrityViolationException)
  ↓
findApproveAttempt(...)
  → empty  → log.error + PaymentException(PAYMENT_ATTEMPT_NOT_FOUND)   [A1 변경]
  → found
    → existing.amount ≠ amount → log.warn + PAYMENT_ATTEMPT_AMOUNT_MISMATCH  [신규]
    → existing.amount = amount → return existing                               [기존 유지]
```

## 예외 및 실패 처리

| 상황 | 이전 동작 | 변경 후 동작 |
|---|---|---|
| 동일 키 + 다른 amount 재요청 | 기존 attempt 반환 (침묵 처리) | `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` (409) |
| 충돌 후 재조회 실패 | `DataIntegrityViolationException` 누수 | `PAYMENT_ATTEMPT_NOT_FOUND` (404) |
| 동일 키 + 같은 amount 재요청 | 기존 attempt 반환 | 동일 (변경 없음) |

## 테스트 포인트

- `PaymentAttemptServiceTest`: APPROVE/CANCEL 각각 mismatch 시나리오 (unit, mock 기반)
- `PaymentAttemptServiceConcurrencyTest`: 동시 다른 amount 시나리오 (실제 DB, Docker)
- `NaverPay` 관련 기존 테스트 회귀 확인
