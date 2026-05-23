# Step 5: product-domain-logging

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/logging-conventions.md`
- `src/main/java/com/commerce/product/application/AdminProductService.java`

## 작업

### `AdminProductService` — 상품 CRUD INFO

파일: `src/main/java/com/commerce/product/application/AdminProductService.java`

- 클래스 상단에 `@Slf4j` 부착
- `createProduct(AdminProductCreateCommand command)`: `productRepository.save(product)` 결과를 분리:
  ```java
  Product savedProduct = productRepository.save(product);
  log.info("상품 생성 productId={} name={}", savedProduct.getId(), savedProduct.getName());
  return AdminProductResult.from(savedProduct);
  ```
- `updateProduct(AdminProductUpdateCommand command)`: `product.update(...)` 호출 직후:
  ```java
  log.info("상품 수정 productId={}", product.getId());
  ```
- `deleteProduct(Long productId)`: `product.softDelete()` 호출 직후:
  ```java
  log.info("상품 삭제 productId={}", product.getId());
  ```
- private `findNotDeletedProduct`는 헬퍼 — 로그 추가하지 않음

## 수정 가능 경로

- `src/main/java/com/commerce/product/application/AdminProductService.java`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 실행 → 기존 테스트 모두 PASS
2. `AdminProductService`에 `@Slf4j` 부착 확인
3. 3개 INFO 로그 메시지가 사전 시그니처와 정확히 일치
4. `ProductQueryService`는 손대지 않았는지 확인 (제외 대상)
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `ProductQueryService`에 `@Slf4j` 부착 금지. 이유: 조회 서비스는 §3 정합성 위반.
- private 헬퍼(`findNotDeletedProduct`)에 INFO 추가 금지. 이유: 비공개 메서드는 유스케이스 진입점이 아님.
- 비즈니스 로직 변경 금지.
- 기존 테스트를 깨뜨리지 마라.
