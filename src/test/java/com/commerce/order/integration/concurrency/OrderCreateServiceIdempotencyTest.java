package com.commerce.order.integration.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

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
import com.commerce.member.integration.support.MemberPersistenceTestSupport;
import com.commerce.order.application.OrderCreateService;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.integration.support.OrderPersistenceTestSupport;
import com.commerce.order.domain.OrderIdempotencyStore;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.integration.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.integration.support.StockPersistenceTestSupport;
import com.commerce.test.support.PersistenceCleanupTestSupport;
import com.commerce.test.support.TestcontainersSupport;

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
	static void registerRedis(DynamicPropertyRegistry registry) {
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

	@DisplayName("처리 중인 멱등키면 주문 생성에 실패한다")
	@Test
	void createOrder_whenIdempotencyProcessing_throwException() {
		// given
		Member member = memberPersistence.save(createMember("order-idempotency-processing"));
		Product product = productPersistence.save(createProduct("product-2", 2000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("processing-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();

		// when
		orderIdempotencyStore.reserve(member.getId(), "processing-key", Duration.ofSeconds(60));

		// then
		assertThatThrownBy(() -> orderCreateService.createOrder(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
			});
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
