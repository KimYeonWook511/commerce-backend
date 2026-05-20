# Step 3: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/features/product-query/prd.md`
- `/docs/features/product-query/architecture.md`
- `/docs/features/product-query/adr.md`
- `/docs/features/product-query/api-spec.md`
- `/docs/features/product-query/db-schema.md`
- `/docs/api-spec.md`
- `/docs/architecture.md`
- `/docs/db-schema.md`

이전 step에서 만들어진 코드와 feature 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

step1 구현 결과를 기준으로 루트 문서를 동기화하라.

- `docs/api-spec.md`에 `GET /products`, `GET /products/{productId}` 공개 조회 스펙을 추가하라.
- `docs/architecture.md`에 `product` 도메인이 공개 조회 API를 제공한다는 설명을 반영하라.
- 구현 결과와 어긋나는 문서 표현이 없게 정리하라.

## 수정 가능 경로

- `docs/features/product-query/**`
- `docs/api-spec.md`
- `docs/architecture.md`

## Acceptance Criteria

```bash
./gradlew test --tests 'com.commerce.product.service.ProductServiceTest' --tests 'com.commerce.product.controller.ProductControllerTest'
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - feature 문서와 루트 문서의 API 계약이 일치하는가?
   - architecture.md 설명이 실제 구현과 충돌하지 않는가?
   - 상위 작업 규칙을 위반하지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 구현과 다른 내용을 루트 문서에 적지 마라. 이유: 기준 문서 오염을 방지해야 한다.
- 새로운 기능 범위를 문서에 임의로 추가하지 마라. 이유: 이번 범위는 조회 API로 한정되어 있다.
- 기존 테스트를 깨뜨리지 마라
