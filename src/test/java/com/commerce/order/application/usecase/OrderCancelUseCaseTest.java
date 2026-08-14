package com.commerce.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.service.CancelOrderService;
import com.commerce.order.application.service.CancelPaidOrderService;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderTransactionResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.legacy.application.port.result.CancelOutcome;
import com.commerce.payment.legacy.application.usecase.RefundExecutionUseCase;
import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.naverpay.application.port.NaverPayGateway;

@ExtendWith(MockitoExtension.class)
class OrderCancelUseCaseTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CancelOrderService cancelOrderService;

	@Mock
	private CancelPaidOrderService cancelPaidOrderService;

	@Mock
	private RefundExecutionUseCase refundExecutionUseCase;

	@Mock
	private NaverPayGateway naverPayGateway;

	@InjectMocks
	private CancelOrderUseCase cancelOrderUseCase;

	@DisplayName("INIT 주문 취소는 기존 CancelOrderService에 위임한다")
	@Test
	void cancel_whenInitStatus_delegateToCancelOrderService() {
		// given
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "id", 100L);
		OrderCancelResult expected = OrderCancelResult.from(order);

		given(orderRepository.findByIdAndMemberId(100L, 1L)).willReturn(Optional.of(order));
		given(cancelOrderService.cancelOrder(1L, 100L)).willReturn(expected);

		// when
		OrderCancelResult result = cancelOrderUseCase.cancel(1L, 100L);

		// then
		assertThat(result).isSameAs(expected);
		then(cancelPaidOrderService).shouldHaveNoInteractions();
	}

	@DisplayName("PAID 주문 취소는 CancelPaidOrderService를 호출하고 best-effort PG 환불을 실행한다")
	@Test
	void cancel_whenPaidStatus_cancelWithRefund() {
		// given
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);

		Payment cancelPayment = createCancelRequestedPayment(100L);
		CancelPaidOrderTransactionResult txResult = new CancelPaidOrderTransactionResult(order, cancelPayment);

		given(orderRepository.findByIdAndMemberId(100L, 1L)).willReturn(Optional.of(order));
		given(cancelPaidOrderService.cancelPaidOrder(1L, 100L)).willReturn(txResult);
		given(refundExecutionUseCase.execute(any(), any())).willReturn(CancelOutcome.processing());

		// when
		OrderCancelResult result = cancelOrderUseCase.cancel(1L, 100L);

		// then
		assertThat(result.getOrderId()).isEqualTo(100L);
		then(refundExecutionUseCase).should().execute(any(), any());
		then(cancelOrderService).shouldHaveNoInteractions();
	}

	@DisplayName("PAID 취소 후 PG 환불이 SUCCESS면 refundStatus가 COMPLETED이다")
	@Test
	void cancel_whenPaidAndPgSucceeded_refundStatusCompleted() {
		// given
		Order order = createCanceledOrder();
		Payment cancelPayment = createCancelRequestedPayment(100L);
		CancelPaidOrderTransactionResult txResult = new CancelPaidOrderTransactionResult(order, cancelPayment);

		Order paidOrder = Order.create(1L);
		ReflectionTestUtils.setField(paidOrder, "id", 100L);
		ReflectionTestUtils.setField(paidOrder, "status", OrderStatus.PAID);

		given(orderRepository.findByIdAndMemberId(100L, 1L)).willReturn(Optional.of(paidOrder));
		given(cancelPaidOrderService.cancelPaidOrder(1L, 100L)).willReturn(txResult);
		// execute()가 PG SUCCESS 결과를 반환 → COMPLETED
		given(refundExecutionUseCase.execute(any(), any())).willReturn(CancelOutcome.success());

		// when
		OrderCancelResult result = cancelOrderUseCase.cancel(1L, 100L);

		// then - execute()가 SUCCESS를 반환하므로 COMPLETED
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.COMPLETED);
	}

	@DisplayName("PAID 취소 후 PG 환불이 처리 중이면 refundStatus가 IN_PROGRESS이다")
	@Test
	void cancel_whenPaidAndPgPending_refundStatusInProgress() {
		// given
		Order order = createCanceledOrder();
		Payment cancelPayment = createCancelRequestedPayment(100L);
		CancelPaidOrderTransactionResult txResult = new CancelPaidOrderTransactionResult(order, cancelPayment);

		Order paidOrder = Order.create(1L);
		ReflectionTestUtils.setField(paidOrder, "id", 100L);
		ReflectionTestUtils.setField(paidOrder, "status", OrderStatus.PAID);

		given(orderRepository.findByIdAndMemberId(100L, 1L)).willReturn(Optional.of(paidOrder));
		given(cancelPaidOrderService.cancelPaidOrder(1L, 100L)).willReturn(txResult);
		// execute()가 PROCESSING 결과를 반환 → IN_PROGRESS
		given(refundExecutionUseCase.execute(any(), any())).willReturn(CancelOutcome.processing());

		// when
		OrderCancelResult result = cancelOrderUseCase.cancel(1L, 100L);

		// then
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("주문이 없으면 예외를 던진다")
	@Test
	void cancel_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findByIdAndMemberId(100L, 1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> cancelOrderUseCase.cancel(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
	}

	// ── 헬퍼 ──

	private Order createCanceledOrder() {
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELED);
		return order;
	}

	private Payment createCancelRequestedPayment(Long orderId) {
		return Payment.createCancelRequested(orderId, "PAY-KEY-1", "PG-PAY-1", 10000, PaymentProvider.NAVERPAY);
	}
}
