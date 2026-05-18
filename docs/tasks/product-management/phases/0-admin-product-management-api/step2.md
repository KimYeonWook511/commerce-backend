# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/product-management/prd.md`
- `/docs/features/product-management/architecture.md`
- `/docs/features/product-management/adr.md`
- `/docs/features/product-management/api-spec.md`
- `/docs/features/product-management/db-schema.md`
- `/docs/features/product-management/phases/0-admin-product-management-api/step0.md`
- `/docs/features/product-management/phases/0-admin-product-management-api/step1.md`
- `/docs/PRD.md`
- `/docs/architecture.md`
- `/docs/api-spec.md`
- `/docs/db-schema.md`
- `/src/main/java/com/commerce/product/**`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

루트 문서를 `product-management` 구현 결과와 동기화하라.

- `docs/api-spec.md`에 관리자 상품 등록, 수정, 삭제 API를 추가하라.
- `docs/api-spec.md`의 공개 상품 조회 설명에 `ON_SALE`, `SOLD_OUT`만 노출하고 `STOPPED`/삭제 상품은 제외한다는 정책을 반영하라.
- `docs/db-schema.md`의 `tbl_product` 컬럼 목록에 `description`, `image_url`, `status`, `deleted_at`을 반영하라.
- `docs/architecture.md`의 `product` 도메인 책임에 관리자 상품 관리와 공개 조회 노출 정책을 반영하라.
- 필요하면 `docs/PRD.md`의 핵심 기능 설명을 현재 상태와 맞게 최소 수정하라.

## 수정 가능 경로

- `docs/features/product-management/**`
- `docs/PRD.md`
- `docs/architecture.md`
- `docs/api-spec.md`
- `docs/db-schema.md`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.product.*'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 루트 문서와 feature 문서의 API/DB 설명이 충돌하지 않는가?
   - 구현된 필드명과 문서 필드명이 일치하는가?
   - 구현되지 않은 재고 관리 기능을 문서에 완료된 것처럼 쓰지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 재고 수동 조정 또는 재고 이력을 구현 완료로 문서화하지 마라. 이유: 이번 feature 범위 밖이다.
- 실제 구현과 다른 API 경로를 문서화하지 마라.
- 기존 테스트를 깨뜨리지 마라
