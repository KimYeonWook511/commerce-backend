# Repository Adapter Boundary 회고

## 배경

이 작업은 `ddd-migration-plan.md`에서 정의한 adapter 분리 원칙이 일부 도메인에서 일관되게 적용되지 않았던 문제를 해결했다.

Product, Member, Payment는 이미 `XxxRepositoryAdapter` 방식으로 분리되어 있었지만, Order, Stock, StockHistory는 `JpaXxxRepository`가 domain `XxxRepository`를 직접 implements하는 방식으로 남아 있었다.

---

## 이번 작업에서 확정한 기준

### JPA repository는 domain repository를 implements하지 않는다

피해야 할 방식:

```java
public interface JpaOrderRepository extends JpaRepository<Order, Long>, OrderRepository { }
```

이 방식은 `ddd-migration-plan.md`에서 이미 경계 규칙으로 명시했다. `JpaRepository.save`의 generic 시그니처에 맞춰야 하고, port가 Spring Data JPA 구현 세부사항에 끌려가기 때문이다.

올바른 방식:

- `JpaXxxRepository`는 `JpaRepository<X, Long>`만 상속한다
- `XxxRepositoryAdapter`가 domain `XxxRepository`를 구현하고 `JpaXxxRepository`에 위임한다

### domain repository interface에서 JPA 구현 세부사항을 제거한다

adapter 분리와 함께 `OrderRepository` domain interface에서 JPA 의존성이 드러나는 부분을 정리했다.

- `<S extends Order> S save(S order)` → `Order save(Order order)`: generic 타입은 `JpaRepository.save`를 상속할 때 필요했던 형태다. domain port에서 이 형태를 쓸 이유가 없다.
- `void flush()` 제거: JPA flush 전략을 domain interface에 드러내는 것은 port 설계와 맞지 않는다.
- `findExpiredOrdersAfterId(OrderStatus, LocalDateTime, Long, Pageable)` → `int limit` 파라미터로 교체: `Pageable`은 Spring Data JPA 전용 타입이다. adapter 내부에서 `PageRequest`로 변환하는 것이 올바른 경계다.

`flush()` 제거에 따라 `OrderCancelService`에서 `orderRepository.flush()` 호출을 제거했고, `OrderExpirationBatchConfig`는 `PageRequest` 생성 없이 `int` limit만 전달하도록 변경했다.

### 단위 테스트의 mock 대상은 domain repository다

이전에는 `@Mock JpaOrderRepository`로 JPA repository를 직접 모킹했다. adapter 분리 이후 application 계층은 domain repository에만 의존하므로 단위 테스트의 mock 대상도 domain interface로 바꾼다.

```java
// 이전
@Mock JpaOrderRepository orderRepository;

// 이후
@Mock OrderRepository orderRepository;
```

### 테스트 패키지를 역할별로 나눈다

기존에는 통합 테스트, 동시성 테스트, repository 테스트가 `application/`이나 `repository/`에 섞여 있었다. 이번 작업에서 역할별로 분리했다.

```text
order.application              - 순수 단위 테스트 (Mockito)
order.infrastructure           - @DataJpaTest 수준의 repository 테스트
order.integration              - @SpringBootTest 통합 테스트
order.integration.batch        - 배치 통합 테스트
order.integration.concurrency  - 동시성/데드락 테스트
```

payment, stock, product, member도 같은 구조를 따른다.

`@DataJpaTest` 기반의 `infrastructure/` 테스트는 트랜잭션 롤백으로 데이터를 정리하므로 `PersistenceCleanupTestSupport`가 필요하지 않다.

---

## 테스트 Persistence Cleanup 구조 개선

### 배경

기존 `PersistenceCleanupTestSupport`는 `deleteAllInBatch()`를 호출하면 모든 repository를 일괄 삭제하는 방식이었다.

```java
public void deleteAllInBatch() {
    paymentAttemptRepository.deleteAllInBatch();
    paymentRepository.deleteAllInBatch();
    outboxEventRepository.deleteAllInBatch();
    orderItemRepository.deleteAllInBatch();
    orderRepository.deleteAllInBatch();
    stockHistoryRepository.deleteAllInBatch();
    stockRepository.deleteAllInBatch();
    productRepository.deleteAllInBatch();
    memberRepository.deleteAllInBatch();
}
```

도메인별 `*PersistenceTestSupport`로 fixture 저장 책임을 나눈 뒤, cleanup 책임도 같은 기준으로 분리했다. 기존 방식의 문제점은 다음과 같았다.

- **테스트 의도가 드러나지 않는다.** stock 테스트에서 payment, member 같은 무관한 도메인까지 삭제된다. tearDown만 봐서는 이 테스트가 어떤 도메인 데이터를 사용하는지 알 수 없다.
- **책임이 중복된다.** 도메인별 support가 이미 자기 도메인 삭제 책임을 가지는데, `PersistenceCleanupTestSupport`가 모든 repository를 다시 직접 의존한다.
- **중앙 메서드가 계속 커진다.** 새로운 도메인이 추가될 때마다 이 메서드에 삭제 순서를 직접 추가해야 한다.

### 개선된 구조

#### `PersistenceTestSupport` 인터페이스

각 도메인별 persistence support가 공통으로 구현할 인터페이스다.

```java
public interface PersistenceTestSupport {
    CleanupOrder cleanupOrder();
    void deleteAllInBatch();
}
```

- `cleanupOrder()`는 FK 안전 삭제 순서를 제공한다
- `deleteAllInBatch()`는 해당 support가 담당하는 repository만 삭제한다

#### `CleanupOrder` enum

FK 의존 방향을 기준으로 삭제 순서를 enum으로 관리한다.

```java
public enum CleanupOrder {
    PAYMENT(10),
    OUTBOX(20),
    ORDER(30),
    STOCK(40),
    PRODUCT(50),
    MEMBER(60);
}
```

payment는 order를 참조하므로 먼저 삭제한다. order는 product/member를 참조하므로 그 이전에 삭제한다. stock은 product를 참조하므로 product보다 먼저다.

#### `PersistenceCleanupTestSupport`

더 이상 모든 repository를 직접 의존하지 않는다. 전달받은 support를 FK-safe 순서로 정렬해서 실행하는 역할만 한다.

```java
@TestComponent
public class PersistenceCleanupTestSupport {
    public void deleteAllInBatch(PersistenceTestSupport... supports) {
        Arrays.stream(supports)
            .sorted(Comparator.comparingInt(support -> support.cleanupOrder().value()))
            .forEach(PersistenceTestSupport::deleteAllInBatch);
    }
}
```

### 테스트 사용 방식

```java
// 이전
@AfterEach
void tearDown() {
    persistenceCleanup.deleteAllInBatch();
}

// 이후
@AfterEach
void tearDown() {
    persistenceCleanup.deleteAllInBatch(
        paymentPersistence, orderPersistence, productPersistence, memberPersistence
    );
}
```

인자 순서를 잘못 넘겨도 `PersistenceCleanupTestSupport`가 `CleanupOrder` 기준으로 정렬하므로 FK 오류가 발생하지 않는다.

tearDown만 봐도 이 테스트가 어떤 도메인 데이터를 사용하는지 파악할 수 있다.

### 주의할 점

- **support 누락 시 데이터가 남는다.** setup에서 저장에 사용한 persistence support는 tearDown에도 반드시 넘겨야 한다. 누락하면 해당 도메인 데이터가 잔존해 다음 테스트에 영향을 줄 수 있다.
- **CleanupOrder는 FK 관계 변경 시 재검토한다.** 새로운 도메인이 추가되거나 FK 관계가 바뀌면 함께 검토해야 한다.

---

## 다음 작업에 적용할 원칙

- 신규 도메인 repository는 반드시 adapter 방식으로 분리한다. `JpaXxxRepository`가 domain interface를 직접 implements하는 방식은 사용하지 않는다.
- domain repository interface에 `flush()`, `Pageable`, generic save 같은 JPA 구현 세부사항이 드러나면 제거한다.
- 단위 테스트의 mock 대상은 domain repository interface다.
- 통합 테스트는 `integration/` 패키지에, repository 수준 테스트는 `infrastructure/` 패키지에 둔다.
- 신규 도메인 persistence support 추가 시 `PersistenceTestSupport`를 구현하고 `CleanupOrder`에 FK 관계를 고려해 순서를 지정한다.
