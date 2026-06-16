package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.commerce.payment.application.service.EscalateApprovePaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.TestcontainersSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class
})
class PaymentEscalationConcurrencyTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private EscalateApprovePaymentService escalateApprovePaymentService;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("같은 escalation 건에 N개 스레드가 동시에 escalate transition을 호출하면 정확히 1개 스레드만 통지 주체(true)가 되고 나머지는 PAYMENT_CONCURRENTLY_MODIFIED로 skip된다")
	@Test
	void escalate_concurrent_exactlyOneThreadBecomesNotificationSubject() throws Exception {
		// given: 6시간 초과 UNKNOWN APPROVE 결제 1건 준비
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(
				9001L, 1L, 1000, PaymentProvider.NAVERPAY, "ESC-CON-1",
				LocalDateTime.now().plusMinutes(15))
		);
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-esc-con-1");
		payment.markUnknown("timeout", LocalDateTime.now().minusHours(7));
		paymentPersistence.save(payment);

		int threadCount = 10;
		AtomicInteger successCount = new AtomicInteger(0);
		ConcurrentLinkedQueue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();
		LocalDateTime escalationTime = LocalDateTime.now();

		// when: N개 스레드가 동시에 escalate transition(find → escalate() → saveChecked) 호출
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						boolean notificationSubject = escalateApprovePaymentService.escalate(
							"ESC-CON-1", PaymentProvider.NAVERPAY, "pg-esc-con-1", escalationTime);
						if (notificationSubject) {
							successCount.incrementAndGet();
						}
					} catch (PaymentException ex) {
						// 정상 skip — 다른 스레드가 먼저 escalation 성공 (transition이 PAYMENT_CONCURRENTLY_MODIFIED로 변환)
						if (ex.getErrorCode() != PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED) {
							unexpectedErrors.add(ex);
						}
					} catch (Throwable ex) {
						unexpectedErrors.add(ex);
					} finally {
						doneLatch.countDown();
					}
				});
			}
			startLatch.countDown();
			doneLatch.await(15, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// then: 예상치 못한 예외 없음 (충돌 외 다른 예외 없음)
		assertThat(unexpectedErrors).isEmpty();

		// then: 정확히 1개 스레드만 escalation 통지 주체 (통지 1회 보장)
		assertThat(successCount.get()).isEqualTo(1);

		// then: DB에 escalatedAt이 기록됨
		Payment escalated = paymentPersistence.getPayment(
			"ESC-CON-1", PaymentProvider.NAVERPAY, "pg-esc-con-1", PaymentType.APPROVE);
		assertThat(escalated.getEscalatedAt()).isNotNull();
	}
}
