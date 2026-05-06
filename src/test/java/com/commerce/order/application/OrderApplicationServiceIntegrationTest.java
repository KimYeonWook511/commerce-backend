package com.commerce.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;
import com.commerce.test.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
class OrderApplicationServiceIntegrationTest {

	@Autowired
	private OrderCreateService orderCreateService;

	@Autowired
	private OrderCancelService orderCancelService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("재고가 부족하면 주문이 실패하고 취소 후 다시 주문할 수 있다")
	@Test
	void createOrder_whenOutOfStock_thenCancel_thenCreateSuccess() {
		// given
		Member member = memberRepository.save(
			Member.builder()
				.email("test@example.com")
				.password("password123")
				.username("user1")
				.build()
		);
		Product product = productRepository.save(
			Product.builder()
				.name("recovery-product")
				.price(1000)
				.status(ProductStatus.ON_SALE)
				.build()
		);
		stockRepository.save(
			Stock.builder()
				.product(product)
				.quantity(1)
				.build()
		);

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
		assertThat(stockRepository.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
	}
}
