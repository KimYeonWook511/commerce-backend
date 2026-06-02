# 태스크 API 스펙

## 개요

- 본 태스크는 API 응답 계약을 변경하지 않는다.
- 변경 대상은 Stock / StockHistory 도메인의 JPA 매핑 / application orchestration / test fixture 이며, controller / request / response 형식은 그대로 유지된다.

## 엔드포인트

기존 엔드포인트 그대로 유지. 본 태스크에서 형식 변경 없음.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/admin/products/{productId}/stock` | 관리자 초기 재고 생성 |
| `POST` | `/admin/products/{productId}/stock/increase` | 관리자 재고 증가 |
| `POST` | `/admin/products/{productId}/stock/decrease` | 관리자 재고 감소 |
| `GET` | `/admin/products/{productId}/stock/histories` | 관리자 재고 변경 이력 조회 |

## 요청

기존 요청 계약 그대로 유지.

- `POST /admin/products/{productId}/stock` — `AdminStockCreateRequest`
- `POST /admin/products/{productId}/stock/increase` / `decrease` — `AdminStockAdjustRequest`
- `GET /admin/products/{productId}/stock/histories` — path productId 만

## 응답

기존 응답 계약 그대로 유지.

### `AdminStockResult` (재고 생성 / 증감 응답)

| 필드 | 타입 | 비고 |
|---|---|---|
| `productId` | `Long` | 기존 응답 필드 유지. 내부적으로 `Stock.productId` 필드를 직접 사용한다. |
| `stockId` | `Long` | 기존 |
| `quantity` | `int` | 기존 |

### `StockHistoryResult` (재고 변경 이력 응답)

| 필드 | 타입 | 비고 |
|---|---|---|
| `historyId` | `Long` | 기존 |
| `productId` | `Long` | 기존 응답 필드 유지. application 에서 path productId 를 외부 주입한다. |
| `stockId` | `Long` | 기존 |
| `quantityChange` | `int` | 기존 |
| `reason` | `StockAdjustmentReason` | 기존 |
| `adminMemberId` | `Long` | 기존 |
| `createdAt` | `LocalDateTime` | 기존 |

## 검증 규칙

- 기존 검증 규칙 유지. 본 태스크에서 검증 규칙 변경 없음.

## 비고

- `AdminStockController` 의 모든 엔드포인트는 `@RequireRole(MemberRole.ROLE_ADMIN)` 권한 검증 유지.
- 응답 필드 `productId` 가 path productId 와 동일한 echo 인지 정비할지는 본 태스크 범위가 아니며 별도 트랙으로 분리한다.
