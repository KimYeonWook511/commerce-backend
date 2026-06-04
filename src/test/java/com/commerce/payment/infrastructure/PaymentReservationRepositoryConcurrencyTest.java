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
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentReservationPersistenceTestSupport.class})
class PaymentReservationRepositoryConcurrencyTest {

	@Autowired
	private PaymentReservationRepository reservationRepository;

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
		persistenceCleanup.deleteAllInBatch(reservationPersistence);
	}

	@DisplayName("같은 (주문, 수단, 회원, 금액)으로 reserve가 동시에 들어와도 RESERVED는 정확히 1개만 살아남는다")
	@Test
	void createReserved_whenConcurrent_onlyOneSucceedsAndOthersFailUniqueViolation() throws Exception {
		// given
		Long orderId = 1L;
		Long memberId = 1L;
		PaymentProvider provider = PaymentProvider.NAVERPAY;
		int amount = 1000;
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

		int threadCount = 10;
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 동시에 같은 (orderId, provider)로 RESERVED INSERT 시도
		// — 각 thread는 고유한 merchantPayKey를 가지지만 reservedKey는 "1:NAVERPAY"로 동일
		runConcurrent(threadCount, () ->
			reservationRepository.save(
				PaymentReservation.createReserved(orderId, memberId, amount, provider,
					"PAY-" + UlidGenerator.generate(), expiresAt)
			), errors);

		// then: invariant — DB에 RESERVED 행은 정확히 1개, 나머지는 unique 위반으로 실패
		long reservedCount = reservationPersistence.count();
		assertThat(reservedCount).isEqualTo(1L);
		assertThat(errors).isNotEmpty();
	}

	private void runConcurrent(int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors)
		throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						task.run();
					} catch (Throwable ex) {
						errors.add(ex);
					} finally {
						doneLatch.countDown();
					}
				});
			}
			startLatch.countDown();
			doneLatch.await(30, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
	}
}
