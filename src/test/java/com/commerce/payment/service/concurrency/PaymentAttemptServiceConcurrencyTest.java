package com.commerce.payment.service.concurrency;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.repository.OrderRepository;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.repository.PaymentAttemptRepository;
import com.commerce.payment.service.PaymentAttemptService;
import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.test.support.TestcontainersSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
class PaymentAttemptServiceConcurrencyTest {

	@Autowired
	private PaymentAttemptService paymentAttemptService;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		paymentAttemptRepository.deleteAllInBatch();
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("동시에 승인 시도 이력을 생성해도 approve attempt는 하나만 생성된다")
	@Test
	void getOrCreateApproveRequested_whenConcurrentCreate_createSingleApproveAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-ATTEMPT-CON-1";
		String paymentId = "pg-attempt-con-1";
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> paymentAttemptService.getOrCreateApproveRequested(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			1000
		), errors);

		// then
		long approveAttemptCount = paymentAttemptRepository.findAll().stream()
			.filter(attempt -> attempt.getMerchantPayKey().equals(merchantPayKey))
			.filter(attempt -> attempt.getPaymentId().equals(paymentId))
			.filter(attempt -> attempt.getType() == PaymentAttemptType.APPROVE)
			.count();
		assertThat(approveAttemptCount).isEqualTo(1L);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시에 취소 시도 이력을 생성해도 cancel attempt는 하나만 생성된다")
	@Test
	void getOrCreateCancelRequested_whenConcurrentCreate_createSingleCancelAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-ATTEMPT-CON-2";
		String paymentId = "pg-attempt-con-2";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		paymentAttemptRepository.saveAndFlush(
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
			paymentAttemptService.getOrCreateCancelRequested(
				merchantPayKey,
				PaymentProvider.NAVERPAY,
				paymentId,
				1000
			);
		}, errors);

		// then
		long cancelAttemptCount = paymentAttemptRepository.findAll().stream()
			.filter(attempt -> attempt.getMerchantPayKey().equals(merchantPayKey))
			.filter(attempt -> attempt.getPaymentId().equals(paymentId))
			.filter(attempt -> attempt.getType() == PaymentAttemptType.CANCEL)
			.count();
		assertThat(cancelAttemptCount).isEqualTo(1L);
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
		return memberRepository.save(
			Member.builder()
				.email("payment-attempt-con-" + suffix + "@example.com")
				.password("password123")
				.username("u" + suffix)
				.build()
		);
	}

	private Order createOrder(Member member, String merchantPayKey, int totalPrice) {
		Product product = productRepository.save(
			Product.builder()
				.name("product-" + merchantPayKey)
				.price(totalPrice)
				.build()
		);

		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return orderRepository.saveAndFlush(order);
	}
}
