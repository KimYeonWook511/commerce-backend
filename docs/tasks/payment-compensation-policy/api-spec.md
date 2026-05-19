# 태스크 API 스펙

## 개요

이 태스크는 외부 API를 추가하거나 변경하지 않는다.

## 변경 없음

- `POST /payments/naverpay/approve` 요청/응답 형식 유지
- 내부 보상 로직(cancel 진행 여부 결정)이 변경되지만 클라이언트가 관찰 가능한 응답은 동일하다
- race window에서 보상 cancel이 skip되더라도 최종 응답 (에러 코드, HTTP 상태)은 기존과 동일

## 비고

내부 메서드 `PaymentApprovalService.isCompensationRequired(String merchantPayKey): boolean`은 외부 API가 아닌 application 계층 내부 메서드다. 미래 Payment 도메인 분리 시 외부 API로 승격 가능한 시그니처이나, 현재는 내부 메서드다.
