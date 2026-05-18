# 기능 아키텍처

## 개요

- `stock-management`는 기존 `stock` 도메인에 관리자 command API와 재고 변경 이력 모델을 추가한다.
- 주문 경로의 재고 차감/복구 기능은 유지하고, 관리자 수동 조정 기능을 별도 API로 제공한다.

## 변경 대상

- `stock` 도메인
  - 재고 변경 사유 enum 추가
  - 재고 변경 이력 엔티티와 repository 추가
  - 초기 재고 생성, 증가, 감소, 이력 조회 service/API 추가
- `product` 도메인
  - 삭제되지 않은 상품인지 확인하기 위해 기존 `ProductRepository.findByIdAndDeletedAtIsNull`를 사용한다.
- `auth` 권한 경계
  - 관리자 API에 `@RequireRole(MemberRole.ROLE_ADMIN)` 적용
  - 이력 변경 주체 기록을 위해 `@AuthenticatedMemberId`로 관리자 member id를 받는다.
- 문서
  - 루트 API 스펙, DB 스키마, 아키텍처 문서 동기화

## 설계 방향

- 기존 `Controller -> Service -> Domain/Repository` 계층을 유지한다.
- 재고 등록과 수동 조정 책임은 `StockService`에 둔다.
- 재고 변경 이력은 `StockHistory` 엔티티로 분리한다.
- 관리자 수동 증가/감소는 기존 비관적 락 조회 흐름을 사용해 동시 수정 정합성을 유지한다.
- 변경 수량은 이력에서 부호 있는 값으로 표현한다.

## 데이터 흐름

- 관리자 초기 재고 생성
  - `AdminStockController` -> `StockService` -> `ProductRepository`/`StockRepository`/`StockHistoryRepository`
  - 삭제되지 않은 상품을 확인하고 기존 재고가 없는 경우 `Stock`을 생성한 뒤 생성 이력을 저장한다.
- 관리자 재고 증가
  - `AdminStockController` -> `StockService` -> `StockRepository`/`StockHistoryRepository`
  - 비관적 락으로 재고를 조회해 증가시키고 양수 변경 이력을 저장한다.
- 관리자 재고 감소
  - `AdminStockController` -> `StockService` -> `StockRepository`/`StockHistoryRepository`
  - 비관적 락으로 재고를 조회해 감소시키고 음수 변경 이력을 저장한다.
- 관리자 이력 조회
  - `AdminStockController` -> `StockService` -> `StockHistoryRepository`
  - 상품 id 기준으로 이력을 최신순 조회해 응답한다.

## 예외 및 실패 처리

- 존재하지 않거나 삭제된 상품의 초기 재고 생성은 `PRODUCT_NOT_FOUND`로 처리한다.
- 이미 재고가 존재하는 상품의 초기 재고 생성은 신규 stock 예외 코드로 처리한다.
- 존재하지 않는 재고의 증가/감소/이력 조회는 `STOCK_NOT_FOUND`로 처리한다.
- 재고 감소 수량이 현재 수량보다 크면 기존 `OUT_OF_STOCK`을 사용한다.
- 요청 값 검증 실패는 기존 공통 검증 오류 응답을 따른다.
- 관리자 권한이 없으면 기존 auth 권한 오류 응답을 따른다.

## 테스트 포인트

- 초기 재고 생성 시 `Stock`과 생성 이력이 함께 저장되는지
- 이미 재고가 있는 상품은 초기 재고 생성이 실패하는지
- 재고 증가/감소 시 수량과 이력 변경 수량 부호가 맞는지
- 재고 감소 초과 요청이 실패하는지
- 관리자 API 권한 검증
- request validation과 공통 응답 형식
- 상품별 이력 조회 최신순 정렬
