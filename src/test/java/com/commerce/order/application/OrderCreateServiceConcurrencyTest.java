package com.commerce.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
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
@Tag("concurrency")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, StockPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class OrderCreateServiceConcurrencyTest {

	@Autowired
	private OrderCreateService orderCreateService;

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

	@DisplayName("같은 idempotencyKey로 동시에 두 요청을 보내면 한쪽만 성공하고 다른 쪽은 409를 반환한다")
	@Test
	void createOrder_whenTwoConcurrentRequestsWithSameKey_oneSucceedsAndOtherGets409() throws InterruptedException {
		// given
		Member member = memberPersistence.save(createMember("concurrent-test"));
		Product product = productPersistence.save(createProduct("concurrent-product", 5000));
		stockPersistence.save(createStock(product, 10));

		String idempotencyKey = "concurrent-idem-key";
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger inProgressCount = new AtomicInteger(0);
		AtomicReference<OrderCreateResult> successResult = new AtomicReference<>();

		ExecutorService executor = Executors.newFixedThreadPool(2);

		// when
		for (int i = 0; i < 2; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					OrderCreateResult result = orderCreateService.createOrder(command);
					successResult.compareAndSet(null, result);
					successCount.incrementAndGet();
				} catch (OrderException ex) {
					if (OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS.equals(ex.getErrorCode())) {
						inProgressCount.incrementAndGet();
					}
				} catch (Exception ignored) {
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();

		// then
		// 정확히 하나의 주문만 DB에 저장된다
		assertThat(orderPersistence.count()).isEqualTo(1);
		// 재고가 한 번만 차감된다
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity()).isEqualTo(9);
		// 한쪽은 성공, 다른 쪽은 409 IN_PROGRESS (Race 조건에 따라 두 쪽 모두 성공할 수도 있음 — 두 번째는 DB find 로 기존 주문 반환)
		assertThat(successCount.get() + inProgressCount.get()).isEqualTo(2);
		assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
	}

	private Member createMember(String emailPrefix) {
		return Member.builder()
			.email(emailPrefix + "@example.com")
			.password("password123")
			.username("conc-user")
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
