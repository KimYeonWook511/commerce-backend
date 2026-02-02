package com.commerce.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;

@DataJpaTest
@ActiveProfiles("test")
class PaymentRepositoryTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@DisplayName("결제가 대기 상태면 처리 중으로 변경된다")
	@Test
	void updateStatusIfMatches_whenPending_updateStatus() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(payment);
		String merchantPayKey = payment.getMerchantPayKey();

		// when
		int updated = paymentRepository.updateStatusIfMatches(
			merchantPayKey, PaymentStatus.PENDING, PaymentStatus.PROCESSING);

		// then
		assertThat(updated).isEqualTo(1);
	}

	@DisplayName("결제가 완료 상태면 처리 중으로 변경되지 않는다")
	@Test
	void updateStatusIfMatches_whenCompleted_returnZero() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.COMPLETED);
		paymentRepository.save(payment);
		String merchantPayKey = payment.getMerchantPayKey();

		// when
		int updated = paymentRepository.updateStatusIfMatches(
			merchantPayKey, PaymentStatus.PENDING, PaymentStatus.PROCESSING);

		// then
		assertThat(updated).isEqualTo(0);
	}

	private Member createMember() {
		Member member = Member.builder()
			.email("payment-repo@example.com")
			.password("password123")
			.username("payer")
			.build();
		return memberRepository.save(member);
	}

	private Order createOrder(Member member) {
		Order order = Order.create(member);
		return orderRepository.save(order);
	}
}
