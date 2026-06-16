# Step 2: rename-product-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 맥락을 파악하라:

- `docs/tasks/application-layer-rename/prd.md`
- `src/main/java/com/commerce/product/application/service/ProductQueryService.java`
- `src/main/java/com/commerce/product/application/service/AdminProductService.java`

## 작업

product 도메인 Service 클래스를 ADR-054 컨벤션으로 리네임·분리한다.
동작 변경 없이 파일명·클래스명·주입 변수명·테스트명만 바꾼다.

### 리네임 목록

| 현재 | 변경 후 | 처리 방식 |
|---|---|---|
| `ProductQueryService` | `GetProductService` | 리네임 |
| `AdminProductService.createProduct(...)` | `AdminCreateProductService` | 분리 |
| `AdminProductService.updateProduct(...)` | `AdminUpdateProductService` | 분리 |
| `AdminProductService.deleteProduct(...)` | `AdminDeleteProductService` | 분리 |

### 절차

1. 각 대상 클래스를 사용하는 모든 파일을 확인한다.

   ```bash
   grep -rl "ProductQueryService\|AdminProductService" src/
   ```

2. **ProductQueryService 리네임**: `ProductQueryService.java` → `GetProductService.java`. 클래스명 변경 후 기존 파일 삭제.

3. **AdminProductService 분리**: `createProduct`, `updateProduct`, `deleteProduct` 메서드를 각각 별도 파일로 분리한다.
   - `AdminCreateProductService.java` — `createProduct` 메서드와 그에 필요한 의존성만 포함
   - `AdminUpdateProductService.java` — `updateProduct` 메서드와 의존성
   - `AdminDeleteProductService.java` — `deleteProduct` 메서드와 의존성
   - 기존 `AdminProductService.java` 삭제

4. 모든 참조 파일(Controller 등)에서 업데이트한다:
   - `ProductQueryService` → `GetProductService` (타입·변수명)
   - `AdminProductService` 단일 주입 → `AdminCreateProductService`, `AdminUpdateProductService`, `AdminDeleteProductService` 세 개 별도 주입
   - 변수명은 새 클래스명의 camelCase를 따른다

5. 테스트 파일에서 클래스명·메서드명·`@DisplayName`을 새 이름 기준으로 갱신한다.

### 금지사항

- 각 분리 클래스에서 메서드 내부 로직을 변경하지 마라. 이유: 동작 불변 원칙.
- 분리 시 메서드를 다른 클래스로 합치지 마라. 이유: 1 클래스 1 행위 원칙.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - `ProductQueryService`, `AdminProductService` 문자열이 `src/` 하위에 남아 있지 않은가.
     ```bash
     grep -r "ProductQueryService\|AdminProductService" src/
     ```
   - 분리된 3개 클래스 각각이 정확히 1개의 public 메서드만 보유하는가.
