# 태스크 API 스펙

## 개요

- 이 태스크는 결제 reserve / approve 흐름의 *내부 구현* 을 재설계하면서, *외부 endpoint 이름* 도 의미에 맞춰 정정한다.
- **호환 깨는 변경**: `POST /payments/ready` → `POST /payments/reserve` URL 변경. frontend 가 미개발이라 호환 깨도 무방.
- 응답 본문 구조는 동일 (필드 이름/구조 유지). DTO class 이름만 rename.
- UNKNOWN 차단 시 새 응답 코드 1 개 추가 (`PAYMENT_RESULT_PENDING`).
- 같은 `merchantPayKey` 의 redirect 중복 → 차단이 아닌 *멱등 응답 200* 으로 흡수.

## 엔드포인트

### POST `/payments/reserve` (rename from `/payments/ready`)

- **메서드**: POST
- **설명**: 결제 준비 (예약). 유효 RESERVED `PaymentReservation` 있으면 재사용, 없으면 새 발급
- **변경**: URL 변경 (ready → reserve), 응답 본문 구조 동일. 내부 동작이 *Order 에 키 저장* 에서 *`PaymentReservation` 행 생성/재사용* 으로 변경
- **DTO rename**: `PaymentReadyRequest` → `ReservePaymentRequest`, `PaymentReadyResponse` → `ReservePaymentResponse`

#### 요청

```json
{
  "orderId": 12345,
  "provider": "NAVERPAY"
}
```

#### 응답 (200)

```json
{
  "clientId": "...",
  "chainId": "...",
  "merchantPayKey": "PAY-01HXXX...",
  "productName": "상품명 외 N건",
  "productCount": 3,
  "totalPayAmount": 50000,
  "taxScopeAmount": 50000,
  "taxExScopeAmount": 0,
  "returnUrl": "https://.../return?merchantPayKey=PAY-01HXXX..."
}
```

### POST `/payments/naverpay/approve` (기존)

- **메서드**: POST
- **설명**: PG redirect 후 승인 처리
- **변경**: 요청/응답 동일. 내부 역조회 경로가 `Order.merchantPayKey` 에서 `PaymentReservation.merchantPayKey` 로 변경. 같은 키 중복 요청 시 멱등 응답으로 흡수

#### 요청

```json
{
  "merchantPayKey": "PAY-01HXXX...",
  "pgPaymentId": "naver-pg-id-xxx"
}
```

#### 응답 (200)

```json
{
  "pgPaymentId": "naver-pg-id-xxx",
  "status": "SUCCESS"
}
```

#### 멱등 응답 동작

같은 `merchantPayKey` 로 redirect 가 중복 도착한 경우 (USED Reservation 발견):

- 200 OK + 기존 결제 결과 응답 (`Payment.findApproveSucceeded(merchantPayKey)` 의 SUCCEEDED 행 기반)
- 차단/에러 응답 아님 — PG redirect 가 *결제 한 번 = 한 번* 정신이라 같은 키 중복은 *동일 결과 재반환* 으로 처리
- 근거: ADR-5 의 "같은 키 redirect 중복은 멱등 응답 흡수" 정책

## 검증 규칙

- `orderId` 필수, 양의 정수
- `provider` 필수, `PaymentProvider` enum 값
- `merchantPayKey` 필수, 64 자 이내
- `pgPaymentId` 필수, 64 자 이내
- 인증: `@AuthMember` — 로그인 사용자만

## 비고

### 새 응답 코드

이번 task 에서 1 개 추가:

| 코드 | HTTP | 의미 |
|---|---|---|
| `PAYMENT_RESULT_PENDING` | 409 | 해당 주문에 UNKNOWN 상태의 Payment 시도가 있어 reserve/approve 차단. 사용자에게 "결제 결과 확인 중" 안내 |

### 응답 변경 (기존 의미 보존)

- `ReservePaymentResponse.merchantPayKey`: 발급 주체가 Order 에서 `PaymentReservation` 행으로 이동. 값 자체와 사용 방식은 동일
- `NaverPayApproveResponse`: 응답 구조 동일

### 호환성

- 클라이언트 입장에서 *URL 깨는 변경* 1 건: `POST /payments/ready` → `POST /payments/reserve`. frontend 미개발이라 무방
- 응답 본문 구조 동일 (`PaymentReadyResponse` → `ReservePaymentResponse` 는 class 이름만 변경)
- 새 응답 코드 `PAYMENT_RESULT_PENDING` 추가 → 프론트가 409 핸들링 필요 (frontend 세션 책임)
- redirect URL 의 `merchantPayKey` query 파라미터 동일
- workspace `docs/api-contract.md` 갱신은 **frontend 세션 책임** (backend 세션은 commerce-workspace/docs/ 하위 문서를 만지지 않음)

### 인증/권한

- 두 엔드포인트 모두 로그인 필수 (`memberId` SecurityContext 에서 추출)
- `/payments/reserve` — 요청한 회원이 주문 소유자여야 함 (`orderRepository.findByIdAndMemberId`)
- `/payments/naverpay/approve` — `PaymentReservation.memberId` 와 SecurityContext memberId 일치 검증
