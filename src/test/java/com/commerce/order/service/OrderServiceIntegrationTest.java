package com.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCreateResponse;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;
import com.commerce.test.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceIntegrationTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private OrderRepository orderRepository;

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
				.build()
		);
		stockRepository.save(
			Stock.builder()
				.product(product)
				.quantity(1)
				.build()
		);

		OrderCreateServiceRequest firstRequest = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.idempotencyKey("first-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();
		OrderCreateServiceRequest secondRequest = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.idempotencyKey("second-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();

		// when
		OrderCreateResponse created = orderService.createOrder(firstRequest);

		// then
		assertThatThrownBy(() -> orderService.createOrder(secondRequest))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});

		orderService.cancelOrder(member.getId(), created.getOrderId());

		OrderCreateResponse recreated = orderService.createOrder(secondRequest);
		assertThat(recreated.getOrderId()).isNotNull();
		assertThat(stockRepository.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
	}
}
