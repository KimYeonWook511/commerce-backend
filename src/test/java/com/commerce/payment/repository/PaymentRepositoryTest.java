package com.commerce.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

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
import com.commerce.payment.domain.PaymentReasonCode;
import com.commerce.payment.domain.PaymentStatus;

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

	@DisplayName("결제가 대기 상태면 처리 중으로 변경된다")
	@Test
	void updateStatusIfMatches_whenPending_updateStatus() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(payment);
		String merchantPayKey = payment.getMerchantPayKey();

		em.flush();
		em.clear();

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

		em.flush();
		em.clear();

		// when
		int updated = paymentRepository.updateStatusIfMatches(
			merchantPayKey, PaymentStatus.PENDING, PaymentStatus.PROCESSING);

		// then
		assertThat(updated).isEqualTo(0);
	}

	@DisplayName("결제가 처리 중이면 취소 대기 상태로 변경된다")
	@Test
	void updateToCancelPending_whenProcessing_updateStatusAndReason() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		paymentRepository.save(payment);
		String merchantPayKey = payment.getMerchantPayKey();

		em.flush();
		em.clear();

		// when
		int updated = paymentRepository.updateToCancelPending(
			merchantPayKey,
			PaymentStatus.PROCESSING,
			PaymentStatus.CANCEL_PENDING,
			"pg-payment-id",
			PaymentReasonCode.AMOUNT_MISMATCH,
			"mismatch"
		);

		// then
		assertThat(updated).isEqualTo(1);
		Payment updatedPayment = paymentRepository.findByMerchantPayKey(merchantPayKey).orElseThrow();
		assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
		assertThat(updatedPayment.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(updatedPayment.getReasonCode()).isEqualTo(PaymentReasonCode.AMOUNT_MISMATCH);
		assertThat(updatedPayment.getReasonDetail()).isEqualTo("mismatch");
	}

	@DisplayName("결제 키와 회원 ID가 일치하면 결제를 조회한다")
	@Test
	void findByMerchantPayKeyAndMemberId_whenMatch_returnPayment() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(payment);

		em.flush();
		em.clear();

		// when
		Optional<Payment> result =
			paymentRepository.findByMerchantPayKeyAndMemberId(payment.getMerchantPayKey(), member.getId());

		// then
		assertThat(result).isPresent();
	}

	@DisplayName("결제 키와 회원 ID가 다르면 결제를 조회하지 못한다")
	@Test
	void findByMerchantPayKeyAndMemberId_whenMemberMismatch_returnEmpty() {
		// given
		Member member = createMember();
		Member other = createOtherMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(payment);

		em.flush();
		em.clear();

		// when
		Optional<Payment> result =
			paymentRepository.findByMerchantPayKeyAndMemberId(payment.getMerchantPayKey(), other.getId());

		// then
		assertThat(result).isEmpty();
	}

	@DisplayName("결제 키로 조회하면 주문이 함께 로딩된다")
	@Test
	void findByMerchantPayKeyWithOrder_whenExists_loadOrder() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		paymentRepository.save(payment);
		String merchantPayKey = payment.getMerchantPayKey();

		em.flush();
		em.clear();

		// when
		Payment result = paymentRepository.findByMerchantPayKeyWithOrder(merchantPayKey).orElseThrow();

		// then
		assertThat(result.getOrder().getId()).isEqualTo(order.getId());
	}

	@DisplayName("PG 결제 키로 조회하면 주문이 함께 로딩된다")
	@Test
	void findByPgPaymentIdWithOrder_whenExists_loadOrder() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "pgPaymentId", "pg-payment-id");
		paymentRepository.save(payment);

		em.flush();
		em.clear();

		// when
		Payment result = paymentRepository.findByPgPaymentIdWithOrder("pg-payment-id").orElseThrow();

		// then
		assertThat(result.getOrder().getId()).isEqualTo(order.getId());
	}

	@DisplayName("하나의 주문에 여러 결제를 저장할 수 있다")
	@Test
	void save_whenSameOrder_multiplePaymentsAllowed() {
		// given
		Member member = createMember();
		Order order = createOrder(member);
		Payment first = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		Payment second = Payment.create(order, 1000, PaymentProvider.NAVERPAY);

		// when
		paymentRepository.save(first);
		paymentRepository.save(second);

		em.flush();
		em.clear();

		// then
		assertThat(paymentRepository.count()).isEqualTo(2);
	}

	private Member createMember() {
		Member member = Member.builder()
			.email("payment-repo@example.com")
			.password("password123")
			.username("payer")
			.build();
		return memberRepository.save(member);
	}

	private Member createOtherMember() {
		Member member = Member.builder()
			.email("payment-repo2@example.com")
			.password("password123")
			.username("payer2")
			.build();
		return memberRepository.save(member);
	}

	private Order createOrder(Member member) {
		Order order = Order.create(member);
		return orderRepository.save(order);
	}
}
