# Payment DDD Migration Retrospective

## 배경

이번 작업은 `stock`, `order`, `product` DDD 전환에서 확정한 기준을 `payment` core 흐름에 적용했다.

결제는 외부 PG 연동과 내부 결제 상태 반영이 함께 섞이기 쉬운 도메인이므로, 이번 작업에서는 `Payment`와 `PaymentAttempt` 중심의 core application 흐름만 먼저 전환했다. API와 DB 계약은 바꾸지 않았다.

## 이번 작업에서 확정한 기준

### payment core와 provider 연동 전환은 분리한다

- 결제 준비, 결제 완료 반영, 결제 시도 이력 관리는 `payment.application`에 둔다.
- NaverPay 승인 처리 흐름은 기존 `payment.naverpay` 패키지에 남겨두되, 내부 결제 상태 변경은 새 application service를 호출한다.
- provider client, provider 응답 코드 매핑, provider controller 구조 정리는 다음 작업으로 분리한다.

### application service는 결제 흐름 책임 단위로 분리한다

- 결제 준비는 `PaymentReadyService`에 둔다.
- 결제 완료 반영과 완료 결제 조회는 `PaymentApprovalService`에 둔다.
- 승인/취소 시도 이력 생성과 상태 변경은 `PaymentAttemptService`에 둔다.
- 기존 `PaymentService` 하나에 준비와 승인 완료 반영을 계속 두지 않는다.

### repository 경계는 adapter로 분리한다

- application 계층은 `payment.domain.repository.PaymentRepository`, `PaymentAttemptRepository`에 의존한다.
- Spring Data JPA repository는 `payment.infrastructure.JpaPaymentRepository`, `JpaPaymentAttemptRepository`에 둔다.
- domain repository 구현은 `PaymentRepositoryAdapter`, `PaymentAttemptRepositoryAdapter`가 담당한다.
- application service 테스트는 domain repository를 mock으로 사용한다.
- JPA 전용 fixture 정리나 조회가 필요한 통합 테스트와 동시성 테스트는 `PaymentPersistenceTestSupport`를 사용한다.
- `PaymentPersistenceTestSupport` 내부에서만 infrastructure repository를 직접 사용한다.

## 남겨둔 legacy 참조

- 기존 `payment.service`, `payment.controller`, `payment.repository`, command/result/request 패키지는 삭제하지 않았다.
- legacy `PaymentService`, `PaymentAttemptService`, legacy payment controller는 Spring bean으로 등록되지 않도록 했다.
- NaverPay 패키지는 새 application service를 참조하도록 최소 전환했지만, provider 경계 자체는 아직 DDD 구조로 옮기지 않았다.

## 다음 legacy 삭제 작업 체크리스트

- production 코드에서 legacy payment 패키지 참조가 남았는지 확인한다.

```bash
rg "com\.commerce\.payment\.(service|controller|repository)" src/main/java src/test/java
```

- legacy controller, service, repository, command, result, request 패키지를 제거한다.
- 테스트 fixture가 legacy repository를 쓰는 곳은 `JpaPaymentRepository` 또는 `JpaPaymentAttemptRepository`로 정리한다.
- 전체 테스트를 실행한다.

```bash
./gradlew test
```

권장 커밋 메시지:

```text
refactor: payment legacy 패키지를 정리한다
```

## 다음 DDD 작업에 적용할 원칙

- 다음 후보는 `payment.naverpay` provider 경계 정리다.
- PG client 호출과 내부 결제 상태 반영은 같은 service에 계속 섞지 않는다.
- provider별 응답 코드 매핑은 infrastructure/provider 계층 쪽으로 이동할 수 있는지 검토한다.
- legacy 삭제는 DDD 구조 도입 커밋과 계속 분리한다.
