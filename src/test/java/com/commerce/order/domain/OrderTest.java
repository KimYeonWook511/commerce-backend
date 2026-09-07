package com.commerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;

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
		assertThat(order.getOrderItems()).isEmpty();
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
	void cancelBeforePayment_whenInitStatus_changeToCanceled() {
		// given
		Order order = Order.create(1L);

		// when
		order.cancelBeforePayment();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("결제 완료 주문은 이 관문으로 취소되지 않는다 — 잔여수량을 보는 관문이 따로 받는다")
	@Test
	void cancelBeforePayment_whenPaidStatus_throwException() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.PAID);

		// when & then
		assertThatThrownBy(order::cancelBeforePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("주문이 이미 취소된 상태면 취소에 실패한다")
	@Test
	void cancelBeforePayment_whenAlreadyCanceled_throwException() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.CANCELED);

		// when & then
		assertThatThrownBy(order::cancelBeforePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	@DisplayName("초기 상태가 아니면 취소에 실패한다")
	@Test
	void cancelBeforePayment_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(1L, 1, 1000);
		setStatus(order, OrderStatus.RECEIVED);

		// when & then
		assertThatThrownBy(order::cancelBeforePayment)
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

	@DisplayName("주문이 이미 PAID이면 결제 완료 처리 시 ORDER_ALREADY_PAID를 던진다")
	@Test
	void completePayment_whenStatusPaid_throwOrderAlreadyPaid() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.PAID);

		// when & then
		assertThatThrownBy(order::completePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_PAID);
			});
	}

	@DisplayName("주문이 CANCELED이면 결제 완료 처리 시 ORDER_CANCELED_FOR_PAYMENT를 던진다")
	@Test
	void completePayment_whenStatusCanceled_throwOrderCanceledForPayment() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.CANCELED);

		// when & then
		assertThatThrownBy(order::completePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCELED_FOR_PAYMENT);
			});
	}

	@DisplayName("주문이 그 외 비-INIT 상태이면 결제 완료 처리 시 ORDER_INVALID_STATE_FOR_PAYMENT를 던진다")
	@Test
	void completePayment_whenStatusOtherNonInit_throwOrderInvalidStateForPayment() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.RECEIVED);

		// when & then
		assertThatThrownBy(order::completePayment)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATE_FOR_PAYMENT);
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

	@DisplayName("결제완료 주문은 취소 가능 판정을 통과한다")
	@Test
	void checkCancellable_whenPaidStatus_doNothing() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.PAID);

		// when
		order.checkCancellable();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("결제 전 주문은 취소 가능 판정에서 거부된다")
	@Test
	void checkCancellable_whenInitStatus_throwException() {
		// given
		Order order = Order.create(1L);

		// when & then
		assertThatThrownBy(order::checkCancellable)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	@DisplayName("취소로 종착한 주문은 취소 가능 판정에서 거부된다")
	@Test
	void checkCancellable_whenCanceledStatus_throwException() {
		// given
		Order order = Order.create(1L);
		setStatus(order, OrderStatus.CANCELED);

		// when & then
		assertThatThrownBy(order::checkCancellable)
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	private void setStatus(Order order, OrderStatus status) {
		ReflectionTestUtils.setField(order, "status", status);
	}
}
