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

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.infrastructure.persistence.support.CartPersistenceTestSupport;
import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.usecase.CreateOrderUseCase;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
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
	StockPersistenceTestSupport.class,
	CartPersistenceTestSupport.class
})
class OrderCreateCartIntegrationTest {

	@Autowired
	private CreateOrderUseCase createOrderUseCase;

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

	@Autowired
	private CartPersistenceTestSupport cartPersistence;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, orderPersistence, productPersistence, stockPersistence, cartPersistence
		);
	}

	@DisplayName("주문 성공 시 주문된 항목만 cart 에서 제거되고 미주문 항목은 유지된다")
	@Test
	void createOrder_whenSuccess_removeOrderedCartItemsOnly() {
		// given
		Member member = memberPersistence.save(createMember("cart-clear-success"));
		Product productA = productPersistence.save(createProduct("product-a", 1000));
		Product productB = productPersistence.save(createProduct("product-b", 2000));
		Product productC = productPersistence.save(createProduct("product-c", 3000));
		stockPersistence.save(createStock(productA, 10));
		stockPersistence.save(createStock(productB, 10));
		stockPersistence.save(createStock(productC, 10));

		cartPersistence.save(CartItem.create(member.getId(), productA.getId(), 1));
		cartPersistence.save(CartItem.create(member.getId(), productB.getId(), 2));
		cartPersistence.save(CartItem.create(member.getId(), productC.getId(), 3));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("cart-clear-success-key")
			.items(List.of(
				OrderCreateItem.builder().productId(productA.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(productB.getId()).quantity(2).build()
			))
			.build();

		// when
		OrderCreateResult result = createOrderUseCase.createOrder(command);

		// then
		assertThat(result.getOrderId()).isNotNull();
		List<CartItem> remaining = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(member.getId());
		assertThat(remaining).hasSize(1);
		assertThat(remaining.get(0).getProductId()).isEqualTo(productC.getId());
		assertThat(remaining.get(0).getQuantity()).isEqualTo(3);
	}

	@DisplayName("cart 에 없는 productId 가 주문에 포함되어도 정상 주문되고 cart 변동이 없다")
	@Test
	void createOrder_whenProductNotInCart_thenOrderSucceedsAndCartUnchanged() {
		// given
		Member member = memberPersistence.save(createMember("buy-now"));
		Product cartProduct = productPersistence.save(createProduct("cart-product", 1000));
		Product buyNowProduct = productPersistence.save(createProduct("buy-now-product", 5000));
		stockPersistence.save(createStock(cartProduct, 10));
		stockPersistence.save(createStock(buyNowProduct, 10));

		cartPersistence.save(CartItem.create(member.getId(), cartProduct.getId(), 2));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("buy-now-key")
			.items(List.of(
				OrderCreateItem.builder().productId(buyNowProduct.getId()).quantity(1).build()
			))
			.build();

		// when
		OrderCreateResult result = createOrderUseCase.createOrder(command);

		// then
		assertThat(result.getOrderId()).isNotNull();
		List<CartItem> remaining = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(member.getId());
		assertThat(remaining).hasSize(1);
		assertThat(remaining.get(0).getProductId()).isEqualTo(cartProduct.getId());
		assertThat(remaining.get(0).getQuantity()).isEqualTo(2);
	}

	@DisplayName("주문 실패 시 cart 항목이 트랜잭션 롤백으로 그대로 유지된다")
	@Test
	void createOrder_whenStockShortage_thenCartUnchanged() {
		// given
		Member member = memberPersistence.save(createMember("cart-rollback"));
		Product product = productPersistence.save(createProduct("rollback-product", 1000));
		stockPersistence.save(createStock(product, 1));

		cartPersistence.save(CartItem.create(member.getId(), product.getId(), 5));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("cart-rollback-key")
			.items(List.of(
				OrderCreateItem.builder().productId(product.getId()).quantity(2).build()
			))
			.build();

		// when & then
		assertThatThrownBy(() -> createOrderUseCase.createOrder(command))
			.isInstanceOf(StockException.class);

		List<CartItem> remaining = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(member.getId());
		assertThat(remaining).hasSize(1);
		assertThat(remaining.get(0).getProductId()).isEqualTo(product.getId());
		assertThat(remaining.get(0).getQuantity()).isEqualTo(5);
		assertThat(orderPersistence.count()).isZero();
	}

	private Member createMember(String emailPrefix) {
		return Member.createUser(emailPrefix + "@example.com", "password123", "ucart");
	}

	private Product createProduct(String name, int price) {
		return Product.create(name, price, null, null, ProductStatus.ON_SALE);
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.create(product.getId(), quantity);
	}
}
