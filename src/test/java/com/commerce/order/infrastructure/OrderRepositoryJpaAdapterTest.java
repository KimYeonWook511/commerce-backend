package com.commerce.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.JpaMemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import({JpaConfig.class, OrderRepositoryAdapter.class})
@ActiveProfiles("test")
class OrderRepositoryJpaAdapterTest {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JpaMemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("주문 ID로 주문을 조회하면 주문상품을 함께 조회한다")
	@Test
	void findByIdWithItems_whenOrderExists_returnOrderWithAssociations() {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 2, product.getPrice());
		Order saved = orderRepository.save(order);
		entityManager.flush();
		entityManager.clear();

		// when
		Order result = orderRepository.findByIdWithItems(saved.getId()).orElseThrow();

		// then
		assertThat(result.getMemberId()).isEqualTo(member.getId());
		assertThat(result.getOrderItems()).hasSize(1);
		assertThat(result.getOrderItems().getFirst().getProductId()).isEqualTo(product.getId());
		assertThat(result.getOrderItems().getFirst().getUnitPrice()).isEqualTo(product.getPrice());
	}

	@DisplayName("만료 대상 조회 시 상태와 cutoff, lastId 조건에 맞는 주문만 ID 오름차순으로 조회한다")
	@Test
	void findExpiredOrdersAfterId_whenConditionMatches_returnExpiredOrders() {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());

		Order first = createInitOrder(member, product);
		Order second = createInitOrder(member, product);
		Order canceled = createInitOrder(member, product);
		canceled.cancel();

		Order savedFirst = orderRepository.save(first);
		Order savedSecond = orderRepository.save(second);
		orderRepository.save(canceled);
		entityManager.flush();

		LocalDateTime cutoff = LocalDateTime.now().plusMinutes(10);

		// when
		List<Order> results = orderRepository.findExpiredOrdersAfterId(
			OrderStatus.INIT,
			cutoff,
			savedFirst.getId(),
			10
		);

		// then
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().getId()).isEqualTo(savedSecond.getId());
		assertThat(results.getFirst().getStatus()).isEqualTo(OrderStatus.INIT);
	}

	@DisplayName("만료 대상 조회 조건에 맞는 주문이 없으면 빈 리스트를 반환한다")
	@Test
	void findExpiredOrdersAfterId_whenNoMatch_returnEmptyList() {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());
		Order order = createInitOrder(member, product);
		orderRepository.save(order);
		entityManager.flush();

		LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);

		// when
		List<Order> results = orderRepository.findExpiredOrdersAfterId(
			OrderStatus.INIT,
			cutoff,
			0L,
			10
		);

		// then
		assertThat(results).isEmpty();
	}

	@DisplayName("merchantPayKey와 회원 ID로 주문을 조회한다")
	@Test
	void findByMerchantPayKeyAndMemberId_whenOrderExists_returnOrder() {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());
		Order order = createInitOrder(member, product);
		order.assignMerchantPayKey("PAY-REPO-1");
		Order saved = orderRepository.save(order);
		entityManager.flush();
		entityManager.clear();

		// when
		Order result = orderRepository.findByMerchantPayKeyAndMemberId("PAY-REPO-1", member.getId()).orElseThrow();

		// then
		assertThat(result.getId()).isEqualTo(saved.getId());
	}

	@DisplayName("merchantPayKey로 주문을 조회한다")
	@Test
	void findByMerchantPayKey_whenOrderExists_returnOrder() {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());
		Order order = createInitOrder(member, product);
		order.assignMerchantPayKey("PAY-REPO-2");
		Order saved = orderRepository.save(order);
		entityManager.flush();
		entityManager.clear();

		// when
		Order result = orderRepository.findByMerchantPayKey("PAY-REPO-2").orElseThrow();

		// then
		assertThat(result.getId()).isEqualTo(saved.getId());
	}

	private Order createInitOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("order-repo-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Product.builder()
			.name("order-repo-product-" + suffix)
			.price(1000)
			.status(ProductStatus.ON_SALE)
			.build();
	}
}
