# Task API 스펙

> 이 문서는 이번 Task가 추가·변경하는 API **변경분(delta)**이다.
> 전체 API의 현재 진실은 루트 `docs/api-spec.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- 네이버페이 승인 API의 **실패 응답**만 변경된다. 요청·성공 응답 형태와 엔드포인트는 그대로다.

## 엔드포인트

- 메서드: `POST`
- 경로: `/payments/naverpay/approve`
- 설명: 네이버페이 결제 승인(최종 캡처).

## 요청

- 변경 없음. (memberId는 인증 컨텍스트, body는 merchantPayKey·pgPaymentId 등 기존과 동일)

## 응답

- 성공 응답: 변경 없음.
- 실패 응답 변경분:
  - **제거**: `PAYMENT_MEMBER_MISMATCH`(403). 더 이상 반환하지 않는다.
  - **변경**: 다른 회원의 merchantPayKey 또는 존재하지 않는 키로 승인 요청 시 `PAYMENT_NOT_FOUND`(404)로 응답한다(키 존재 비노출). (ADR-L3)
  - **추가**: 이미 성공한 APPROVE 결제가 있는 주문에 새 승인 진입 시 진입 단계에서 차단 실패 응답을 반환한다(`PAYMENT_DUPLICATE` 제안). (ADR-L2)
  - **추가**: 같은 예약의 동시 이중 use 경합에서 진 쪽은 PG 호출 전 차단 실패 응답을 반환한다(신규 `PAYMENT_RESERVATION_ALREADY_USED` 제안). (ADR-L1)

## 검증 규칙

- 변경 없음.

## 비고

- 동시성 차단 실패코드(`PAYMENT_DUPLICATE` / `PAYMENT_RESERVATION_ALREADY_USED`)의 최종 코드·HTTP 상태는 구현 step에서 `PaymentErrorCode` 컨벤션에 맞춰 확정한다.
- 정합성의 최종 보장은 #230 경로가 담당한다. 본 변경은 그 앞단 진입·예약 차단의 응답 표면이다.
