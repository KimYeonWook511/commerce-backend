package com.commerce.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.PaymentRepositoryAdapter;
import com.commerce.payment.infrastructure.persistence.PaymentReservationRepositoryAdapter;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class,
	PaymentRepositoryAdapter.class,
	PaymentReservationRepositoryAdapter.class
})
class PaymentRepositoryDuplicatePaymentTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(paymentPersistence, reservationPersistence);
	}

	@DisplayName("같은 (merchantPayKey, provider, pgPaymentId, type) 조합으로 두 번째 INSERT는 unique 위반으로 거부된다")
	@Test
	void savePayment_whenSamePaymentKeySetExists_throwsUniqueViolation() {
		// given
		String merchantPayKey = "PAY-DUPLICATE-TEST";
		String pgPaymentId = "pg-dup-id";
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt)
		);
		reservation.use();
		reservationPersistence.save(reservation);

		Payment first = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		paymentRepository.save(first);

		// 같은 (merchantPayKey, provider, pgPaymentId, type=APPROVE) 로 새 Payment 생성 시도
		PaymentReservation reservation2 = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-OTHER", expiresAt)
		);
		reservation2.use();
		reservationPersistence.save(reservation2);

		// 두 번째 Payment는 merchantPayKey가 다른 reservation에서 왔지만 동일한 pgPaymentId와 type
		Payment second = Payment.createRequested(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt),
			PaymentType.APPROVE,
			pgPaymentId
		);

		// when & then: uk_payment_merchant_pay_key_provider_pg_payment_id_type 위반 — save()는 매핑하지 않아 원 예외 전파
		assertThatThrownBy(() -> paymentRepository.save(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("saveApproved: uk_payment_approved_order_key 위반 시 PaymentException(PAYMENT_DUPLICATE)로 매핑된다")
	@Test
	void saveApproved_whenApprovedOrderKeyViolation_throwsPaymentDuplicate() {
		// given: 같은 orderId로 첫 번째 결제가 SUCCEEDED 상태로 저장된 상황
		long orderId = 100L;
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
		LocalDateTime respondedAt = LocalDateTime.now();

		PaymentReservation reservation1 = reservationPersistence.save(
			PaymentReservation.createReserved(orderId, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-APPROVED-1", expiresAt)
		);
		reservation1.use();
		reservationPersistence.save(reservation1);
		Payment first = Payment.createRequested(reservation1, PaymentType.APPROVE, "pg-approved-1");
		first.succeed(respondedAt);
		paymentRepository.saveApproved(first);

		// 두 번째 결제: 같은 orderId, 다른 merchantPayKey/pgPaymentId
		PaymentReservation reservation2 = reservationPersistence.save(
			PaymentReservation.createReserved(orderId, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-APPROVED-2", expiresAt)
		);
		reservation2.use();
		reservationPersistence.save(reservation2);
		Payment second = Payment.createRequested(reservation2, PaymentType.APPROVE, "pg-approved-2");
		second.succeed(respondedAt);

		// when & then: uk_payment_approved_order_key 위반 → PaymentException(PAYMENT_DUPLICATE)로 매핑
		assertThatThrownBy(() -> paymentRepository.saveApproved(second))
			.isInstanceOf(PaymentException.class)
			.satisfies(ex -> {
				PaymentException paymentException = (PaymentException) ex;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
	}

	@DisplayName("saveApproved: uk_payment_merchant_pay_key_provider_pg_payment_id_type 위반 시 DataIntegrityViolationException이 그대로 전파된다")
	@Test
	void saveApproved_whenOtherUniqueViolation_propagatesOriginalException() {
		// given: 같은 (merchantPayKey, provider, pgPaymentId, type) 로 두 번째 INSERT 시도
		String merchantPayKey = "PAY-SAVEAPPROVED-TEST";
		String pgPaymentId = "pg-saveapproved-id";
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt)
		);
		reservation.use();
		reservationPersistence.save(reservation);
		Payment first = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		paymentRepository.save(first);

		// 두 번째 Payment: 다른 orderId지만 동일한 (merchantPayKey, provider, pgPaymentId, type)
		Payment second = Payment.createRequested(
			PaymentReservation.createReserved(999L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt),
			PaymentType.APPROVE,
			pgPaymentId
		);

		// when & then: uk_payment_merchant_pay_key_provider_pg_payment_id_type 위반 → 오매핑 없이 원 예외 전파
		assertThatThrownBy(() -> paymentRepository.saveApproved(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
