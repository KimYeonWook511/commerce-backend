package com.commerce.payment.application.concurrency;

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

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.application.PaymentApprovalRecordService;
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
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, PaymentReservationPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentApprovalRecordServiceConcurrencyTest {

	@Autowired
	private PaymentApprovalRecordService paymentApprovalRecordService;

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

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("같은 예약에 다른 pgPaymentId 승인이 동시에 들어오면 한쪽만 payment를 생성하고 나머지는 PAYMENT_RESERVATION_ALREADY_USED로 차단된다")
	@Test
	void create_whenConcurrentDifferentPgPaymentId_onlyOnePaymentCreatedAndOtherBlocked() throws Exception {
		// given: reservation 선행 생성
		String merchantPayKey = "PAY-RECORD-CON-2";
		String pgPaymentIdA = "pg-record-con-2-a";
		String pgPaymentIdB = "pg-record-con-2-b";
		reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey,
				LocalDateTime.now().plusMinutes(15))
		);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 2개 스레드가 각자 fresh reservation을 로드해 다른 pgPaymentId로 동시 create
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> {
				try {
					startLatch.await();
					PaymentReservation r = reservationPersistence.findByMerchantPayKey(merchantPayKey).orElseThrow();
					paymentApprovalRecordService.create(r, pgPaymentIdA);
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			executor.submit(() -> {
				try {
					startLatch.await();
					PaymentReservation r = reservationPersistence.findByMerchantPayKey(merchantPayKey).orElseThrow();
					paymentApprovalRecordService.create(r, pgPaymentIdB);
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			startLatch.countDown();
			doneLatch.await(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// then: payment는 정확히 1건만 생성됨 (진 쪽 트랜잭션 롤백)
		long totalPayments = paymentPersistence.countPayments(merchantPayKey, pgPaymentIdA, PaymentType.APPROVE)
			+ paymentPersistence.countPayments(merchantPayKey, pgPaymentIdB, PaymentType.APPROVE);
		assertThat(totalPayments).isEqualTo(1L);
		// 진 쪽은 PAYMENT_RESERVATION_ALREADY_USED로 차단됨 (concurrent case)
		// 또는 PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED (sequential case: USED 상태 reservation에 use() 호출)
		assertThat(errors).hasSize(1);
		errors.forEach(e -> {
			assertThat(e).isInstanceOf(PaymentException.class);
			assertThat(((PaymentException) e).getErrorCode())
				.isIn(PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED,
					PaymentErrorCode.PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED);
		});
	}

	@DisplayName("동시에 같은 키로 멱등 재요청해도 사전 find 분기로 모두 같은 approve payment를 반환한다")
	@Test
	void create_whenConcurrentIdempotentRequest_returnSameApprovePayment() throws Exception {
		// given: reservation 선행 생성 후 approve payment 선행 생성
		String merchantPayKey = "PAY-RECORD-CON-1";
		String pgPaymentId = "pg-record-con-1";
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey,
				LocalDateTime.now().plusMinutes(15))
		);
		paymentApprovalRecordService.create(reservation, pgPaymentId);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 20개 스레드가 동일한 pgPaymentId로 동시 재요청
		runConcurrent(20, () -> paymentApprovalRecordService.create(reservation, pgPaymentId), errors);

		// then: 사전 find 분기로 모두 흡수되어 payment는 1건, 에러 없음
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(errors).isEmpty();
	}

	private void runConcurrent(int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors) throws Exception {
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
			doneLatch.await(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
	}
}
