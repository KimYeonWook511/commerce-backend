# 태스크 API Spec

## 변경 사항

이번 작업은 새로운 API endpoint를 추가하지 않는다. 신규 에러 코드 2개가 기존 결제 API 응답에 추가된다.

## 신규 에러 응답

기존 결제 API(`POST /api/payments/naverpay/approve` 등)에서 도메인 무결성 위반 시 반환될 수 있는 에러.

### PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED

정상 흐름에서는 발생하지 않음. 코드 버그 또는 race window에서 attempt가 이미 SUCCEEDED/FAILED 상태일 때 mark 호출 시 발생.

```json
{
  "code": "PAYMENT-500-1",
  "message": "결제 시도 상태 전이가 허용되지 않습니다"
}
```

| 항목 | 값 |
|---|---|
| HTTP 상태 | 500 INTERNAL_SERVER_ERROR |
| 에러 코드 | `PAYMENT-500-1` |
| 발생 조건 | attempt.status != REQUESTED |

### PAYMENT_ATTEMPT_TYPE_MISMATCH

attempt의 type과 호출된 mark 메서드가 일치하지 않을 때 발생. 현실적으로는 거의 발생하지 않음.

```json
{
  "code": "PAYMENT-500-2",
  "message": "결제 시도 타입과 mark 요청이 일치하지 않습니다"
}
```

| 항목 | 값 |
|---|---|
| HTTP 상태 | 500 INTERNAL_SERVER_ERROR |
| 에러 코드 | `PAYMENT-500-2` |
| 발생 조건 | CANCEL attempt에 markApprove* 호출, 또는 APPROVE attempt에 markCancel* 호출 |
