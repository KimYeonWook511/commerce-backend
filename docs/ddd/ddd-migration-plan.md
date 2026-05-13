# DDD Migration Plan

## Overview

이 문서는 커머스 백엔드의 DDD 구조 전환 기준을 정의한다.  
초기 전략과 도메인별 마이그레이션을 거쳐 확정된 원칙만 담는다.

---

## 진행 원칙

- 기존 코드를 한 번에 대규모 수정하지 않는다
- 기능 안정성을 항상 우선한다
- 과도한 추상화는 하지 않는다
- DDD 구조 도입과 legacy 삭제는 반드시 별도 커밋으로 분리한다

---

## 패키지 구조

### 기본 구조

```text
<domain>
├── application    (service, command, result)
├── domain         (entity, value object, repository port)
├── infrastructure (JpaXxxRepository, XxxRepositoryAdapter)
└── presentation   (controller, request, response)
```

### provider 서브 패키지

외부 provider 구현(PG 등)은 도메인 패키지 하위에 서브 패키지로 둔다.  
자체 도메인 엔티티가 없으므로 상위 도메인의 `domain` 패키지를 그대로 사용한다.

```text
payment
├── application
├── domain
├── infrastructure
├── presentation
└── naverpay                   ← provider 서브 패키지
    ├── application            (NaverPayApprovalService)
    ├── infrastructure         (NaverPayGateway, NaverPayClient, code/, result/)
    ├── presentation           (NaverPayController)
    └── exception
```

---

## application 계층 서비스

`ApplicationService` 접미사를 강제하지 않는다.  
패키지 경로가 계층을 표현하므로 클래스명은 유스케이스 책임 중심으로 짧게 작성한다.

처음부터 지나치게 잘게 나누지는 않되, 아래 상황에서는 유스케이스 책임 단위로 분리한다.

- 생성, 취소, 만료처럼 트랜잭션 흐름과 변경 이유가 다르다
- API, batch, PG 승인처럼 호출 맥락이 달라진다
- public method가 실제 유스케이스보다 구현 전략을 드러내기 시작한다

예시:

```text
order.application.OrderCreateService
order.application.OrderCancelService
order.application.OrderExpirationService
order.application.OrderQueryService
order.application.OrderConcurrencyService
```

batch는 실행 기술 계층으로 본다.  
Job, Step, Reader, Writer 구성은 batch 패키지에 두되, 실제 비즈니스 상태 변경 흐름은 application service에 둔다.

Domain Service가 필요해질 경우 `StockService`처럼 포괄적인 이름을 피하고,  
실제 도메인 책임이 드러나는 이름을 우선 사용한다.

```text
StockDeductionPolicy
StockAvailabilityChecker
PaymentApprovalPolicy
```

---

## Domain 역할

- 상태 변경 규칙 포함
- 비즈니스 로직 책임

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

```text
order.domain.repository.OrderRepository     ← port
order.infrastructure.JpaOrderRepository     ← Spring Data JPA
order.infrastructure.OrderRepositoryAdapter ← 구현체
```

domain repository:

```java
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findByMerchantPayKey(String merchantPayKey);
}
```

adapter:

```java
@Repository
public class OrderRepositoryAdapter implements OrderRepository {

    private final JpaOrderRepository jpaOrderRepository;

    @Override
    public Order save(Order order) {
        return jpaOrderRepository.save(order);
    }
}
```

피해야 할 방식:

```java
// JpaRepository와 domain repository를 직접 함께 상속하지 않는다
public interface JpaOrderRepository extends JpaRepository<Order, Long>, OrderRepository {
}
```

domain repository interface에서 JPA 구현 세부사항을 드러내지 않는다.

- `flush()` 제거: JPA flush 전략을 domain interface에 드러내지 않는다
- `Pageable` 제거: Spring Data JPA 전용 타입은 adapter 내부에서 변환한다
- generic save 제거: `<S extends Order> S save(S order)` 형태 대신 `Order save(Order order)` 사용

---

## PG Gateway 패턴

외부 PG 호출과 내부 결제 상태 반영은 같은 service에 두지 않는다.

```text
NaverPayGateway (infrastructure)
  - NaverPayClient 호출
  - NaverPayException 처리
  - 응답 코드 → PaymentAttemptFailCode / PaymentErrorCode 매핑
  - NaverPayApproveResult / NaverPayHistoryResult / NaverPayCancelResult 반환

NaverPayApprovalService (application)
  - 흐름 조율만 담당
  - Gateway Result를 switch로 분기
  - PaymentApprovalService / PaymentAttemptService 호출
```

Gateway 설계 기준:

- Gateway는 application layer에 의존하지 않는다
- provider가 하나뿐일 때는 Gateway 인터페이스를 두지 않는다
- 두 번째 provider 추가 시 공통 인터페이스를 도입한다

---

## 테스트 패키지 구조

```text
<domain>
├── application              ← 단위 테스트 (Mockito)
├── infrastructure           ← @DataJpaTest (repository 수준)
└── integration              ← @SpringBootTest 통합 테스트
    ├── concurrency          ← 동시성 / 데드락 테스트
    └── batch                ← 배치 통합 테스트
```

- application 단위 테스트는 domain repository를 mock으로 사용한다
- `@DataJpaTest` 기반 infrastructure 테스트는 트랜잭션 롤백으로 데이터를 정리한다
- 통합 테스트는 `PersistenceTestSupport`를 구현한 도메인별 support를 `tearDown`에 명시한다

### PersistenceTestSupport 구조

```java
// 각 도메인 support가 구현하는 인터페이스
public interface PersistenceTestSupport {
    CleanupOrder cleanupOrder();
    void deleteAllInBatch();
}

// FK 안전 삭제 순서 관리
public enum CleanupOrder {
    PAYMENT(10), OUTBOX(20), ORDER(30), STOCK(40), PRODUCT(50), MEMBER(60);
}

// 사용 방식 — tearDown에 사용한 support만 명시한다
@AfterEach
void tearDown() {
    persistenceCleanup.deleteAllInBatch(
        paymentPersistence, orderPersistence, productPersistence, memberPersistence
    );
}
```

---

## 마이그레이션 방식

### DDD 구조 도입 커밋

새 구조 추가, 참조 전환, 테스트 갱신을 담는다.  
기존 파일은 삭제하지 않고 유지한다.

```text
refactor: <domain> DDD 구조 마이그레이션
```

### legacy 삭제 커밋

DDD 구조 도입 커밋과 반드시 분리한다.  
삭제 전 잔존 참조 검색으로 안전성을 확인한다.

```bash
rg "com\.commerce\.<domain>\.(service|controller|repository)" src/main/java src/test/java
```

```text
refactor: <domain> legacy 패키지를 정리한다
```

---

## 완료 기준

- 기존 기능이 정상 동작한다
- 핵심 비즈니스 로직이 domain 또는 application 내부로 이동한다
- application 계층 서비스는 흐름 조율만 담당한다
- controller는 요청 수신, 검증, 위임, 반환만 담당한다
- domain repository interface에 JPA 구현 세부사항이 드러나지 않는다
- legacy 코드가 제거된다

---

## 최종 목표 구조

```text
<domain>
├── application
├── domain
├── infrastructure
└── presentation
```

legacy 없이 DDD 구조로 전환 완료, 전 도메인 일관성 유지
