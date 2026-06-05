package com.commerce.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

import com.commerce.common.util.UlidGenerator;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@Tag("concurrency")
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
class PaymentRepositoryApprovedConcurrencyTest {

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

	@DisplayName("같은 주문에 APPROVE가 동시에 SUCCEEDED로 전이되어도 정확히 1개만 살아남는다")
	@Test
	void succeedApprovePayment_whenConcurrent_onlyOneSucceedsAndOthersFailUniqueViolation() throws Exception {
		// given: N개의 서로 다른 Reservation과 Payment(REQUESTED) 준비
		long orderId = 1L;
		int threadCount = 8;
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
		LocalDateTime respondedAt = LocalDateTime.now();

		Payment[] payments = new Payment[threadCount];
		for (int i = 0; i < threadCount; i++) {
			String merchantPayKey = "PAY-" + UlidGenerator.generate();
			PaymentReservation reservation = reservationPersistence.save(
				PaymentReservation.createReserved(orderId, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, expiresAt)
			);
			reservation.use();
			reservationPersistence.save(reservation);
			payments[i] = paymentPersistence.save(
				Payment.createRequested(reservation, PaymentType.APPROVE, "pg-" + i)
			);
		}

		// when: N 스레드가 동시에 같은 orderId로 succeed 시도
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		for (Payment payment : payments) {
			executor.submit(() -> {
				try {
					startLatch.await();
					payment.succeed(respondedAt);
					paymentRepository.save(payment);
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
		}
		startLatch.countDown();
		doneLatch.await(30, TimeUnit.SECONDS);
		executor.shutdownNow();

		// then: SUCCEEDED APPROVE 행은 정확히 1개 (uk_payment_approved_order_key 보장)
		long succeededCount = paymentPersistence.findApproveSucceeded(
			payments[0].getMerchantPayKey()
		).isPresent() ? 1 : 0;

		// 하나라도 succeededCount를 세야 하므로, 전체 APPROVE 행 중 SUCCEEDED 수를 직접 확인
		long totalSucceeded = 0;
		for (Payment payment : payments) {
			if (paymentPersistence.findApproveSucceeded(payment.getMerchantPayKey()).isPresent()) {
				totalSucceeded++;
			}
		}

		assertThat(totalSucceeded).isEqualTo(1L);
		assertThat(errors).isNotEmpty();
	}
}
