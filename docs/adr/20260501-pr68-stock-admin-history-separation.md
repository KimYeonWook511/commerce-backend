# 관리자 재고 관리와 변경 이력을 분리한다

- Status: accepted
- Date: 2026-05-01

## Context

상품 등록/수정 책임과 재고 운영 책임을 분리하고, 변경 수량·사유·관리자 member id·시점을 감사 데이터로 보존할 수 있다.

## Decision

관리자 초기 재고 생성과 수동 증가/감소는 상품 API와 분리된 재고 API로 제공하고, 관리자 변경 이력은 `tbl_stock_history`에 저장한다.

## Consequences

상품 생성 후 초기 재고 생성을 별도 호출해야 하며, 첫 버전의 이력 조회는 pagination 없이 상품별 전체 목록을 반환한다.
