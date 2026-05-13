package com.commerce.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import({JpaConfig.class, OrderRepositoryAdapter.class})
@ActiveProfiles("test")
class OrderAuditingTest {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JpaMemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("주문을 저장하면 생성/수정 시간이 채워진다")
	@Test
	void save_whenOrderCreated_setCreatedAtAndUpdatedAt() {
		// given
		Member member = memberRepository.save(createMember());
		Order order = Order.create(member);

		// when
		Order saved = orderRepository.save(order);
		entityManager.flush();

		// then
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	@DisplayName("주문이 수정되면 수정 시간이 갱신된다")
	@Test
	void save_whenOrderUpdated_refreshUpdatedAt() throws Exception {
		// given
		Member member = memberRepository.save(createMember());
		Product product = productRepository.save(createProduct());
		Order order = orderRepository.save(Order.create(member));
		entityManager.flush();
		LocalDateTime createdAt = order.getCreatedAt();
		LocalDateTime updatedAt = order.getUpdatedAt();

		// when
		TimeUnit.MILLISECONDS.sleep(1);
		order.addOrderItem(product, 1);
		Order updated = orderRepository.save(order);
		entityManager.flush();

		// then
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getUpdatedAt()).isAfter(updatedAt);
	}

	private Member createMember() {
		return Member.builder()
			.email("auditing@example.com")
			.password("password123")
			.username("auditor")
			.build();
	}

	private Product createProduct() {
		return Product.builder()
			.name("auditing-product")
			.price(1000)
			.status(ProductStatus.ON_SALE)
			.build();
	}
}
