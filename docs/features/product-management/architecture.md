# 기능 아키텍처

## 개요

- `product-management`는 기존 `product` 도메인에 관리자 command API와 상품 운영 상태를 추가한다.
- 공개 상품 조회는 기존 `ProductController` 경로를 유지하되 노출 가능한 상품만 반환하도록 repository/service 조건을 조정한다.

## 변경 대상

- `product` 도메인
  - 상품 운영 필드와 상태 enum 추가
  - 등록, 수정, soft delete 도메인 동작 추가
  - 관리자 command service/API 추가
- `auth` 권한 경계
  - 관리자 API에 `@RequireRole(MemberRole.ROLE_ADMIN)` 적용
- 문서
  - 루트 API 스펙, DB 스키마, 아키텍처 문서 동기화

## 설계 방향

- 기존 `Controller -> Service -> Domain/Repository` 계층을 유지한다.
- 공개 조회와 관리자 command 책임은 service 메서드와 DTO를 분리해 표현한다.
- 상품 삭제는 `deletedAt` 기반 soft delete로 처리한다.
- 판매 상태는 `ProductStatus` enum으로 관리한다.
- 재고 생성과 재고 조정은 이번 feature에서 다루지 않는다.

## 데이터 흐름

- 관리자 상품 등록
  - `AdminProductController` -> `ProductService` -> `ProductRepository`
  - 요청 값을 검증하고 `Product`를 생성해 저장한다.
- 관리자 상품 수정
  - `AdminProductController` -> `ProductService` -> `ProductRepository`
  - 상품을 조회한 뒤 도메인 메서드로 변경 가능한 필드를 갱신한다.
- 관리자 상품 삭제
  - `AdminProductController` -> `ProductService` -> `ProductRepository`
  - 상품을 조회한 뒤 삭제 시각을 기록한다.
- 공개 상품 조회
  - `ProductController` -> `ProductService` -> `ProductRepository`/`StockRepository`
  - 삭제되지 않았고 상태가 `ON_SALE` 또는 `SOLD_OUT`인 상품만 응답한다.

## 예외 및 실패 처리

- 존재하지 않거나 삭제된 상품의 관리자 수정/삭제는 기존 product 예외 체계의 `PRODUCT_NOT_FOUND`를 사용한다.
- 공개 상세 조회에서 `STOPPED` 또는 삭제된 상품은 `PRODUCT_NOT_FOUND`로 처리한다.
- 요청 값 검증 실패는 기존 공통 검증 오류 응답을 따른다.
- 관리자 권한이 없으면 기존 auth 권한 오류 응답을 따른다.

## 테스트 포인트

- 상품 상태별 공개 조회 노출 여부
- soft delete 후 공개 조회 제외
- 관리자 등록, 수정, 삭제 service 동작
- 관리자 API 권한 검증
- request validation과 공통 응답 형식
