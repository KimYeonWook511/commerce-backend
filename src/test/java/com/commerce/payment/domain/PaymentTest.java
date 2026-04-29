package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

class PaymentTest {

	@DisplayName("완료 결제를 생성하면 승인 정보가 함께 저장된다")
	@Test
	void createCompleted_whenCalled_setCompletedState() {
		// given
		Order order = createOrder(1000);
		LocalDateTime approvedAt = LocalDateTime.now();

		// when
		Payment payment = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			approvedAt
		);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(payment.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.status(ProductStatus.ON_SALE)
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
