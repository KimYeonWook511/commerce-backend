# 기능 API 스펙

## API 변경 없음

이번 변경은 `PaymentAttemptService` 내부 정책이며 외부 API 엔드포인트 추가/변경이 없다.

## 영향받는 응답 에러 코드

기존 결제 API 응답에서 발생할 수 있는 새 에러 코드:

| 코드 | HTTP | 설명 | 발생 시점 |
|---|---|---|---|
| `PAYMENT-409-3` | 409 Conflict | 결제 시도 이력의 금액과 요청 금액이 일치하지 않습니다 | 동일 멱등 키 + 다른 amount로 재요청 시 |

기존 에러 코드 중 동작이 변경되는 항목:

| 코드 | HTTP | 변경 내용 |
|---|---|---|
| `PAYMENT-404-2` (`PAYMENT_ATTEMPT_NOT_FOUND`) | 404 | unique 충돌 후 재조회 실패 시 `DataIntegrityViolationException` 누수 대신 이 코드로 응답하도록 변경 |
