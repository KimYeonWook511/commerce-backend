# 🧱 DDD Migration Plan

## 🧾 Overview
이 문서는 기존 커머스 백엔드 코드를 유지하면서  
DDD 스타일 구조로 점진적으로 리팩터링하기 위한 전략을 정의한다.

목표는 전체를 한 번에 바꾸는 것이 아니라  
👉 핵심 도메인(Order, Stock, Payment)부터 안정적으로 전환하는 것이다.

---

# 🎯 목표

- 기존 기능을 깨지 않으면서 구조 개선
- 핵심 비즈니스 로직을 도메인 객체로 이동
- application 계층 서비스 중심 흐름으로 전환
- 점진적 리팩터링 후 legacy 제거

---

# ⚙️ 진행 원칙

- 기존 코드를 한 번에 대규모 수정하지 않는다
- 기능 안정성을 항상 우선한다
- 과도한 추상화는 하지 않는다
- 핵심 도메인부터 순차적으로 적용한다
- 최종적으로 legacy 코드를 제거하는 것을 목표로 한다

---

# 📦 패키지 전략

## 권장 구조

```text
order
├── legacy
├── application
├── domain
├── infrastructure
└── presentation
```

👉 legacy에는 기존 하위 패키지를 옮기며, 기존 코드를 DDD에 맞게 복사 후 수정하도록 한다.

단, 대량 패키지 이동이 DDD 리팩터링 커밋을 과하게 복잡하게 만들면  
legacy 물리 이동은 별도 커밋 또는 별도 단계로 분리한다.  
DDD 적용 커밋에는 새 구조 생성, 참조 전환, 테스트 갱신처럼 의도 파악에 필요한 변경을 우선 담는다.

---

# 🚀 1차 적용 범위

다음 흐름을 우선 리팩터링 대상으로 한다:

```text
1. 주문 생성
2. 재고 차감
3. 결제 요청
4. 결제 승인 / 실패
5. 주문 취소
6. 재고 복구
7. 결제 취소
```

👉 이유: 프로젝트의 핵심 비즈니스 흐름이기 때문

---

# 🧠 설계 방향

## application 계층 서비스 역할

- 흐름 조율 담당
- 트랜잭션 관리
- 도메인 호출

```text
OrderService
    ↓
Stock / Order / Payment 호출
```

application 패키지 안의 서비스는 `ApplicationService` 접미사를 강제하지 않는다.  
패키지 경로가 계층을 표현하므로 클래스명은 유스케이스 책임 중심으로 짧게 작성한다.

예시:

```text
stock.application.AdminStockService
stock.application.OrderStockService
stock.application.StockConcurrencyService
```

application service는 도메인당 하나로 강제하지 않는다.

처음부터 지나치게 잘게 나누지는 않되, 아래 상황에서는 유스케이스 책임 단위로 분리한다.

- 생성, 취소, 만료처럼 트랜잭션 흐름과 변경 이유가 다르다.
- API, batch, 결제 승인처럼 호출 맥락이 달라진다.
- public method가 실제 유스케이스보다 구현 전략이나 실험 흐름을 드러내기 시작한다.

예시:

```text
order.application.OrderCreateService
order.application.OrderCancelService
order.application.OrderExpirationService
order.application.OrderQueryService
order.application.OrderConcurrencyService
```

batch는 실행 기술 계층으로 본다. Job, Step, Reader, Writer 구성은 batch 패키지에 두되, 주문 만료처럼 실제 비즈니스 상태를 바꾸는 흐름은 application service에 둔다.

Domain Service가 필요해질 경우 `StockService`처럼 포괄적인 이름을 피하고,  
실제 도메인 책임이 드러나는 이름을 우선 사용한다.

예시:

```text
StockDeductionPolicy
StockAvailabilityChecker
PaymentApprovalPolicy
OrderPricingCalculator
```

---

## Domain 역할

- 상태 변경 규칙 포함
- 비즈니스 로직 책임

예시:

```text
stock.decrease(quantity);
order.cancel();
payment.approve();
```

---

## Repository 역할

- `domain.repository`에는 application 의도가 드러나는 repository port만 정의한다
- domain repository는 Spring Data JPA의 `JpaRepository`를 상속하지 않는다
- domain repository 메서드명은 JPA 파생 쿼리명이 아니라 도메인 조회 의도를 기준으로 정한다
- Spring Data JPA repository는 `infrastructure`의 `JpaXxxRepository`에 둔다
- domain repository 구현은 `infrastructure`의 `XxxRepositoryAdapter`가 담당한다
- `JpaXxxRepository`는 `XxxRepositoryAdapter` 내부에서만 사용한다

예시:

```text
order.domain.repository.OrderRepository
order.infrastructure.JpaOrderRepository
order.infrastructure.OrderRepositoryAdapter
```

domain repository:

```java
public interface ProductRepository {
    Product save(Product product);
    Optional<Product> findVisibleProduct(Long productId, List<ProductStatus> statuses);
}
```

JPA repository:

```java
public interface JpaProductRepository extends JpaRepository<Product, Long> {
    @Query("""
            select p
            from Product p
            where p.id = :productId
              and p.deletedAt is null
              and p.status in :statuses
            """)
    Optional<Product> findVisibleProduct(Long productId, List<ProductStatus> statuses);
}
```

adapter:

```java
@Repository
public class ProductRepositoryAdapter implements ProductRepository {

    private final JpaProductRepository jpaProductRepository;

    @Override
    public Product save(Product product) {
        return jpaProductRepository.save(product);
    }

    @Override
    public Optional<Product> findVisibleProduct(Long productId, List<ProductStatus> statuses) {
        return jpaProductRepository.findVisibleProduct(productId, statuses);
    }
}
```

피해야 할 방식:

```java
public interface JpaProductRepository extends JpaRepository<Product, Long>, ProductRepository {
}
```

이 방식은 domain repository가 `JpaRepository.save`의 generic 시그니처에 맞춰져야 하고, port가 Spring Data JPA 구현 세부사항에 끌려간다. 따라서 새 DDD 마이그레이션에서는 adapter 방식을 기본 원칙으로 둔다.

---

# 🧪 테스트 전략

## 원칙

- 모든 테스트를 한 번에 작성하지 않는다
- 변경이 잦은 영역은 후순위로 둔다

---

## 우선 작성 테스트

```text
Stock 감소
Stock 복구
Order 생성
Order 취소
Payment 승인
Payment 실패 처리
```

👉 도메인 테스트 위주로 작성

---

## 후순위 테스트

```text
Controller 테스트
API 응답 테스트
통합 테스트
```

👉 구조 안정화 이후 진행

---

# 🔄 마이그레이션 단계

## Step 1
- 기존 코드 유지
- 새로운 domain 구조 생성

## Step 2
- Order → Stock → Payment 흐름 리팩터링

## Step 3
- 도메인 로직 이동
- application 계층 서비스 정리

## Step 4
- 기존 legacy 코드 제거 또는 축소

---

# ✅ 완료 기준

- 기존 기능이 정상 동작한다
- 핵심 비즈니스 로직이 도메인 내부로 이동한다
- application 계층 서비스는 흐름만 담당한다
- Controller는 최소 역할만 수행한다
- legacy 코드 제거 가능 상태가 된다

---

# 📌 최종 목표 구조

```text
order
├── application
├── domain
├── infrastructure
└── presentation
```

👉 legacy 없이 DDD 구조로 전환 완료

---

# 🔥 핵심 요약

```text
전체 리팩터링 ❌
핵심 도메인부터 점진 적용 ⭕

완벽한 DDD ❌
실용적인 DDD ⭕

구조보다 안정성 우선 ⭕

최종적으로 legacy 제거 ⭕
```

---

# 🚀 결론

이 전략의 핵심은:

👉 "한 번에 바꾸지 않는다"

- 핵심 도메인부터 시작한다
- 안정성을 유지한다
- 점진적으로 확장한다

이 과정을 통해  
👉 유지보수 가능한 커머스 백엔드 구조로 전환한다
