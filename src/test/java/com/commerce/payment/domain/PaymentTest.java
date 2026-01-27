package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

class PaymentTest {

	@DisplayName("결제를 생성하면 PENDING 상태로 초기화된다")
	@Test
	void create_whenCalled_initializeWithPendingStatus() {
		// when
		Payment payment = Payment.create(createOrder(), 1000, PaymentProvider.NAVERPAY);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getApprovedAt()).isNull();
		assertThat(payment.getFailureReason()).isNull();
	}

	@DisplayName("결제를 완료하면 상태가 COMPLETED로 바뀌고 승인 시간이 기록된다")
	@Test
	void complete_whenPending_changeToCompleted() {
		// given
		Payment payment = Payment.create(createOrder(), 1000, PaymentProvider.NAVERPAY);
		LocalDateTime approvedAt = LocalDateTime.now();

		// when
		payment.complete(approvedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
		assertThat(payment.getFailureReason()).isNull();
	}

	@DisplayName("결제가 실패하면 상태가 FAILED로 바뀌고 실패 사유가 기록된다")
	@Test
	void fail_whenPending_changeToFailed() {
		// given
		Payment payment = Payment.create(createOrder(), 1000, PaymentProvider.NAVERPAY);

		// when
		payment.fail("insufficient balance");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailureReason()).isEqualTo("insufficient balance");
		assertThat(payment.getApprovedAt()).isNull();
	}

	@DisplayName("결제를 취소하면 상태가 CANCELED로 바뀌고 취소 사유가 기록된다")
	@Test
	void cancel_whenPending_changeToCanceled() {
		// given
		Payment payment = Payment.create(createOrder(), 1000, PaymentProvider.NAVERPAY);

		// when
		payment.cancel("user canceled");

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(payment.getFailureReason()).isEqualTo("user canceled");
		assertThat(payment.getApprovedAt()).isNull();
	}

	@DisplayName("대기 상태가 아니면 결제 상태를 변경할 수 없다")
	@Test
	void fail_whenStatusNotPending_throwException() {
		// given
		Payment payment = Payment.create(createOrder(), 1000, PaymentProvider.NAVERPAY);
		setStatus(payment, PaymentStatus.COMPLETED);

		// when & then
		assertThatThrownBy(() -> payment.fail("reason"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode())
					.isEqualTo(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
			});
	}

	private void setStatus(Payment payment, PaymentStatus status) {
		org.springframework.test.util.ReflectionTestUtils.setField(payment, "status", status);
	}

	private Order createOrder() {
		return Order.create(createMember());
	}

	private Member createMember() {
		return Member.builder()
			.email("test@example.com")
			.password("password123")
			.username("tester")
			.build();
	}
}
