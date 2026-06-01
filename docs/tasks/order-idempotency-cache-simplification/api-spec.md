# 태스크 API 스펙

## 개요

주문 생성 API (`POST /api/orders`) 의 응답 규약에 `409 ORDER_IDEMPOTENCY_IN_PROGRESS` 를 추가한다. 같은 `Idempotency-Key` 로 다른 요청이 *현재 처리 중* 임을 클라이언트에 명시적으로 알린다.

기존 흐름에서 race window 충돌로 안전망 500 이 반환되던 케이스가 본 코드로 흡수된다. 안전망 500 자체는 *Redis fallback 후 발생하는 진짜 race* (드문 케이스) 에만 남는다.

## 엔드포인트

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/orders` | 주문 생성 |

## 요청

기존과 동일.

- Header: `Idempotency-Key: <UUID>` (필수)
- Body: `OrderCreateRequest` (memberId, items 등)

## 응답

### 200 OK — 정상 생성 또는 멱등 흡수

기존과 동일.

### 400 Bad Request — 검증 실패

기존과 동일.

### 404 Not Found — 상품 / 회원 미존재 등 비즈니스 예외

기존과 동일.

### 409 Conflict — `ORDER_IDEMPOTENCY_IN_PROGRESS` *(신규)*

```json
{
  "code": "ORDER_IDEMPOTENCY_IN_PROGRESS",
  "message": "주문 생성이 이미 처리 중입니다. 잠시 후 다시 시도해주세요."
}
```

같은 `Idempotency-Key` 로 다른 요청이 처리 중인 경우 반환된다. 클라이언트는 backoff 후 재시도하면 결과를 받을 수 있다.

### 500 Internal Server Error — 안전망

ADR-011 정합 유지. Redis fallback 후 발생하는 진짜 race window 충돌이나 시스템 결함 시 반환. 빈도 매우 낮음.

## 검증 규칙

- `Idempotency-Key` 헤더 누락 또는 빈 값 → 400 `INVALID_REQUEST` (기존)
- 다른 검증 규칙 기존과 동일

## 비고

- **클라이언트 retry 정책**: 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답을 받으면 같은 `Idempotency-Key` 로 backoff 재시도가 안전하다. 예: 1초 → 2초 → 4초 exponential backoff.
- **PROCESSING TTL**: Redis 마커의 TTL 은 60초. 비정상 잔존 시 60초 후 자동 만료.
- **응답 일관성 trade-off**: 같은 `Idempotency-Key` 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (예: 첫 시도 `PRODUCT_NOT_FOUND`, 재시도 시점에 상품 등록됨 → 200). *DB 상태 기준 멱등성* 으로 본다면 정상 (자세한 근거는 ADR 참조).
