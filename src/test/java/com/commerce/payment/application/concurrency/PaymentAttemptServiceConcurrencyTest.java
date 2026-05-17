package com.commerce.payment.application.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
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

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.application.PaymentAttemptService;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.TestcontainersSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentAttemptServiceConcurrencyTest {

	@Autowired
	private PaymentAttemptService paymentAttemptService;

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

	@DisplayName("동시에 승인 시도 이력을 생성해도 approve attempt는 하나만 생성된다")
	@Test
	void getOrCreateApproveAttempt_whenConcurrentCreate_createSingleApproveAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-ATTEMPT-CON-1";
		String paymentId = "pg-attempt-con-1";
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> paymentAttemptService.getOrCreateApproveAttempt(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			1000
		), errors);

		// then
		assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.APPROVE))
			.isEqualTo(1L);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시에 취소 시도 이력을 생성해도 cancel attempt는 하나만 생성된다")
	@Test
	void getOrCreateCancelAttempt_whenConcurrentCreate_createSingleCancelAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-ATTEMPT-CON-2";
		String paymentId = "pg-attempt-con-2";
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("product-" + merchantPayKey, 1000));
		orderPersistence.saveAndFlush(createOrder(member, product, merchantPayKey));
		paymentPersistence.save(
			PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, 1000, PaymentProvider.NAVERPAY)
		);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> {
			paymentAttemptService.failApproveAttempt(
				merchantPayKey,
				PaymentProvider.NAVERPAY,
				paymentId,
				PaymentAttemptFailCode.APPROVE_PROCESS_FAILED,
				"duplicate cancel request",
				LocalDateTime.now()
			);
			paymentAttemptService.getOrCreateCancelAttempt(
				merchantPayKey,
				PaymentProvider.NAVERPAY,
				paymentId,
				1000
			);
		}, errors);

		// then
		assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.CANCEL))
			.isEqualTo(1L);
		assertThat(errors).isEmpty();
	}

	@DisplayName("기존 승인 attempt와 다른 금액으로 동시 요청하면 모두 금액 불일치 예외가 발생한다")
	@Test
	void getOrCreateApproveAttempt_whenConcurrentRequestWithDifferentAmount_allThrowAmountMismatch() throws Exception {
		// given: amount=1000으로 approve attempt 선행 생성
		String merchantPayKey = "PAY-ATTEMPT-MISMATCH-1";
		String paymentId = "pg-attempt-mismatch-1";
		paymentAttemptService.getOrCreateApproveAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 1000);

		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 20개 스레드가 amount=2000으로 동시 재요청 (mismatch)
		runConcurrent(20, () -> paymentAttemptService.getOrCreateApproveAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 2000), errors);

		// then: attempt는 1건, 재요청 20개 모두 mismatch 예외
		assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.APPROVE))
			.isEqualTo(1L);
		assertThat(errors).hasSize(20);
		errors.forEach(e -> {
			assertThat(e).isInstanceOf(PaymentException.class);
			assertThat(((PaymentException) e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
		});
	}

	@DisplayName("기존 취소 attempt와 다른 금액으로 동시 요청하면 모두 금액 불일치 예외가 발생한다")
	@Test
	void getOrCreateCancelAttempt_whenConcurrentRequestWithDifferentAmount_allThrowAmountMismatch() throws Exception {
		// given: amount=1000으로 cancel attempt 선행 생성
		String merchantPayKey = "PAY-ATTEMPT-MISMATCH-2";
		String paymentId = "pg-attempt-mismatch-2";
		paymentAttemptService.getOrCreateCancelAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 1000);

		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when: 20개 스레드가 amount=2000으로 동시 재요청 (mismatch)
		runConcurrent(20, () -> paymentAttemptService.getOrCreateCancelAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, 2000), errors);

		// then: attempt는 1건, 재요청 20개 모두 mismatch 예외
		assertThat(paymentPersistence.countAttempts(merchantPayKey, paymentId, PaymentAttemptType.CANCEL))
			.isEqualTo(1L);
		assertThat(errors).hasSize(20);
		errors.forEach(e -> {
			assertThat(e).isInstanceOf(PaymentException.class);
			assertThat(((PaymentException) e).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
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

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("payment-attempt-con-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product, String merchantPayKey) {
		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return order;
	}
}
