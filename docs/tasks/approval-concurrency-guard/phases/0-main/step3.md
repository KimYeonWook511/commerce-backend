# Step 3: reservation-lookup-unification

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/approval-concurrency-guard/prd.md`
- `/docs/tasks/approval-concurrency-guard/architecture.md`
- `/docs/tasks/approval-concurrency-guard/adr.md`
- `/docs/tasks/approval-concurrency-guard/api-spec.md`

작업 대상 코드와 테스트:

- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentReservationRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/infrastructure/JpaPaymentReservationRepository.java`
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayApprovalServiceTest.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java`

## 작업

reservation 조회를 (memberId, merchantPayKey)로 단일화해 member 검증 분기를 흡수하고, 남의/없는 키를 `NOT_FOUND`로 응답한다(키 존재 비노출). 설계 근거는 ADR-L3이다.

1. **단일 조회 메서드 추가**: `PaymentReservationRepository`
   - `Optional<PaymentReservation> findByMemberIdAndMerchantPayKey(Long memberId, String merchantPayKey)`를 추가한다.
   - 어댑터·JPA 레포지토리에 구현한다.

2. **진입 조회 단일화**: `NaverPayApprovalService.approve()`
   - 기존 `findByMerchantPayKey(merchantPayKey)` + `memberId` 불일치 분기(`PAYMENT_MEMBER_MISMATCH`)를 `findByMemberIdAndMerchantPayKey(memberId, merchantPayKey)` 단일 조회로 대체한다. 조회 실패는 `PAYMENT_NOT_FOUND`로 던진다.

3. **에러코드 정리**: `PaymentErrorCode`
   - `PAYMENT_MEMBER_MISMATCH`의 다른 사용처가 없음을 확인한 뒤 제거한다.

4. **기존 `findByMerchantPayKey` 처리**: 다른 사용처가 없으면 제거하고, 다른 경로가 사용 중이면 유지한다(아래 검증 절차의 사용처 탐색 결과를 따른다).

5. **테스트 갱신**: 기존 "다른 회원이 승인 요청 시 `PAYMENT_MEMBER_MISMATCH`" 테스트(`NaverPayApprovalServiceTest`, `NaverPayServiceIntegrationTest`)를 "남의/없는 키 → `PAYMENT_NOT_FOUND`"로 갱신한다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

## 검증 절차

1. shared 계약(레포지토리 메서드·에러코드) 변경이므로 사용처를 먼저 탐색한다.
   - `rg "findByMerchantPayKey" src/main src/test`
   - `rg "PAYMENT_MEMBER_MISMATCH" src/main src/test`
2. 위 Acceptance Criteria 커맨드를 실행한다.
3. 아래를 확인한다.
   - 남의 키 승인 요청이 `PAYMENT_NOT_FOUND`로 응답되는가?
   - `PAYMENT_MEMBER_MISMATCH` 잔존 참조가 없는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 사용처가 있는데 `findByMerchantPayKey`를 제거하지 마라. 이유: 사용처 탐색 없이 지우면 다른 경로가 깨진다.
- 조회 단일화를 핑계로 USED/RESERVED 분기, UNKNOWN/APPROVED 진입 차단 등 다른 승인 로직을 변경하지 마라. 이유: 이 step의 목적은 조회·member 검증 정리에 한정된다.
- 기존 테스트를 깨뜨리지 마라.
