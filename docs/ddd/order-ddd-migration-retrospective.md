# Order DDD Migration Retrospective

## 배경

이번 작업은 `stock` DDD 전환에서 확정한 기준을 `order` 도메인에 적용했다.

새 기능을 추가하거나 API/DB 계약을 바꾸는 작업이 아니므로 별도 feature PRD, API spec, DB schema 문서는 만들지 않았다. 기준 문서는 `DDD-MIGRATION-PLAN.md`와 `stock-ddd-migration-retrospective.md`로 두고, 작업 결과와 후속 기준만 이 문서에 남긴다.

## 이번 작업에서 확정한 기준

### DDD 구조 도입과 legacy 삭제는 분리한다

- `order.application`, `order.domain.repository`, `order.infrastructure`, `order.presentation` 구조를 먼저 추가한다.
- 기존 `order.service`, `order.controller`, `order.repository` 코드는 당장 삭제하지 않는다.
- legacy controller는 API bean으로 등록되지 않도록 둔다.
- legacy 삭제는 별도 커밋에서 production/test 참조를 검색해 정리한다.

### application service는 유스케이스 책임 단위로 분리한다

- `OrderCommandService` 하나에 주문 생성, 취소, 만료, 조회, 동시성 실험 흐름을 모두 두면 public API가 과하게 넓어진다.
- 주문 생성은 `OrderCreateService`, 주문 취소는 `OrderCancelService`, 주문 만료는 `OrderExpirationService`, 조회는 `OrderQueryService`로 분리했다.
- 동시성 전략 비교와 검증 목적의 생성 흐름은 production 주문 생성 흐름과 섞지 않고 `OrderConcurrencyService`로 분리했다.
- production 주문 생성의 public API는 `OrderCreateService.createOrder`로 두고, 비관적 락 정렬 같은 구현 세부사항은 내부 helper로 숨긴다.

### batch는 application service를 호출하는 실행 계층으로 둔다

- `OrderExpirationBatchConfig`는 Job, Step, Reader, Writer 구성을 담당한다.
- 주문 만료라는 비즈니스 유스케이스는 batch 패키지가 아니라 `OrderExpirationService`에 둔다.
- 이렇게 두면 batch 외의 scheduler, admin API, 수동 복구 흐름에서도 같은 application service를 재사용할 수 있다.

### repository 경계는 domain interface와 infrastructure JPA 구현으로 나눈다

- application 계층은 `order.domain.repository.OrderRepository`에 의존한다.
- JPA 구현은 `order.infrastructure.JpaOrderRepository`가 담당한다.
- 테스트 fixture가 JPA 전용 메서드를 써야 하는 경우에는 `JpaOrderRepository`를 직접 사용한다.

## 남겨둔 legacy 참조

- 기존 `order.service`, `order.controller`, `order.repository`, command/result/request 패키지는 삭제하지 않았다.
- 기존 service 테스트 파일 경로와 클래스명은 새 application service 책임 기준으로 갱신했다.
- `payment`와 `batch`의 주문 흐름 의존성은 새 application service와 domain repository로 전환했다.

## 다음 legacy 삭제 작업 체크리스트

- production 코드에서 legacy order 패키지 참조가 남았는지 확인한다.

```bash
rg "com\.commerce\.order\.(service|controller|repository)" src/main/java src/test/java
```

- legacy controller, service, repository, command, result, request 패키지를 제거한다.
- 테스트 fixture가 legacy repository를 쓰는 곳은 `JpaOrderRepository` 또는 새 테스트 helper로 정리한다.
- 전체 테스트를 실행한다.

```bash
./gradlew test
```

권장 커밋 메시지:

```text
refactor: order legacy 패키지를 정리한다
```

## OrderItem 경계 정리

- `orderitem`은 독립 유스케이스가 없고 `Order` aggregate 내부 구성요소로만 사용된다.
- 따라서 별도 최상위 도메인 패키지로 두지 않고 `order.domain.OrderItem`으로 이동한다.
- 테스트 fixture용 JPA repository는 domain repository port를 만들지 않고 `order.infrastructure.JpaOrderItemRepository`로 둔다.
- 이 작업은 legacy 삭제와 repository adapter 일관성 정리보다 먼저 진행하되, API와 DB 계약은 바꾸지 않는다.

## 다음 DDD 작업에 적용할 원칙

- 다음 대상은 `payment`로 진행한다.
- PG 연동 흐름과 내부 결제 상태 반영 흐름은 한 커밋에 과하게 섞지 않는다.
- provider client와 application service 경계를 먼저 구분하고, legacy 삭제는 별도 작업으로 분리한다.
