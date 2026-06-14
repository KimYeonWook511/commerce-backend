package com.commerce.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@InjectMocks
	private PaymentApprovalService paymentApprovalService;

	@DisplayName("succeedApproval 호출 시 payment가 SUCCEEDED가 되고 order가 PAID가 된다")
	@Test
	void succeedApproval_whenPaymentRequested_completeAndReturn() {
		// given
		long orderId = 1L;
		long memberId = 1L;
		String merchantPayKey = "PAY-1";
		String pgPaymentId = "pg-payment-id";
		LocalDateTime now = LocalDateTime.now();

		PaymentReservation reservation = PaymentReservation.createReserved(
			orderId, memberId, 1000, PaymentProvider.NAVERPAY, merchantPayKey, now.plusMinutes(15));
		Order order = createOrder(orderId, 1000);
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);

		given(paymentRepository.findApprovePayment(eq(merchantPayKey), eq(PaymentProvider.NAVERPAY), eq(pgPaymentId)))
			.willReturn(Optional.of(payment));
		given(orderRepository.findByIdForUpdate(orderId)).willReturn(Optional.of(order));

		// when
		Payment result = paymentApprovalService.succeedApproval(payment, now);

		// then
		assertThat(result).isSameAs(payment);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("succeedApproval 호출 시 payment가 이미 SUCCEEDED면 멱등으로 반환하고 order를 잠그지 않는다")
	@Test
	void succeedApproval_whenPaymentAlreadySucceeded_returnIdempotent() {
		// given
		long orderId = 1L;
		long memberId = 1L;
		String merchantPayKey = "PAY-1";
		String pgPaymentId = "pg-payment-id";
		LocalDateTime now = LocalDateTime.now();

		PaymentReservation reservation = PaymentReservation.createReserved(
			orderId, memberId, 1000, PaymentProvider.NAVERPAY, merchantPayKey, now.plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		payment.succeed(now.minusMinutes(1));

		given(paymentRepository.findApprovePayment(eq(merchantPayKey), eq(PaymentProvider.NAVERPAY), eq(pgPaymentId)))
			.willReturn(Optional.of(payment));

		// when
		Payment result = paymentApprovalService.succeedApproval(payment, now);

		// then
		assertThat(result).isSameAs(payment);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(orderRepository).should(never()).findByIdForUpdate(orderId);
	}

	private Order createOrder(long orderId, int totalPrice) {
		Order order = Order.create(1L);
		order.addOrderItem(1L, 1, totalPrice);
		ReflectionTestUtils.setField(order, "id", orderId);
		return order;
	}
}
