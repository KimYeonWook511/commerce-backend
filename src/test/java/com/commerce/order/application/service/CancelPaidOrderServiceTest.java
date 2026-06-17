package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderTransactionResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.service.GetOrCreateCancelPaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.stock.application.service.IncreaseStockService;

@ExtendWith(MockitoExtension.class)
class CancelPaidOrderServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private GetOrCreateCancelPaymentService getOrCreateCancelPaymentService;

	@Mock
	private IncreaseStockService increaseStockService;

	@InjectMocks
	private CancelPaidOrderService cancelPaidOrderService;

	@DisplayName("PAID 주문을 취소하면 환불 의도가 영속화되고 주문이 CANCELED로 전이된다")
	@Test
	void cancelPaidOrder_whenPaidStatus_cancelOrderAndCreateCancelPayment() {
		// given
		Order order = createPaidOrderWithItems();
		Payment approvePayment = createApproveSucceededPayment(order.getId());
		Payment cancelPayment = createCancelRequestedPayment(order.getId());

		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnconfirmedApproveByOrderId(100L)).willReturn(false);
		given(paymentRepository.findApproveSucceededByOrderId(100L)).willReturn(Optional.of(approvePayment));
		given(getOrCreateCancelPaymentService.getOrCreate(anyLong(), anyString(), any(), anyString(), anyInt()))
			.willReturn(cancelPayment);

		// when
		CancelPaidOrderTransactionResult result = cancelPaidOrderService.cancelPaidOrder(1L, 100L);

		// then
		assertThat(result.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.cancelPayment()).isSameAs(cancelPayment);
		assertThat(result.toResult(OrderCancelRefundStatus.COMPLETED).getRefundStatus())
			.isEqualTo(OrderCancelRefundStatus.COMPLETED);
	}

	@DisplayName("PAID 주문 취소 시 재고 복구는 상품 ID 정렬 순서로 호출된다")
	@Test
	void cancelPaidOrder_whenPaidStatus_restoreStockInProductIdOrder() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(5L, 2, 1000);
		order.addOrderItem(2L, 3, 500);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);

		Payment approvePayment = createApproveSucceededPayment(100L);
		Payment cancelPayment = createCancelRequestedPayment(100L);

		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnconfirmedApproveByOrderId(100L)).willReturn(false);
		given(paymentRepository.findApproveSucceededByOrderId(100L)).willReturn(Optional.of(approvePayment));
		given(getOrCreateCancelPaymentService.getOrCreate(anyLong(), anyString(), any(), anyString(), anyInt()))
			.willReturn(cancelPayment);

		// when
		cancelPaidOrderService.cancelPaidOrder(1L, 100L);

		// then - productId 2가 먼저, 5가 나중
		org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(increaseStockService);
		inOrder.verify(increaseStockService).increase(2L, 3);
		inOrder.verify(increaseStockService).increase(5L, 2);
	}

	@DisplayName("주문이 없으면 취소에 실패한다")
	@Test
	void cancelPaidOrder_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
	}

	@DisplayName("PAID 상태가 아닌 주문은 이 service에서 거부한다")
	@Test
	void cancelPaidOrder_whenNotPaidStatus_throwException() {
		// given
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "id", 100L);
		// INIT 상태는 CancelOrderService가 처리하므로 이 service에 들어오면 안 됨

		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));
	}

	@DisplayName("미확정 APPROVE가 있는 주문은 환불 불가 예외를 던진다")
	@Test
	void cancelPaidOrder_whenUnconfirmedApproveExists_throwException() {
		// given
		Order order = createPaidOrderWithItems();
		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnconfirmedApproveByOrderId(100L)).willReturn(true);

		// when & then
		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE));
	}

	@DisplayName("SUCCEEDED APPROVE가 없는 PAID 주문은 정합성 오류로 처리된다")
	@Test
	void cancelPaidOrder_whenApproveSucceededNotFound_throwException() {
		// given
		Order order = createPaidOrderWithItems();
		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnconfirmedApproveByOrderId(100L)).willReturn(false);
		given(paymentRepository.findApproveSucceededByOrderId(100L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_TARGET_NOT_FOUND));
	}

	@DisplayName("환불 의도 금액은 approve 결제 금액을 그대로 사용한다")
	@Test
	void cancelPaidOrder_whenCanceling_cancelAmountEqualsApproveAmount() {
		// given
		Order order = createPaidOrderWithItems();
		Payment approvePayment = createApproveSucceededPayment(order.getId());
		Payment cancelPayment = createCancelRequestedPayment(order.getId());

		given(orderRepository.findByIdAndMemberIdForUpdate(100L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnconfirmedApproveByOrderId(100L)).willReturn(false);
		given(paymentRepository.findApproveSucceededByOrderId(100L)).willReturn(Optional.of(approvePayment));
		given(getOrCreateCancelPaymentService.getOrCreate(anyLong(), anyString(), any(), anyString(), anyInt()))
			.willReturn(cancelPayment);

		// when
		cancelPaidOrderService.cancelPaidOrder(1L, 100L);

		// then - approve.amount(10000)가 cancelAmount로 전달됨
		then(getOrCreateCancelPaymentService).should().getOrCreate(
			anyLong(),
			anyString(),
			any(),
			anyString(),
			org.mockito.ArgumentMatchers.eq(approvePayment.getAmount())
		);
	}

	// ── 헬퍼 ──

	private Order createPaidOrderWithItems() {
		Order order = Order.create(1L);
		order.addOrderItem(10L, 2, 1000);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		return order;
	}

	private Payment createApproveSucceededPayment(Long orderId) {
		Payment payment = Payment.createCancelRequested(orderId, "PAY-KEY-1", "PG-PAY-1", 10000,
			PaymentProvider.NAVERPAY);
		// APPROVE SUCCEEDED 상태로 설정
		ReflectionTestUtils.setField(payment, "type", PaymentType.APPROVE);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.SUCCEEDED);
		ReflectionTestUtils.setField(payment, "amount", 10000);
		ReflectionTestUtils.setField(payment, "merchantPayKey", "PAY-KEY-1");
		ReflectionTestUtils.setField(payment, "provider", PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "pgPaymentId", "PG-PAY-1");
		return payment;
	}

	private Payment createCancelRequestedPayment(Long orderId) {
		return Payment.createCancelRequested(orderId, "PAY-KEY-1", "PG-PAY-1", 10000, PaymentProvider.NAVERPAY);
	}
}
