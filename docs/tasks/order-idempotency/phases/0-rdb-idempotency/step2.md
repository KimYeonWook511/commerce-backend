# Step 2: docs-sync

## 읽어야 할 파일

먼저 아래 파일들을 읽고 변경된 내용을 파악하라:

- `docs/features/order-idempotency/prd.md`
- `docs/features/order-idempotency/architecture.md`
- `docs/features/order-idempotency/adr.md`
- `docs/features/order-idempotency/db-schema.md`
- `docs/commit-conventions.md`
- `docs/db-schema.md`
- `docs/ADR.md`

이전 step에서 생성/수정된 파일:
- `src/main/java/com/commerce/order/domain/Order.java`
- `src/main/java/com/commerce/order/application/OrderCreateService.java`
- `src/main/java/com/commerce/order/application/OrderCreateProcessor.java`
- `src/main/java/com/commerce/order/infrastructure/RedisOrderIdempotencyStore.java`

## 작업

step0, step1에서 변경된 내용을 루트 문서에 반영한다.

### 1. `docs/db-schema.md` 업데이트

`tbl_order` 스키마에 `idempotency_key` 컬럼과 `uk_order_member_idempotency` unique 인덱스를 추가한다.

### 2. `docs/ADR.md` 업데이트

ADR-002의 캐싱 동작 설명을 실제 구현 내용에 맞게 수정한다.

현재:
> 캐시 MISS 시 도메인 로직 실행 → DB 저장 → Spring ApplicationEvent 발행 → AFTER_COMMIT 핸들러에서 결과 캐싱 순서로 처리한다

이 내용은 이미 구현된 상태이므로 구현 완료를 반영하고, RDB unique 제약이 추가되었음을 명시한다.

## 수정 가능 경로

- `docs/db-schema.md`
- `docs/ADR.md`
- `docs/features/order-idempotency/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. `docs/db-schema.md`에 `idempotency_key` 컬럼이 반영됐는지 확인한다.
3. `docs/ADR.md`의 ADR-002가 실제 구현과 일치하는지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 소스 코드를 수정하지 마라. 이유: 이 step은 문서 동기화만 담당한다.
- `docs/features/order-idempotency/` 하위 회고 문서를 소급 수정하지 마라.
