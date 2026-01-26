package com.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.redis.OrderIdempotencyStatus;
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCreateResponse;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;
import com.commerce.test.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceIdempotencyTest {

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

	@Autowired
	private StringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void registerRedis(DynamicPropertyRegistry registry) {
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

	@DisplayName("같은 멱등키로 재요청하면 주문이 중복 생성되지 않는다")
	@Test
	void createOrder_whenSameIdempotencyKey_returnSameOrder() {
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
				.name("product-1")
				.price(1000)
				.build()
		);
		stockRepository.save(
			Stock.builder()
				.product(product)
				.quantity(10)
				.build()
		);

		OrderCreateServiceRequest request = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.idempotencyKey("idem-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// when
		OrderCreateResponse first = orderService.createOrder(request);
		OrderCreateResponse second = orderService.createOrder(request);

		// then
		assertThat(first.getOrderId()).isEqualTo(second.getOrderId());
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(orderItemRepository.count()).isEqualTo(1);
		assertThat(stockRepository.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
	}

	@DisplayName("처리 중인 멱등키면 주문 생성에 실패한다")
	@Test
	void createOrder_whenIdempotencyProcessing_throwException() {
		// given
		Member member = memberRepository.save(
			Member.builder()
				.email("test2@example.com")
				.password("password123")
				.username("user2")
				.build()
		);
		Product product = productRepository.save(
			Product.builder()
				.name("product-2")
				.price(2000)
				.build()
		);
		stockRepository.save(
			Stock.builder()
				.product(product)
				.quantity(10)
				.build()
		);

		OrderCreateServiceRequest request = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.idempotencyKey("processing-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(1).build()))
			.build();

		// when
		String redisKey = "order:idempotency:" + member.getId() + ":processing-key";
		redisTemplate.opsForValue().set(
			redisKey,
			OrderIdempotencyStatus.PROCESSING.value(),
			Duration.ofSeconds(60)
		);

		// then
		assertThatThrownBy(() -> orderService.createOrder(request))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
			});
	}
}
