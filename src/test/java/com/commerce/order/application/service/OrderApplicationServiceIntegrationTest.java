package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.usecase.OrderCreateUseCase;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	StockPersistenceTestSupport.class
})
class OrderApplicationServiceIntegrationTest {

	@Autowired
	private OrderCreateUseCase orderCreateService;

	@Autowired
	private OrderCancelService orderCancelService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, orderPersistence, productPersistence, stockPersistence
		);
	}

	@DisplayName("재고가 부족하면 주문이 실패하고 취소 후 다시 주문할 수 있다")
	@Test
	void createOrder_whenOutOfStock_thenCancel_thenCreateSuccess() {
		// given
		Member member = memberPersistence.save(createMember("order-recovery"));
		Product product = productPersistence.save(createProduct("recovery-product", 1000));
		stockPersistence.save(createStock(product, 1));

		OrderCreateCommand firstRequest = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("first-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();
		OrderCreateCommand secondRequest = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("second-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();

		// when
		OrderCreateResult created = orderCreateService.createOrder(firstRequest);

		// then
		assertThatThrownBy(() -> orderCreateService.createOrder(secondRequest))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});

		orderCancelService.cancelOrder(member.getId(), created.getOrderId());

		OrderCreateResult recreated = orderCreateService.createOrder(secondRequest);
		assertThat(recreated.getOrderId()).isNotNull();
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
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
			.productId(product.getId())
			.quantity(quantity)
			.build();
	}
}
