package com.commerce.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;

@DataJpaTest
@ActiveProfiles("test")
class PaymentRepositoryTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private MemberRepository memberRepository;

	@DisplayName("주문 ID로 결제를 조회하면 해당 결제를 반환한다")
	@Test
	void findByOrderId_whenPaymentExists_returnPayment() {
		// given
		Member member = memberRepository.save(createMember());
		Order order = orderRepository.save(Order.create(member));
		Payment payment = paymentRepository.save(Payment.create(order, 1000, PaymentProvider.NAVERPAY));

		// when
		Optional<Payment> result = paymentRepository.findByOrderId(order.getId());

		// then
		assertThat(result).isPresent();
		assertThat(result.orElseThrow().getId()).isEqualTo(payment.getId());
	}

	private Member createMember() {
		return Member.builder()
			.email("payment@example.com")
			.password("password123")
			.username("payer")
			.build();
	}
}
