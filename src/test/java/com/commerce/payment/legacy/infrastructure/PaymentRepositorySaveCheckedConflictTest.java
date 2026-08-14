package com.commerce.payment.legacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * saveChecked의 @Version 충돌 변환을 version 강제 stale로 결정적으로 검증한다.
 * 두 detached 복사본 중 하나를 먼저 저장해 DB version을 bump한 뒤, stale 복사본을 saveChecked하면
 * ObjectOptimisticLockingFailureException이 PaymentException(PAYMENT_CONCURRENTLY_MODIFIED)으로 변환돼 throw됨을 확인한다.
 */
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class
})
class PaymentRepositorySaveCheckedConflictTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(paymentPersistence, reservationPersistence);
	}

	@DisplayName("stale 엔티티를 saveChecked하면 ObjectOptimisticLockingFailureException이 PAYMENT_CONCURRENTLY_MODIFIED로 변환돼 throw된다")
	@Test
	void saveChecked_whenStaleVersion_throwsConcurrentlyModified() {
		// given: REQUESTED APPROVE 결제 1건 저장 (version 0)
		reservationPersistence.save(PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "SC-1", LocalDateTime.now().plusMinutes(15)));
		paymentPersistence.save(Payment.createRequested(
			reservationPersistence.findByMerchantPayKey("SC-1").orElseThrow(), PaymentType.APPROVE, "pg-sc-1"));

		// 두 detached 복사본을 독립 조회 (각자 version 0)
		Payment winner = paymentRepository.findApprovePayment("SC-1", PaymentProvider.NAVERPAY, "pg-sc-1").orElseThrow();
		Payment loser = paymentRepository.findApprovePayment("SC-1", PaymentProvider.NAVERPAY, "pg-sc-1").orElseThrow();

		// 승자가 먼저 종착 전이를 저장해 DB version을 bump (loser는 이제 stale)
		winner.markUnknown("first writer", LocalDateTime.now());
		paymentRepository.saveChecked(winner);

		// when & then: stale한 loser의 saveChecked는 PAYMENT_CONCURRENTLY_MODIFIED로 변환된 예외를 던진다
		loser.fail(PaymentFailCode.PG_NETWORK_ERROR, "stale writer", LocalDateTime.now());
		assertThatThrownBy(() -> paymentRepository.saveChecked(loser))
			.isInstanceOf(PaymentException.class)
			.extracting(ex -> ((PaymentException) ex).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED);

		// then: 승자의 전이만 반영됨 (lost update 없음)
		Payment finalPayment = paymentPersistence.getPayment("SC-1", PaymentProvider.NAVERPAY, "pg-sc-1", PaymentType.APPROVE);
		assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("충돌이 없으면 saveChecked는 정상적으로 종착 전이를 저장한다")
	@Test
	void saveChecked_whenNoConflict_persistsTransition() {
		// given
		reservationPersistence.save(PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, "SC-2", LocalDateTime.now().plusMinutes(15)));
		paymentPersistence.save(Payment.createRequested(
			reservationPersistence.findByMerchantPayKey("SC-2").orElseThrow(), PaymentType.APPROVE, "pg-sc-2"));

		Payment payment = paymentRepository.findApprovePayment("SC-2", PaymentProvider.NAVERPAY, "pg-sc-2").orElseThrow();

		// when
		payment.fail(PaymentFailCode.PG_NETWORK_ERROR, "no conflict", LocalDateTime.now());
		assertThatCode(() -> paymentRepository.saveChecked(payment)).doesNotThrowAnyException();

		// then
		Payment finalPayment = paymentPersistence.getPayment("SC-2", PaymentProvider.NAVERPAY, "pg-sc-2", PaymentType.APPROVE);
		assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
	}
}
