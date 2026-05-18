package com.commerce.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, StockPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class OrderCreateServiceIdempotencyTest {

	@Autowired
	private OrderCreateService orderCreateService;

	@Autowired
	private OrderIdempotencyStore orderIdempotencyStore;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, productPersistence, stockPersistence, orderPersistence
		);
	}

	@DisplayName("같은 멱등키로 재요청하면 주문이 중복 생성되지 않는다")
	@Test
	void createOrder_whenSameIdempotencyKey_returnSameOrder() {
		// given
		Member member = memberPersistence.save(createMember("order-idempotency"));
		Product product = productPersistence.save(createProduct("product-1", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("idem-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// when
		OrderCreateResult first = orderCreateService.createOrder(command);
		OrderCreateResult second = orderCreateService.createOrder(command);

		// then
		assertThat(first.getOrderId()).isEqualTo(second.getOrderId());
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(orderPersistence.countItems()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
	}

	@DisplayName("Redis TTL 만료 후 재요청하면 중복 주문 없이 기존 주문을 반환한다")
	@Test
	void createOrder_whenIdempotencyKeyExpired_returnExistingOrder() {
		// given
		Member member = memberPersistence.save(createMember("order-ttl-expired"));
		Product product = productPersistence.save(createProduct("product-ttl", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("ttl-expired-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// when
		OrderCreateResult first = orderCreateService.createOrder(command);

		// Redis TTL 만료 시뮬레이션: Redis 상태를 직접 제거한다
		orderIdempotencyStore.clear(member.getId(), "ttl-expired-key");

		// TTL 만료 후 동일 키로 재요청 → unique 위반 → 기존 주문 반환
		OrderCreateResult second = orderCreateService.createOrder(command);

		// then
		assertThat(first.getOrderId()).isEqualTo(second.getOrderId());
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
	}

	@DisplayName("동시에 같은 멱등키로 요청하면 한 요청만 주문을 생성하고 다른 요청은 같은 주문을 반환하거나 race window 로 실패한다")
	@Test
	void createOrder_whenConcurrentSameIdempotencyKey_returnSameOrderOrRaceFailure() throws Exception {
		// given
		Member member = memberPersistence.save(createMember("order-concurrent"));
		Product product = productPersistence.save(createProduct("product-concurrent", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("concurrent-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		// when
		Future<OrderCreateResult> future1 = executor.submit(() -> {
			barrier.await();
			return orderCreateService.createOrder(command);
		});
		Future<OrderCreateResult> future2 = executor.submit(() -> {
			barrier.await();
			return orderCreateService.createOrder(command);
		});

		OrderCreateResult winnerResult = null;
		Throwable raceError = null;
		try {
			winnerResult = future1.get();
		} catch (ExecutionException ex) {
			raceError = ex.getCause();
		}
		try {
			OrderCreateResult other = future2.get();
			if (winnerResult == null) {
				winnerResult = other;
			} else {
				// 두 요청 모두 성공한 경우 같은 주문을 반환해야 한다.
				assertThat(other.getOrderId()).isEqualTo(winnerResult.getOrderId());
			}
		} catch (ExecutionException ex) {
			raceError = ex.getCause();
		}

		executor.shutdown();

		// then
		// 적어도 한 요청은 성공해 주문을 생성한다.
		assertThat(winnerResult).isNotNull();
		// unique 제약으로 주문은 정확히 1건만 저장되고 재고도 한 번만 차감된다.
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
		// race window 가 발생했다면 find-first 정책상 unique 위반이 안전망 500 으로 도달한다.
		if (raceError != null) {
			assertThat(raceError).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	private Member createMember(String emailPrefix) {
		return Member.builder()
			.email(emailPrefix + "@example.com")
			.password("password123")
			.username("uorder")
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.product(product)
			.quantity(quantity)
			.build();
	}
}
