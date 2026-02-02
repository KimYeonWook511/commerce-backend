package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.product.domain.Product;

class PaymentTest {

	@DisplayName("결제가 처리 중이 아니면 완료 처리에 실패한다")
	@Test
	void completeWithPgPaymentId_whenNotProcessing_throwException() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);

		// when & then
		assertThatThrownBy(() -> payment.completeWithPgPaymentId("pg-payment-id", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
	}

	@DisplayName("결제가 완료 상태면 완료 처리에 실패한다")
	@Test
	void completeWithPgPaymentId_whenCompleted_throwException() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.COMPLETED);

		// when & then
		assertThatThrownBy(() -> payment.completeWithPgPaymentId("pg-payment-id", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
	}

	@DisplayName("결제가 처리 중이면 완료 처리에 성공한다")
	@Test
	void completeWithPgPaymentId_whenProcessing_updateStatusAndApprovedAt() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		LocalDateTime approvedAt = LocalDateTime.now();

		// when
		payment.completeWithPgPaymentId("pg-payment-id", approvedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
	}

	@DisplayName("결제가 처리 중이면 실패 처리에 성공한다")
	@Test
	void failWithPgPaymentId_whenProcessing_updateStatusAndReason() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);

		// when
		payment.failWithPgPaymentId(null, "fail");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getPgPaymentId()).isNull();
		assertThat(payment.getFailureReason()).isEqualTo("fail");
		assertThat(payment.getApprovedAt()).isNull();
	}

	@DisplayName("결제가 대기 상태면 취소할 수 있다")
	@Test
	void cancel_whenPending_updateStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);

		// when
		payment.cancel("cancel");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getFailureReason()).isEqualTo("cancel");
	}

	@DisplayName("결제가 완료 상태면 취소할 수 있다")
	@Test
	void cancel_whenCompleted_updateStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.COMPLETED);

		// when
		payment.cancel("cancel");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getFailureReason()).isEqualTo("cancel");
	}

	@DisplayName("결제가 처리 중이면 취소할 수 있다")
	@Test
	void cancel_whenProcessing_updateStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);

		// when
		payment.cancel("cancel");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getFailureReason()).isEqualTo("cancel");
	}

	@DisplayName("결제가 실패 상태면 취소할 수 없다")
	@Test
	void cancel_whenFailed_throwException() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);

		// when & then
		assertThatThrownBy(() -> payment.cancel("cancel"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.build();
		order.addOrderItem(product, 1);
		return order;
	}

	private Member createMember() {
		return Member.builder()
			.email("payment@example.com")
			.password("password123")
			.username("payer")
			.build();
	}
}
