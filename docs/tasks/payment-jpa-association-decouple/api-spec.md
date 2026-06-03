# 태스크 API 스펙

## 개요

- 본 태스크는 API 응답 계약을 변경하지 않는다.
- 변경 대상은 Payment 도메인의 JPA 매핑, 도메인 정적 팩토리 시그니처, application 호출부, test fixture 이며, controller / request / response 형식은 그대로 유지된다.

## 엔드포인트

기존 엔드포인트 그대로 유지. 본 태스크에서 형식 변경 없음.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/payments/ready` | 결제 준비 |
| `POST` | `/naverpay/approve` | NaverPay 승인 처리 |

내부 호출 / 보상 흐름:

- `PaymentApprovalService.completeApprovedPayment(merchantPayKey, provider, pgPaymentId, approvedAt)` — 승인 흐름의 결제 완료 반영.
- `PaymentApprovalCompensationService` — 보상 흐름 (변경 없음).

## 요청

기존 요청 계약 그대로 유지.

## 응답

기존 응답 계약 그대로 유지.

### 응답 조립 변경 사항

- 본 태스크에서 응답 DTO 시그니처·필드·매핑 변경 없음.
- `PaymentReadyResult` 의 외부 주입 패턴 (productName 외부 주입) 은 이미 선행 Order PR #200 에서 정립됐다. 본 sub-PR 에서 추가 변경 없음.
- `NaverPayApproveResponse` 는 Payment 객체 자체에서 추출 가능한 필드 (`pgPaymentId`, `status`) 만 사용. 본 sub-PR 영향 없음.

## 검증 규칙

- 기존 검증 규칙 유지. 본 태스크에서 검증 규칙 변경 없음.

## 비고

- 응답 필드의 path/command echo (예: 응답에 `pgPaymentId` 등이 그대로 되돌아가는 구조) 정비는 본 태스크 범위가 아니며 별도 트랙으로 분리한다.
- 선행 sub-PR (`stock-jpa-association-decouple`, `order-jpa-association-decouple`) 과 동일 정책.
