package com.commerce.payment.application.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.application.PaymentCancellationService;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.TestcontainersSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentCancellationServiceConcurrencyTest {

	@Autowired
	private PaymentCancellationService paymentCancellationService;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

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
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("동시에 같은 키로 멱등 재요청해도 사전 find 분기로 모두 같은 cancel payment를 반환한다")
	@Test
	void getOrCreate_whenConcurrentIdempotentRequest_returnSameCancelPayment() throws Exception {
		// given: amount=1000으로 cancel payment 선행 생성
		String merchantPayKey = "PAY-RECORD-CON-2";
		String pgPaymentId = "pg-record-con-2";
		paymentCancellationService.getOrCreate(
			1L, merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 20개 스레드가 동일한 amount로 동시 재요청
		runConcurrent(20, () -> paymentCancellationService.getOrCreate(
			1L,
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			pgPaymentId,
			1000
		), errors);

		// then: 사전 find 분기로 모두 흡수되어 payment는 1건, 에러 없음
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.CANCEL))
			.isEqualTo(1L);
		assertThat(errors).isEmpty();
	}

	@DisplayName("기존 취소 payment와 다른 금액으로 동시 요청하면 모두 금액 불일치 예외가 발생한다")
	@Test
	void getOrCreate_whenConcurrentRequestWithDifferentAmount_allThrowAmountMismatch() throws Exception {
		// given: amount=1000으로 cancel payment 선행 생성
		String merchantPayKey = "PAY-RECORD-MISMATCH-2";
		String pgPaymentId = "pg-record-mismatch-2";
		paymentCancellationService.getOrCreate(
			1L, merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, 1000);

		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 20개 스레드가 amount=2000으로 동시 재요청 (mismatch)
		runConcurrent(20, () -> paymentCancellationService.getOrCreate(
			1L, merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, 2000), errors);

		// then: payment는 1건, 재요청 20개 모두 mismatch 예외
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.CANCEL))
			.isEqualTo(1L);
		assertThat(errors).hasSize(20);
		errors.forEach(e -> {
			assertThat(e).isInstanceOf(PaymentException.class);
			assertThat(((PaymentException) e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_RECORD_AMOUNT_MISMATCH);
		});
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
