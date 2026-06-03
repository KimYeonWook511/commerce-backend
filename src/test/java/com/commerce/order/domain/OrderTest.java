package com.commerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;

class OrderTest {

	@DisplayName("주문을 생성하면 상태가 INIT이고 총액이 0이다")
	@Test
	void create_whenMemberProvided_initializeWithInitStatusAndZeroTotalPrice() {
		// when
		Order order = Order.create(1L);

		// then
		assertThat(order.getMemberId()).isEqualTo(1L);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.INIT);
		assertThat(order.getTotalPrice()).isZero();
		assertThat(order.getMerchantPayKey()).isNull();
		assertThat(order.getOrderItems()).isEmpty();
	}

	@DisplayName("merchantPayKey가 없으면 주문에 merchantPayKey를 저장한다")
	@Test
	void assignMerchantPayKey_whenNull_assignMerchantPayKey() {
		// given
		Order order = Order.create(1L);

		// when
		order.assignMerchantPayKey("PAY-123");

		// then
		assertThat(order.getMerchantPayKey()).isEqualTo("PAY-123");
	}

	@DisplayName("merchantPayKey가 이미 있으면 기존 값을 유지한다")
	@Test
	void assignMerchantPayKey_whenAlreadyAssigned_keepExistingValue() {
		// given
		Order order = Order.create(1L);
		order.assignMerchantPayKey("PAY-123");

		// when
		order.assignMerchantPayKey("PAY-456");

		// then
		assertThat(order.getMerchantPayKey()).isEqualTo("PAY-123");
	}

	@DisplayName("주문 상품을 추가하면 주문 상품이 등록되고 총액이 증가한다")
	@Test
	void addOrderItem_whenCalled_appendOrderItemAndIncreaseTotalPrice() {
		// given
		Order order = Order.create(1L);

		// when
		order.addOrderItem(10L, 2, 1500);

		// then
		assertThat(order.getOrderItems()).hasSize(1);
		assertThat(order.getTotalPrice()).isEqualTo(3000);
		assertThat(order.getOrderItems().get(0).getOrder()).isEqualTo(order);
		assertThat(order.getOrderItems().get(0).getProductId()).isEqualTo(10L);
		assertThat(order.getOrderItems().get(0).getQuantity()).isEqualTo(2);
		assertThat(order.getOrderItems().get(0).getUnitPrice()).isEqualTo(1500);
	}

	@DisplayName("서로 다른 unitPrice 로 두 번 addOrderItem 하면 각각의 unitPrice 가 보존된다")
	@Test
	void addOrderItem_whenMultipleItemsWithDifferentUnitPrice_preserveEachUnitPrice() {
		// given
		Order order = Order.create(1L);

		// when
		order.addOrderItem(10L, 1, 1000);
		order.addOrderItem(10L, 1, 2000);

		// then
		assertThat(order.getOrderItems().get(0).getUnitPrice()).isEqualTo(1000);
		assertThat(order.getOrderItems().get(1).getUnitPrice()).isEqualTo(2000);
	}

	@DisplayName("여러 상품을 추가하면 총액이 누적된다")
	@Test
	void addOrderItem_whenMultipleItems_accumulateTotalPrice() {
		// given
		Order order = Order.create(1L);

		// when
		order.addOrderItem(1L, 2, 1000);
		order.addOrderItem(2L, 1, 2000);

		// then
		assertThat(order.getOrderItems()).hasSize(2);
		assertThat(order.getTotalPrice()).isEqualTo(4000);
	}

	@DisplayName("주문이 초기 상태면 취소된다")
	@Test
	void cancel_whenInitStatus_changeToCanceled() {
		// given
		Order order = Order.create(1L);

		// when
		order.cancel();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("주문이 초기 상태가 아니면 취소에 실패한다")
	@Test
	void cancel_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(1L, 1, 1000);
		setStatus(order, OrderStatus.RECEIVED);

		// when & then
		assertThatThrownBy(order::cancel)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	@DisplayName("주문이 초기 상태면 결제 완료로 변경된다")
	@Test
	void completePayment_whenInitStatus_changeToPaid() {
		// given
		Order order = Order.create(1L);

		// when
		order.completePayment();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("주문이 초기 상태가 아니면 결제 완료 처리에 실패한다")
	@Test
	void completePayment_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.RECEIVED);

		// when & then
		assertThatThrownBy(order::completePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAID_NOT_ALLOWED);
			});
	}

	@DisplayName("주문이 초기 상태면 결제를 진행할 수 있다")
	@Test
	void checkPayable_whenInitStatus_doNothing() {
		// given
		Order order = Order.create(1L);

		// when
		order.checkPayable();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.INIT);
	}

	@DisplayName("주문이 초기 상태가 아니면 결제를 진행할 수 없다")
	@Test
	void checkPayable_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.PAID);

		// when & then
		assertThatThrownBy(order::checkPayable)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
	}

	private void setStatus(Order order, OrderStatus status) {
		ReflectionTestUtils.setField(order, "status", status);
	}
}
