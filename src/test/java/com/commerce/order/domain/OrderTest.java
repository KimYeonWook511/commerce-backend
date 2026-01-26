package com.commerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.member.domain.Member;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
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
		assertThat(order.getOrderItems()).isEmpty();
	}

	@DisplayName("주문 상품을 추가하면 주문 상품이 등록되고 총액이 증가한다")
	@Test
	void addOrderItem_whenCalled_appendOrderItemAndIncreaseTotalPrice() {
		// given
		Order order = Order.create(createMember());
		Product product = createProduct("product-1", 1500);

		// when
		order.addOrderItem(product, 2);

		// then
		assertThat(order.getOrderItems()).hasSize(1);
		assertThat(order.getTotalPrice()).isEqualTo(3000);
		assertThat(order.getOrderItems().get(0).getOrder()).isEqualTo(order);
		assertThat(order.getOrderItems().get(0).getProduct()).isEqualTo(product);
		assertThat(order.getOrderItems().get(0).getQuantity()).isEqualTo(2);
	}

	@DisplayName("여러 상품을 추가하면 총액이 누적된다")
	@Test
	void addOrderItem_whenMultipleItems_accumulateTotalPrice() {
		// given
		Order order = Order.create(createMember());
		Product product1 = createProduct("product-1", 1000);
		Product product2 = createProduct("product-2", 2000);

		// when
		order.addOrderItem(product1, 2);
		order.addOrderItem(product2, 1);

		// then
		assertThat(order.getOrderItems()).hasSize(2);
		assertThat(order.getTotalPrice()).isEqualTo(4000);
	}

	@DisplayName("주문이 초기 상태면 취소된다")
	@Test
	void cancel_whenInitStatus_changeToCanceled() {
		// given
		Order order = Order.create(createMember());

		// when
		order.cancel();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("주문이 초기 상태가 아니면 취소에 실패한다")
	@Test
	void cancel_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(createMember());
		order.addOrderItem(createProduct("product-1", 1000), 1);
		setStatus(order, OrderStatus.RECEIVED);

		// when & then
		assertThatThrownBy(order::cancel)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
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

	private void setStatus(Order order, OrderStatus status) {
		org.springframework.test.util.ReflectionTestUtils.setField(order, "status", status);
	}
}
