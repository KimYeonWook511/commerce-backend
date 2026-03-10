package com.commerce.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
class PaymentRepositoryTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private EntityManager em;

	@DisplayName("merchantPayKey로 결제 정보를 조회한다")
	@Test
	void findByMerchantPayKey_whenPaymentExists_returnPayment() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id-1",
			LocalDateTime.of(2026, 3, 5, 19, 30)
		);
		paymentRepository.save(payment);
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findByMerchantPayKey("PAY-1");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(result.get().getPgPaymentId()).isEqualTo("pg-payment-id-1");
	}

	@DisplayName("merchantPayKey에 해당하는 결제가 없으면 빈 값을 반환한다")
	@Test
	void findByMerchantPayKey_whenPaymentNotExists_returnEmpty() {
		// when
		Optional<Payment> result = paymentRepository.findByMerchantPayKey("PAY-NOT-FOUND");

		// then
		assertThat(result).isEmpty();
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
