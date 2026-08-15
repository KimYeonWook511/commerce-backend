package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.stock.application.service.IncreaseStockService;

@ExtendWith(MockitoExtension.class)
class CancelPaidOrderServiceTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long ORDER_ID = 100L;
	private static final int APPROVED_AMOUNT = 10_000;
	private static final String IDEMPOTENCY_KEY = "cancel-key-1";

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private RefundRepository refundRepository;

	@Mock
	private IncreaseStockService increaseStockService;

	@InjectMocks
	private CancelPaidOrderService cancelPaidOrderService;

	@DisplayName("결제된 주문을 취소하면 주문이 취소되고 되돌릴 환불 사건이 함께 열린다")
	@Test
	void cancelPaidOrder_whenPaid_cancelsOrderAndOpensRefund() {
		Order order = paidOrder();
		Payment payment = succeededPayment();
		givenCancelable(order, payment);

		CancelPaidOrderResult result =
			cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.refund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
		assertThat(result.refund().getRequester()).isEqualTo(RefundRequester.MEMBER);
		assertThat(result.refund().getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
		assertThat(result.refund().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
		assertThat(result.refund().getRefundKey()).isNotBlank();
	}

	@DisplayName("환불액은 승인 금액 전액이고 남은 취소 가능 금액은 그만큼 줄어든다")
	@Test
	void cancelPaidOrder_whenPaid_refundsApprovedAmountAndLeavesNoRemaining() {
		Order order = paidOrder();
		Payment payment = succeededPayment();
		givenCancelable(order, payment);

		CancelPaidOrderResult result =
			cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.refund().getAmount()).isEqualTo(APPROVED_AMOUNT);
		assertThat(result.remainingAmount()).isZero();
		assertThat(result.payment().getTotalRefundedAmount()).isEqualTo(APPROVED_AMOUNT);
	}

	@DisplayName("같은 요청 키로 다시 취소해도 앞서 만든 환불이 그대로 돌아오고 누적 환불액이 다시 오르지 않는다")
	@Test
	void cancelPaidOrder_whenSameIdempotencyKey_returnsExistingRefund() {
		Order order = paidOrder();
		Payment payment = succeededPayment();
		Refund existing = Refund.open(payment.getId(), "RF-existing", RefundRequester.MEMBER,
			IDEMPOTENCY_KEY, APPROVED_AMOUNT, RefundReason.ORDER_CANCELED);
		ReflectionTestUtils.setField(payment, "totalRefundedAmount", APPROVED_AMOUNT);

		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(ORDER_ID)).willReturn(false);
		given(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID, ORDER_ID))
			.willReturn(Optional.of(payment));
		given(refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			payment.getId(), RefundRequester.MEMBER, IDEMPOTENCY_KEY)).willReturn(Optional.of(existing));
		given(refundRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

		CancelPaidOrderResult result =
			cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.refund()).isSameAs(existing);
		assertThat(result.payment().getTotalRefundedAmount()).isEqualTo(APPROVED_AMOUNT);
	}

	@DisplayName("재고 복구는 상품 ID 정렬 순서로, 환불 의도를 연 뒤에 호출된다")
	@Test
	void cancelPaidOrder_whenPaid_restoresStockLastInProductIdOrder() {
		Order order = Order.create(MEMBER_ID);
		order.addOrderItem(5L, 2, 1_000);
		order.addOrderItem(2L, 3, 500);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		Payment payment = succeededPayment();
		givenCancelable(order, payment);

		cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		// 재고 행 락을 쥐는 시간을 줄이려고 이 묶음의 맨 뒤에 둔다.
		InOrder inOrder = Mockito.inOrder(refundRepository, increaseStockService, orderRepository);
		inOrder.verify(refundRepository).findByPaymentIdAndRequesterAndIdempotencyKey(any(), any(), any());
		inOrder.verify(increaseStockService).increase(2L, 3);
		inOrder.verify(increaseStockService).increase(5L, 2);
		inOrder.verify(orderRepository).save(order);
	}

	@DisplayName("남의 주문 번호로는 취소할 수 없다")
	@Test
	void cancelPaidOrder_whenOrderNotFound_throws() {
		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
	}

	@DisplayName("결제되지 않은 주문은 이 자리에서 거부한다")
	@Test
	void cancelPaidOrder_whenNotPaid_throws() {
		Order order = Order.create(MEMBER_ID);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));

		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));
	}

	@DisplayName("승인 결과를 모르는 결제가 걸린 주문은 취소할 수 없다")
	@Test
	void cancelPaidOrder_whenUnknownPaymentExists_throws() {
		Order order = paidOrder();
		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(ORDER_ID)).willReturn(true);

		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE));
	}

	@DisplayName("성공한 결제를 찾지 못하면 정합성 오류로 다룬다")
	@Test
	void cancelPaidOrder_whenSucceededPaymentNotFound_throws() {
		Order order = paidOrder();
		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(ORDER_ID)).willReturn(false);
		given(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID, ORDER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_TARGET_NOT_FOUND));
	}

	@DisplayName("승인 금액이 없는 결제는 얼마를 돌려줄지 정해지지 않아 취소할 수 없다")
	@Test
	void cancelPaidOrder_whenApprovedAmountMissing_throws() {
		Order order = paidOrder();
		Payment payment = Payment.start(ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "idem-1", APPROVED_AMOUNT);
		ReflectionTestUtils.setField(payment, "id", 7L);

		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(ORDER_ID)).willReturn(false);
		given(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID, ORDER_ID))
			.willReturn(Optional.of(payment));

		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE));
	}

	// ── 헬퍼 ──

	private void givenCancelable(Order order, Payment payment) {
		given(orderRepository.findByIdAndMemberIdForUpdate(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(ORDER_ID)).willReturn(false);
		given(paymentRepository.findSucceededByMemberIdAndOrderId(MEMBER_ID, ORDER_ID))
			.willReturn(Optional.of(payment));
		given(refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			payment.getId(), RefundRequester.MEMBER, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
		given(refundRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	private Order paidOrder() {
		Order order = Order.create(MEMBER_ID);
		order.addOrderItem(10L, 2, 5_000);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		return order;
	}

	private Payment succeededPayment() {
		Payment payment = Payment.start(
			ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "idem-1", APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		payment.succeed(APPROVED_AMOUNT, "pg-tx-1");
		ReflectionTestUtils.setField(payment, "id", 7L);
		return payment;
	}
}
