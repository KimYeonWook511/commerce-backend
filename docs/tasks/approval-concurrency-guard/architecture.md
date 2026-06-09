# Task 아키텍처

> 이 문서는 이번 Task 시점의 **변경 제안 스냅샷**이다.
> 시스템의 현재 진실은 루트 `docs/architecture.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- payment 도메인의 승인(approve) 경로에 진입·예약 단계 동시성 가드를 더한다. 구조 자체(레이어·서비스 경계)는 그대로 두고, 기존 서비스/도메인/레포지토리에 가드와 조회 조건을 보강한다.

## 변경 대상

- `presentation`: 변경 없음 (`POST /payments/naverpay/approve`).
- `application`
  - `NaverPayApprovalService.approve()`: 진입 차단(`existsApprovedByOrderId`) 추가, 조회 단일화에 따른 member 분기 제거.
  - `PaymentApprovalRecordService.create()`: 낙관적 락 충돌 차단 매핑.
- `domain`
  - `PaymentReservation`: `@Version` 필드 추가. `use()`는 그대로.
  - `repository.PaymentRepository` / `PaymentReservationRepository`: 존재 조회·단일 조회 메서드 추가.
- `infrastructure`: 각 어댑터·JPA 레포지토리에 신규 메서드 구현.

## 설계 방향

- **예약 소비 동시성 (낙관적 락)**: `PaymentReservation.@Version`으로 같은 예약의 동시 이중 use를 감지한다. 도메인 `use()`의 read-modify-write를 유지해 "RESERVED일 때만" 전이 규칙과 캡슐화(ADR-3)를 보존한다. 진 쪽 충돌은 `create()`에서 차단 예외로 매핑한다. `create()`가 PG 호출보다 앞이라 PG 청구 전 차단된다.
- **진입 사전 차단**: `existsApprovedByOrderId`(= APPROVE·SUCCEEDED 존재)를 기존 `existsUnknownByOrderId` 차단과 동형으로 추가한다.
- **조회 단일화**: `(memberId, merchantPayKey)`로 조회해 남의/없는 키를 `NOT_FOUND`로 흡수한다.

## 데이터 흐름

```
approve(memberId, merchantPayKey, pgPaymentId)
  → findByMemberIdAndMerchantPayKey(memberId, merchantPayKey)   // 없으면 NOT_FOUND
  → 주문 존재 검증
  → existsUnknownByOrderId / existsApprovedByOrderId 차단        // PG 호출 전 진입 차단
  → (USED) 기존 payment 재처리 / (RESERVED) create()             // create(): use()+save, 낙관적 락 충돌 시 차단
  → PG approve 호출
  → succeedApproval                                             // 최종 보루: uk_payment_approved_order_key (#230)
```

## 예외 및 실패 처리

- 동시 이중 use 진 쪽: `ObjectOptimisticLockingFailureException` → 차단용 `PaymentException`. PG 호출 전.
- 이미 성공 결제 있는 주문 진입: 차단용 `PaymentException`.
- 남의/없는 키: `PAYMENT_NOT_FOUND`.
- 정합성 최종 보장은 #230 경로(`uk_payment_approved_order_key` + 보상)가 그대로 담당한다.

## 테스트 포인트

- 같은 reservation·다른 pgPaymentId 동시 승인 → 한쪽만 payment 생성·PG 호출, 진 쪽 PG 호출 전 차단(동시성 테스트).
- 이미 성공 결제 있는 주문 새 승인 → 진입 차단(통합 테스트).
- 남의 키 승인 요청 → `PAYMENT_NOT_FOUND`(통합 테스트).
