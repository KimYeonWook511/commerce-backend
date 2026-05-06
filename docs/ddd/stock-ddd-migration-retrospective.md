# Stock DDD Migration Retrospective

## 배경

이번 작업은 기존 `stock` 코드를 한 번에 삭제하거나 이동하지 않고, DDD 구조에 맞는 새 패키지를 추가한 뒤 핵심 호출 흐름을 점진적으로 전환하는 방식으로 진행했다.

목표는 legacy 코드를 즉시 정리하는 것이 아니라, 현재 동작을 유지하면서 `application`, `domain.repository`, `infrastructure`, `presentation` 구조를 먼저 도입하는 것이었다.

## 이번 작업에서 확정한 기준

### DDD 리팩터링과 legacy 삭제는 분리한다

- DDD 적용 커밋에는 새 구조 추가, 핵심 흐름 전환, 테스트 정리만 담는다.
- legacy 패키지 이동이나 삭제는 별도 작업으로 분리한다.
- legacy 참조가 남아 있어도 현재 동작에 문제가 없으면 이번 DDD 커밋에서 억지로 수정하지 않는다.
- 나중에 legacy를 삭제할 때 production 참조와 test fixture 참조를 함께 정리한다.

이 기준을 둔 이유는 DDD 리팩터링 의도와 legacy 제거 의도가 한 커밋에 섞이면 리뷰하기 어렵고, 변경량도 불필요하게 커지기 때문이다.

### legacy 코드는 삭제 예정 코드로 둔다

- 기존 `stock.service`, `stock.controller`, `stock.repository` 하위 코드는 당장 삭제하지 않는다.
- legacy `StockService`의 `@Service`만 따로 제거하지 않는다.
- legacy controller가 API bean으로 등록되지 않도록 한 현재 상태는 유지한다.
- 삭제 시점에는 legacy 패키지 전체를 제거하고, 남은 참조가 없는지 검색으로 검증한다.

삭제 전 확인 명령:

```bash
rg "com\.commerce\.stock\.(service|controller|repository)" src/main/java src/test/java
```

## 네이밍 시행착오

### `ApplicationService` 접미사

처음에는 application 계층을 드러내기 위해 `ApplicationService` 접미사를 고려했다.

하지만 패키지 경로가 이미 계층을 표현하므로 클래스명에서 다시 반복할 필요는 없다고 판단했다. 따라서 application 패키지의 서비스는 유스케이스 책임 중심으로 이름을 짓는다.

예시:

```text
stock.application.AdminStockService
stock.application.StockInventoryService
stock.application.StockConcurrencyService
```

### `OrderStockService`

`OrderStockService`는 주문 흐름에서 호출된다는 점은 드러나지만, 서비스의 실제 책임이 재고 변경이라는 점은 애매했다.

최종적으로 주문 관점보다 재고 책임을 우선해 `StockInventoryService`로 정리했다.

### `decreaseWithPessimisticLock`

`decreaseWithPessimisticLock`처럼 구현 전략을 public application API 이름에 드러내면 이름이 길어지고 호출부가 구현 세부사항에 묶인다.

application service의 public method는 `decrease`, `increase`, `decreaseBatch`처럼 유스케이스 중심으로 유지하고, 비관적 락 사용 이유는 서비스 내부 주석으로 남긴다.

## 테스트 정리에서 얻은 기준

기존에는 하나의 `StockServiceTest`가 관리자 재고, 주문 재고 차감, 동시성 전략 테스트를 함께 다뤘다.

DDD 구조로 나누면서 테스트도 책임별로 분리했다.

```text
AdminStockServiceTest
StockInventoryServiceTest
StockConcurrencyServiceTest
```

이렇게 나누면 어떤 application service의 계약이 깨졌는지 더 빨리 파악할 수 있고, 후속 legacy 삭제 작업에서도 영향 범위를 좁혀 볼 수 있다.

## 남겨둔 legacy 참조

현재 남아 있는 legacy 참조는 의도적으로 즉시 수정하지 않는다.

- `ProductService`의 legacy `StockRepository` 참조
- 통합 테스트와 동시성 테스트의 legacy repository fixture 참조
- legacy `StockService`, command/result/request DTO
- legacy repository interface

이 참조들은 당장 동작 문제를 만들지 않으며, 나중에 legacy 패키지를 삭제하는 단계에서 한 번에 정리하는 편이 커밋 목적이 명확하다.

## 다음 legacy 삭제 작업 체크리스트

- `ProductService`의 재고 조회 참조를 새 repository 구조로 전환한다.
- 테스트 fixture에서 legacy repository가 필요한 곳을 새 repository 또는 infrastructure repository로 바꾼다.
- legacy controller, service, repository, command, result, request 패키지를 제거한다.
- 아래 검색 결과가 의도한 outbox 패키지 등을 제외하고 비어 있는지 확인한다.

```bash
rg "com\.commerce\.stock\.(service|controller|repository)" src/main/java src/test/java
```

- 전체 테스트를 실행한다.

```bash
./gradlew test
```

- legacy 삭제는 DDD 리팩터링 커밋과 분리해 별도 커밋으로 남긴다.

권장 커밋 메시지:

```text
refactor: stock legacy 패키지를 정리한다
```

## 다음 DDD 작업에 적용할 원칙

- 새 구조 도입과 legacy 삭제를 같은 커밋에 섞지 않는다.
- application service 이름은 계층명이 아니라 책임을 기준으로 짓는다.
- 구현 전략은 public method 이름보다 내부 구현과 주석으로 표현한다.
- 테스트는 기존 서비스 단위가 아니라 새 application service 책임 단위로 나눈다.
- legacy 삭제 가능성은 검색 명령과 체크리스트로 관리한다.
