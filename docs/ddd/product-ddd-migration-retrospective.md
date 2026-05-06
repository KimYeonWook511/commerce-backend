# Product DDD Migration Retrospective

## 배경

이번 작업은 `stock`, `order` DDD 전환에서 확정한 기준을 `product` 도메인에 적용했다.

`product`는 주문 생성, 재고 생성, 상품 공개 조회의 기반 도메인으로 사용되므로 `payment` 전환 전에 repository 경계를 먼저 정리했다. API와 DB 계약은 바꾸지 않았다.

## 이번 작업에서 확정한 기준

### application service는 관리자 command와 공개 query를 분리한다

- 관리자 상품 등록, 수정, 삭제는 `AdminProductService`에 둔다.
- 공개 상품 목록, 상세 조회는 `ProductQueryService`에 둔다.
- 기존 `ProductService` 하나에 command/query 흐름을 계속 두지 않는다.

### repository 경계는 adapter로 분리한다

- application 계층은 `product.domain.repository.ProductRepository`에 의존한다.
- Spring Data JPA repository는 `product.infrastructure.JpaProductRepository`에 둔다.
- domain repository 구현은 `ProductRepositoryAdapter`가 담당한다.
- domain repository 메서드명은 Spring Data JPA 파생 쿼리명이 아니라 `findVisibleProduct`, `findNotDeletedProduct`처럼 application 의도가 드러나게 둔다.
- JPA 조회 조건은 `JpaProductRepository`의 `@Query`로 명시한다.
- `JpaRepository`와 domain repository를 직접 함께 상속하면 `save` 시그니처를 Spring Data JPA의 generic method에 맞춰야 하므로 Product에서는 adapter 방식으로 분리한다.

### 테스트 fixture는 JPA repository를 직접 사용할 수 있다

- application service 테스트는 domain repository를 mock으로 사용한다.
- `@DataJpaTest`, 통합 테스트, 동시성 테스트의 fixture 정리처럼 JPA 전용 메서드가 필요한 경우 `JpaProductRepository`를 직접 사용한다.

## 남겨둔 legacy 참조

- 기존 `product.service`, `product.controller`, `product.repository`, command/result/request 패키지는 삭제하지 않았다.
- legacy `ProductService`와 legacy product controller는 Spring bean으로 등록되지 않도록 했다.
- legacy repository는 후속 legacy 삭제 작업에서 production/test 참조를 확인한 뒤 제거한다.

## 다음 legacy 삭제 작업 체크리스트

- production 코드에서 legacy product 패키지 참조가 남았는지 확인한다.

```bash
rg "com\.commerce\.product\.(service|controller|repository)" src/main/java src/test/java
```

- legacy controller, service, repository, command, result, request 패키지를 제거한다.
- 테스트 fixture가 legacy repository를 쓰는 곳은 `JpaProductRepository` 또는 새 테스트 helper로 정리한다.
- 전체 테스트를 실행한다.

```bash
./gradlew test
```

권장 커밋 메시지:

```text
refactor: product legacy 패키지를 정리한다
```

## 다음 DDD 작업에 적용할 원칙

- 다음 후보는 `payment` DDD 마이그레이션이다.
- repository port는 의도 기반 메서드명으로 두고, JPA 구현체 또는 adapter에서 영속성 조건을 분리한다.
- 새 DDD 구조 도입과 legacy 삭제는 계속 별도 작업으로 분리한다.
