package com.commerce.payment.integration.concurrency;

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
import com.commerce.payment.integration.support.PaymentPersistenceTestSupport;
import com.commerce.member.integration.support.MemberPersistenceTestSupport;
import com.commerce.order.integration.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.integration.support.ProductPersistenceTestSupport;
import com.commerce.test.support.TestcontainersSupport;
import com.commerce.test.support.PersistenceCleanupTestSupport;

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
