# 기능 PRD

## 기능명

- `payment-attempt-idempotency`

## 배경

`PaymentAttemptService`는 `(merchantPayKey, provider, paymentId, type)` 조합을 멱등성 키로 사용해 중복 결제 시도를 막는다.
unique 제약 충돌이 나면 catch 블록에서 기존 attempt를 재조회해 그대로 반환한다.

문제는 동일 키에 **다른 amount**로 재요청이 들어와도 기존 attempt를 침묵 처리하며 반환한다는 점이다.
호출자 측 amount 산출 오류나 PG 응답 검증 흐름에서 어떤 amount를 기준으로 삼아야 할지 모호해지고,
멱등성 계약("같은 요청 → 같은 결과") 위반이 가시화되지 않는다.

## 목표

- 동일 멱등 키에 다른 amount가 들어오면 명시적 예외로 거부한다.
- PG 응답 mismatch(외부 원인)와 호출자 측 mismatch(내부 원인)를 에러 코드 수준에서 분리한다.
- 같은 catch 블록의 infrastructure 예외 누수와 파라미터 명명 불일치를 함께 정리한다.

## 범위

**포함**:
- `PaymentErrorCode`에 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` (409) 신규 추가
- `PaymentAttemptService.getOrCreateApproveAttempt` catch 블록 보강
- `PaymentAttemptService.getOrCreateCancelAttempt` catch 블록 보강
- 파라미터 명명 통일 (`pgPaymentId` → `paymentId`)
- 단위 테스트 및 동시성 테스트 추가
- `docs/adr.md`에 ADR-010 추가

**제외**:
- `PaymentAttempt` 상태 전이 검증 (Issue #99)
- `DataIntegrityViolationException` catch 범위 좁히기 (Issue #100)
- 결제 API 에러 코드 문서화 (`api-spec.md`)

## 주요 시나리오

1. 동일 키 + 동일 amount 재요청 → 기존 attempt 반환 (기존 동작 유지)
2. 동일 키 + 다른 amount 재요청 → `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` (409) 예외
3. unique 충돌 후 재조회도 실패 → `PAYMENT_ATTEMPT_NOT_FOUND` 예외 (infrastructure 예외 누수 방지)
4. 동시 다른 amount 요청 → 1건 저장 성공, 나머지 모두 mismatch 예외

## 요구사항

- APPROVE/CANCEL 각각: 동일 키 + 다른 amount → `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` 예외
- 기존 attempt status(REQUESTED/FAILED/SUCCEEDED)와 무관하게 적용
- mismatch 감지 시 WARN 로그로 merchantPayKey, existing amount, requested amount 기록
- infrastructure 예외(`DataIntegrityViolationException`) → `PaymentException(PAYMENT_ATTEMPT_NOT_FOUND)` 변환

## 제약사항

- `Payment.pgPaymentId` 필드와 `NaverPayApproveResponse.pgPaymentId`는 변경하지 않는다. 내부 도메인 명명과 PG API 스펙 명명의 의도된 분리.
- 정상 흐름(`NaverPayApprovalService`)에서는 새 예외가 발생하지 않아야 한다.
