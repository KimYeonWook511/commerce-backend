package com.commerce.payment.legacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.legacy.infrastructure.persistence.PaymentRepositoryAdapter;
import com.commerce.payment.legacy.infrastructure.persistence.PaymentReservationRepositoryAdapter;
import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@Import({
	JpaConfig.class,
	PaymentRepositoryAdapter.class,
	PaymentReservationRepositoryAdapter.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class
})
class PaymentRepositoryJpaAdapterTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private EntityManager em;

	@DisplayName("merchantPayKey, provider, pgPaymentId로 APPROVE 시도를 조회한다")
	@Test
	void findApprovePayment_whenPaymentExists_returnPayment() {
		// given
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-1",
				LocalDateTime.now().plusMinutes(15)));
		Payment payment = paymentRepository.save(
			Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-1"));
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findApprovePayment("PAY-1", PaymentProvider.NAVERPAY, "pg-payment-id-1");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(result.get().getPgPaymentId()).isEqualTo("pg-payment-id-1");
		assertThat(result.get().getType()).isEqualTo(PaymentType.APPROVE);
	}

	@DisplayName("merchantPayKey, provider, pgPaymentId로 CANCEL 시도를 조회한다")
	@Test
	void findCancelPayment_whenPaymentExists_returnPayment() {
		// given
		Payment cancelPayment = paymentRepository.save(
			Payment.createCancelRequested(1L, "PAY-2", "pg-payment-id-2", 1000, PaymentProvider.NAVERPAY));
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findCancelPayment("PAY-2", PaymentProvider.NAVERPAY, "pg-payment-id-2");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-2");
		assertThat(result.get().getPgPaymentId()).isEqualTo("pg-payment-id-2");
		assertThat(result.get().getType()).isEqualTo(PaymentType.CANCEL);
	}

	@DisplayName("merchantPayKey로 승인 완료된 결제를 조회한다")
	@Test
	void findApproveSucceeded_whenSucceededExists_returnPayment() {
		// given
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-3",
				LocalDateTime.now().plusMinutes(15)));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-payment-id-3");
		payment.succeed(LocalDateTime.now());
		paymentRepository.save(payment);
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findApproveSucceeded("PAY-3");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-3");
	}

	@DisplayName("orderId로 SUCCEEDED APPROVE 결제를 조회한다")
	@Test
	void findApproveSucceededByOrderId_whenSucceededExists_returnPayment() {
		// given
		long orderId = 42L;
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(orderId, 1L, 3000, PaymentProvider.NAVERPAY, "PAY-ORDER-42",
				LocalDateTime.now().plusMinutes(15)));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-order-42");
		payment.succeed(LocalDateTime.now());
		paymentRepository.save(payment);
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findApproveSucceededByOrderId(orderId);

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getOrderId()).isEqualTo(orderId);
		assertThat(result.get().getType()).isEqualTo(PaymentType.APPROVE);
		assertThat(result.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("orderId에 해당하는 SUCCEEDED APPROVE 결제가 없으면 빈 Optional을 반환한다")
	@Test
	void findApproveSucceededByOrderId_whenNotExists_returnEmpty() {
		// given: 해당 orderId의 APPROVE가 없는 상황
		long orderId = 999L;

		// when
		Optional<Payment> result = paymentRepository.findApproveSucceededByOrderId(orderId);

		// then
		assertThat(result).isEmpty();
	}

}
