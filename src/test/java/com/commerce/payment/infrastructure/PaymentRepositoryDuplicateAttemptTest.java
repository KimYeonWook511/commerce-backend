package com.commerce.payment.infrastructure;

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
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
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
class PaymentRepositoryDuplicateAttemptTest {

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
	void savePayment_whenSameAttemptKeySetExists_throwsUniqueViolation() {
		// given
		String merchantPayKey = "PAY-DUPLICATE-TEST";
		String pgPaymentId = "pg-dup-id";
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt)
		);
		reservation.markUsed();
		reservationPersistence.save(reservation);

		Payment first = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		paymentRepository.save(first);

		// 같은 (merchantPayKey, provider, pgPaymentId, type=APPROVE) 로 새 Payment 생성 시도
		PaymentReservation reservation2 = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, "PAY-OTHER", expiresAt)
		);
		reservation2.markUsed();
		reservationPersistence.save(reservation2);

		// 두 번째 Payment는 merchantPayKey가 다른 reservation에서 왔지만 동일한 pgPaymentId와 type
		Payment second = Payment.createRequested(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt),
			PaymentType.APPROVE,
			pgPaymentId
		);

		// when & then: uk_payment_merchant_pay_key_provider_pg_payment_id_type 위반
		assertThatThrownBy(() -> paymentRepository.save(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
