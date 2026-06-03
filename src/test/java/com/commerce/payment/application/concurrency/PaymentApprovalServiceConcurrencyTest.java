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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.application.PaymentApprovalService;
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
class PaymentApprovalServiceConcurrencyTest {

	@Autowired
	private PaymentApprovalService paymentApprovalService;

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

	@DisplayName("동시에 결제 완료를 호출해도 payment는 하나만 생성된다")
	@Test
	void completeApprovedPayment_whenConcurrentCall_createSinglePayment() throws Exception {
		// given
		String merchantPayKey = "PAY-CON-1";
		String pgPaymentId = "pg-con-1";
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("product-" + merchantPayKey, 1000));
		orderPersistence.saveAndFlush(createOrder(member, product, merchantPayKey));
		paymentPersistence.save(createApproveAttempt(merchantPayKey, pgPaymentId, 1000));
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> paymentApprovalService.completeApprovedPayment(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			pgPaymentId,
			LocalDateTime.now()
		), errors);

		// then
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey(merchantPayKey))
			.isEqualTo(OrderStatus.PAID);
		assertThat(errors.stream().allMatch(this::isAllowedConcurrentException)).isTrue();
	}

	private boolean isAllowedConcurrentException(Throwable throwable) {
		// race window에서 attempt unique 위반 또는 payment unique 위반으로 발생
		if (throwable instanceof DataIntegrityViolationException) {
			return true;
		}
		if (throwable instanceof PaymentException paymentException) {
			return paymentException.getErrorCode() == PaymentErrorCode.PAYMENT_DUPLICATE
				// Order FOR UPDATE 직렬화 후 두 번째 스레드가 이미 SUCCEEDED 상태인 attempt를 다시 mark할 때 발생
				|| paymentException.getErrorCode() == PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED;
		}
		if (throwable instanceof OrderException orderException) {
			return orderException.getErrorCode() == OrderErrorCode.ORDER_PAID_NOT_ALLOWED;
		}
		return false;
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
			.email("payment-con-" + suffix + "@example.com")
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
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		order.assignMerchantPayKey(merchantPayKey);
		return order;
	}

	private PaymentAttempt createApproveAttempt(String merchantPayKey, String pgPaymentId, int totalPayAmount) {
		return PaymentAttempt.createApproveRequested(
			merchantPayKey,
			pgPaymentId,
			totalPayAmount,
			PaymentProvider.NAVERPAY
		);
	}
}
