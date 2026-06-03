package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.application.PaymentApprovalAttemptService;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private PaymentApprovalAttemptService paymentApprovalAttemptService;

	@InjectMocks
	private PaymentApprovalService paymentApprovalService;

	@DisplayName("결제 완료에 성공하면 payment를 생성한다")
	@Test
	void completeApprovedPayment_whenPaymentNotExists_createPayment() {
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		LocalDateTime approvedAt = LocalDateTime.now();
		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

		Payment result = paymentApprovalService.completeApprovedPayment(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);

		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("이미 완료된 동일 결제가 있으면 기존 payment를 반환한다")
	@Test
	void completeApprovedPayment_whenPaymentExistsAndSameRequest_returnExistingPayment() {
		Order order = createOrder(1000);
		Payment payment = Payment.createCompleted(
			1L,
			1000,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);
		LocalDateTime approvedAt = LocalDateTime.now();

		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		Payment result = paymentApprovalService.completeApprovedPayment(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);

		assertThat(result).isEqualTo(payment);
		then(orderRepository).should().findByMerchantPayKeyForUpdate("PAY-1");
		then(paymentApprovalAttemptService).should().succeed(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);
		then(paymentRepository).shouldHaveNoMoreInteractions();
	}

	@DisplayName("이미 완료된 결제의 pgPaymentId가 다르면 예외가 발생한다")
	@Test
	void completeApprovedPayment_whenExistingPaymentHasDifferentPgPaymentId_throwException() {
		Order order = createOrder(1000);
		Payment payment = Payment.createCompleted(
			1L,
			1000,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"other-payment-id",
			LocalDateTime.now()
		);

		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentApprovalService.completeApprovedPayment(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			LocalDateTime.now()
		))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(1L);
		order.addOrderItem(1L, 1, totalPrice);
		return order;
	}

	private void setOrderId(Order order, Long orderId) {
		ReflectionTestUtils.setField(order, "id", orderId);
	}
}
