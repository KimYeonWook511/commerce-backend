# Step 5: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/adr.md`
- `/docs/tasks/cart/api-spec.md`
- `/docs/tasks/cart/db-schema.md`
- `/docs/prd.md`
- `/docs/architecture.md`
- `/docs/api-spec.md`
- `/docs/db-schema.md`
- `/docs/adr.md`
- `/docs/tasks/cart/phases/0-cart/step1.md` ~ `/step4.md` (구현된 산출물)

## 작업

루트 `docs/` 문서를 phase 산출물에 맞게 동기화한다.

### `docs/prd.md`

- `MVP 제외 사항`(`docs/prd.md:16` 근처)에서 `장바구니` 줄을 삭제한다.
- 핵심 기능 섹션에 장바구니가 포함되어야 한다면 명시적으로 추가한다(필요 시).

### `docs/architecture.md`

- 패키지 구조 트리에 `cart/`를 추가
  ```
  ├── cart/              # 장바구니 항목 추가·변경·삭제·조회, 주문 시 항목 제거 연동
  ```
- "도메인별 주요 서비스" 표에 `cart` 행 추가
  - `AddCartItemService`, `GetMyCartService`, `UpdateCartItemQuantityService`, `RemoveCartItemService`
- "데이터 흐름" 섹션에 다음 흐름 추가
  - 장바구니 담기/조회/수정/삭제 흐름
  - 주문 생성 흐름에 `CartItemRemover` 호출 단계 추가
- "도메인 이벤트 INFO 로그 적용 범위" 표 갱신
  - Cart 도메인 추가: `AddCartItemService`, `UpdateCartItemQuantityService`, `RemoveCartItemService` (3개 컴포넌트)
  - 14개 → 17개로 갱신
- "저장소 및 인프라 의존성" 등 다른 섹션은 변경 없음

### `docs/api-spec.md`

- cart API 4종을 추가한다.
  - `POST /cart/items` (장바구니 담기)
  - `GET /cart` (내 장바구니 조회)
  - `PATCH /cart/items/{productId}` (수량 변경)
  - `DELETE /cart/items/{productId}` (항목 삭제)
- 기존 형식(요청/응답 예시, 상태 코드, 인증 요건)을 그대로 따른다.

### `docs/db-schema.md`

- `tbl_cart_item` 테이블을 추가한다.
- 컬럼, UNIQUE 제약(`uk_cart_item_member_product`), 인덱스 정책 명시
- FK 미사용 정책 명시(ADR-020 참조)

### `docs/adr.md`

- 마지막(현재 ADR-019) 다음에 **ADR-020**을 추가한다.
- 제목: `ADR-020: 신규 도메인의 cross-aggregate 참조는 ID로 한다`
- 내용 골격
  - **결정**: 본 phase의 cart를 기점으로 신규 도메인은 다른 aggregate를 `Long` ID로만 참조한다. `@ManyToOne`, `@JoinColumn`, `@OneToOne`(cross-aggregate) 사용 금지.
  - **배경**: 기존 도메인은 ManyToOne 객체 참조를 사용했으나 application 계층이 ID로 다루는 이중 표현, N+1 회피 부담, 도메인 결합도 증가, DDD "다른 aggregate는 ID로만 참조" 원칙 위반 등의 단점이 있었다.
  - **결정 근거**: DDD 정통(Eric Evans) Reference Other Aggregates Only By Identity. 도메인 결합 감소, JPA lifecycle 함정 회피, 단위 테스트 단순, 마이크로서비스 분리 친화적.
  - **트레이드오프**: DB 참조 무결성을 application과 unique 제약·삭제 순서 정책으로 책임진다. 기존 Order/Stock/StockHistory 등 ManyToOne 마이그레이션은 별도 트랙으로 분리한다.
  - **적용 범위**: 본 ADR 이후 신설되는 모든 cross-aggregate 참조에 적용. 같은 aggregate 내 부모-자식(`Order ↔ OrderItem` 같은 collection root)은 본 정책 대상 아님.

## 수정 가능 경로

- `docs/prd.md`
- `docs/architecture.md`
- `docs/api-spec.md`
- `docs/db-schema.md`
- `docs/adr.md`
- `docs/tasks/cart/**`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.cart.*'
```

(문서만 변경되어도 회귀 안전 확인.)

추가 검증:

```bash
rg -n "장바구니" docs/prd.md
rg -n "^### ADR-020" docs/adr.md
rg -n "cart" docs/architecture.md docs/api-spec.md docs/db-schema.md
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/prd.md` MVP 제외 사항에서 "장바구니"가 제거됐는가?
   - `docs/adr.md`에 ADR-020이 마지막 ADR-019 다음에 추가됐는가?
   - `docs/architecture.md`, `docs/api-spec.md`, `docs/db-schema.md`가 cart 도입을 반영하는가?
   - workspace `docs/` 하위 문서(`api-contract.md`, `progress.md`)는 수정되지 않았는가? (backend 세션 범위 밖)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- workspace `docs/` 하위 문서(`commerce-workspace/docs/api-contract.md`, `progress.md`)를 수정하지 마라. 이유: Frontend 세션의 책임이다.
- `docs/exception-strategy.md`, `docs/logging-conventions.md`, `docs/testing-conventions.md`를 수정하지 마라. 이유: 본 phase에서 정책 변경이 없다.
- ADR-020을 ADR-019 사이에 끼워 넣지 마라. 이유: ADR 번호는 추가 순서로 누적되어야 한다(중간 삽입 시 번호 충돌 위험).
- 기존 다른 ADR의 내용을 수정하지 마라. 이유: ADR은 시간 순서대로 누적되는 역사 기록이다.
- 기존 테스트를 깨뜨리지 마라.
