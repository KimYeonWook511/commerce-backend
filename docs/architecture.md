# 아키텍처

## 디렉토리 구조
``` 
src/
├── auth/              # 인증, JWT, 토큰 재발급
├── member/            # 회원 도메인
├── product/           # 상품 도메인, 공개 조회, 관리자 상품 관리
├── stock/             # 재고 생성/조정/차감/복구, 변경 이력, 동시성 제어
├── order/             # 주문 생성/취소, 멱등 처리
├── orderitem/         # 주문 항목
├── payment/           # 결제 준비/완료
├── outbox/            # 이벤트 저장/발행
└── common/            # 공통 설정, 예외, 유틸
```

## 계층 구조
Controller -> Service -> Domain/Repository

## 데이터 흐름
```
회원 요청 → Controller → Service → Domain/Repository
상품 공개 조회 → ProductController → ProductService → ProductRepository/StockRepository
관리자 상품 관리 → AdminProductController → ProductService → ProductRepository
관리자 재고 관리 → AdminStockController → StockService → ProductRepository/StockRepository/StockHistoryRepository
주문 생성 → 재고 차감 → 주문 저장 → 결제 준비
결제 승인 → 외부 결제사 검증 → 주문/결제 상태 반영
주문 취소/실패 → Outbox 이벤트 저장 → 후속 복구 처리
```

## 도메인 책임
- `product` 도메인은 주문 내부 참조용 상품 정보 관리, 공개 상품 조회, 관리자 상품 등록/수정/soft delete API를 제공한다.
- 상품 목록 조회는 `ProductRepository`를 통해 삭제되지 않고 `status`가 `ON_SALE` 또는 `SOLD_OUT`인 상품을 `createdAt DESC` 기준으로 반환한다.
- 상품 상세 조회는 공개 대상 상품만 `ProductRepository`와 `StockRepository`로 조회해 상품 기본 정보와 현재 재고 수량을 조합한다.
- 관리자 상품 삭제는 물리 삭제하지 않고 `deletedAt`을 기록하며, 삭제된 상품은 공개 조회와 관리자 수정/삭제 대상에서 제외한다.
- `stock` 도메인은 상품별 현재 재고, 주문 경로의 재고 차감/복구, 관리자 초기 재고 생성, 관리자 수동 증가/감소, 재고 변경 이력 조회를 담당한다.
- 관리자 초기 재고 생성은 삭제되지 않은 상품에 대해서만 상품별 한 번 가능하며, 기존 `Product : Stock = 1:1` 관계를 유지한다.
- 관리자 재고 증가/감소는 비관적 락으로 `Stock`을 조회한 뒤 수량을 변경하고, `StockHistory`에 변경 수량, 변경 사유, 관리자 member id, 변경 시점을 기록한다.
- 재고 변경 사유는 `INBOUND`, `DISPOSAL`, `ADMIN_ADJUSTMENT`, `ORDER_CANCEL_RESTORE` 중 하나로 관리한다.

## 저장소 및 인프라 의존성
- 영속 데이터는 MySQL에 저장한다.
- 토큰과 주문 멱등 키는 Redis에 저장한다.
- 후속 이벤트 처리는 Outbox 모듈을 중심으로 구성되어 있으며 Kafka 연동 코드를 포함한다.
- 외부 결제는 PG 연동 모듈을 통해 처리한다.

## 인프라 경계
- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
