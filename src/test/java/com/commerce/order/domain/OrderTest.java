package com.commerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.member.domain.Member;
import com.commerce.product.domain.Product;

class OrderTest {

	@DisplayName("주문을 생성하면 상태가 INIT이고 총액이 0이다")
	@Test
	void create_whenMemberProvided_initializeWithInitStatusAndZeroTotalPrice() {
		// given
		Member member = createMember();

		// when
		Order order = Order.create(member);

		// then
		assertThat(order.getMember()).isEqualTo(member);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.INIT);
		assertThat(order.getTotalPrice()).isZero();
		assertThat(order.getOrderProducts()).isEmpty();
	}

	@DisplayName("주문 상품을 추가하면 주문 상품이 등록되고 총액이 증가한다")
	@Test
	void addOrderProduct_whenCalled_appendOrderProductAndIncreaseTotalPrice() {
		// given
		Order order = Order.create(createMember());
		Product product = createProduct("product-1", 1500);

		// when
		order.addOrderProduct(product, 2);

		// then
		assertThat(order.getOrderProducts()).hasSize(1);
		assertThat(order.getTotalPrice()).isEqualTo(3000);
		assertThat(order.getOrderProducts().get(0).getOrder()).isEqualTo(order);
		assertThat(order.getOrderProducts().get(0).getProduct()).isEqualTo(product);
		assertThat(order.getOrderProducts().get(0).getQuantity()).isEqualTo(2);
	}

	@DisplayName("여러 상품을 추가하면 총액이 누적된다")
	@Test
	void addOrderProduct_whenMultipleItems_accumulateTotalPrice() {
		// given
		Order order = Order.create(createMember());
		Product product1 = createProduct("product-1", 1000);
		Product product2 = createProduct("product-2", 2000);

		// when
		order.addOrderProduct(product1, 2);
		order.addOrderProduct(product2, 1);

		// then
		assertThat(order.getOrderProducts()).hasSize(2);
		assertThat(order.getTotalPrice()).isEqualTo(4000);
	}

	private Member createMember() {
		return Member.builder()
			.email("test@example.com")
			.password("password123")
			.username("tester")
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.build();
	}
}
