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

	@DisplayName("결제 완료 처리 시 상태와 승인 정보가 변경된다")
	@Test
	void completeWithPgPaymentId_whenPending_updateStatusAndApprovedAt() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		LocalDateTime approvedAt = LocalDateTime.now();

		// when
		payment.completeWithPgPaymentId("pg-payment-id", approvedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
		assertThat(payment.getFailureReason()).isNull();
	}

	@DisplayName("결제가 대기 상태가 아니면 완료 처리에 실패한다")
	@Test
	void completeWithPgPaymentId_whenNotPending_throwException() {
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
