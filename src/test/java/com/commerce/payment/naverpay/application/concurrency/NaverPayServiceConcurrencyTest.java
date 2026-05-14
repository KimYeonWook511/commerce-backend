package com.commerce.payment.naverpay.application.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.UUID;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.application.NaverPayApprovalService;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.result.NaverPayApproveStatus;
import com.commerce.payment.naverpay.infrastructure.NaverPayGateway;
import com.commerce.payment.naverpay.infrastructure.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.infrastructure.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.infrastructure.result.NaverPayHistoryResult;
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
class NaverPayServiceConcurrencyTest {

	@Autowired
	private NaverPayApprovalService naverPayApprovalService;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@MockitoBean
	private NaverPayGateway naverPayGateway;

	@MockitoSpyBean
	private PaymentApprovalService paymentApprovalService;

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

	@DisplayName("같은 결제 승인 요청이 동시에 들어와도 payment는 하나만 생성되고 cancel은 호출되지 않는다")
	@Test
	void approve_whenConcurrentRequest_createSinglePaymentWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-1";
		String paymentId = "pg-naver-con-1";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return NaverPayApproveResult.success(merchantPayKey, 1000);
			}
			return NaverPayApproveResult.processing();
		}).when(naverPayGateway).approve(paymentId);

		// when
		runConcurrent(20, () -> results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey(merchantPayKey))
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(results).isNotEmpty();
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.anyMatch(status -> status == NaverPayApproveStatus.SUCCESS);
		assertThat(paymentPersistence.findAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 AlreadyComplete 응답이 들어와도 history 경로로 payment는 하나만 생성된다")
	@Test
	void approve_whenConcurrentRequestAndAlreadyComplete_createSinglePayment() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-2";
		String paymentId = "pg-naver-con-2";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return NaverPayApproveResult.alreadyComplete();
			}
			return NaverPayApproveResult.processing();
		}).when(naverPayGateway).approve(paymentId);
		given(naverPayGateway.getApprovalHistory(paymentId))
			.willReturn(NaverPayHistoryResult.approved(merchantPayKey, 1000));

		// when
		runConcurrent(20, () -> results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey(merchantPayKey))
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 merchantPayKey가 다른 승인 응답이 들어오면 payment 없이 approve attempt만 FAILED가 된다")
	@Test
	void approve_whenConcurrentRequestAndMerchantPayKeyMismatch_failApproveWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-3";
		String paymentId = "pg-naver-con-3";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(paymentId))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).hasSize(20);
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode())
					.isIn(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		assertThat(paymentPersistence.findPaymentByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(paymentPersistence.findAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 금액이 다른 승인 응답이 들어오면 payment 없이 cancel attempt는 REQUESTED로 유지된다")
	@Test
	void approve_whenConcurrentRequestAndAmountMismatch_keepSingleCancelRequested() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-4";
		String paymentId = "pg-naver-con-4";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(paymentId))
			.willReturn(NaverPayApproveResult.success(merchantPayKey, 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).isNotEmpty();
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		assertThat(paymentPersistence.findPaymentByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getFailCode()).isEqualTo(PaymentAttemptFailCode.AMOUNT_MISMATCH);
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	@DisplayName("동시에 복구 요청이 들어와도 SUCCEEDED approve attempt는 history 기반으로 payment 하나만 반영한다")
	@Test
	void approve_whenConcurrentRecoveryRequest_createSinglePaymentByHistory() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-5";
		String paymentId = "pg-naver-con-5";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			merchantPayKey, paymentId, 1000, PaymentProvider.NAVERPAY
		);
		attempt.markApproveSucceeded(LocalDateTime.now());
		paymentPersistence.save(attempt);

		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		given(naverPayGateway.getApprovalHistory(paymentId))
			.willReturn(NaverPayHistoryResult.approved(merchantPayKey, 1000));

		// when
		runConcurrent(20, () -> results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey(merchantPayKey))
			.isEqualTo(OrderStatus.PAID);
		assertThat(results).isNotEmpty();
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS);
	}

	@DisplayName("동시에 중복 결제 보상 취소 경로로 들어가도 cancel attempt는 하나만 생성된다")
	@Test
	void approve_whenConcurrentDuplicateCompensation_createSingleCancelAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-6";
		String paymentId = "pg-naver-con-6";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(paymentId))
			.willReturn(NaverPayApproveResult.success(merchantPayKey, 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq(merchantPayKey), eq(PaymentProvider.NAVERPAY), eq(paymentId), any(LocalDateTime.class));

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).isNotEmpty();
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
		assertThat(paymentPersistence.countCancelAttempts(merchantPayKey)).isEqualTo(1L);
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		).getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		then(naverPayGateway).should(atLeastOnce()).cancel(any(), anyInt(), any());
	}

	@DisplayName("approve mismatch와 history mismatch가 섞여 동시에 들어와도 외부에는 PAYMENT_MERCHANT_KEY_MISMATCH 또는 PAYMENT_NOT_FOUND만 노출되고 approve attempt는 MERCHANT_PAY_KEY_MISMATCH로 FAILED가 된다")
	@Test
	void approve_whenConcurrentApproveAndHistoryMismatch_failApproveConsistently() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-7";
		String paymentId = "pg-naver-con-7";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() % 2 == 0) {
				return NaverPayApproveResult.alreadyComplete();
			}
			return NaverPayApproveResult.success("OTHER-PAY", 1000);
		}).when(naverPayGateway).approve(paymentId);
		given(naverPayGateway.getApprovalHistory(paymentId))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).hasSize(20);
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode())
					.isIn(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		assertThat(paymentPersistence.findPaymentByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(paymentPersistence.getAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.APPROVE
		).getFailCode()).isEqualTo(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH);
		assertThat(paymentPersistence.findAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	private void runConcurrent(
		int threadCount,
		Runnable task,
		ConcurrentLinkedQueue<Throwable> errors
	) throws Exception {
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
			.email("naverpay-con-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Order persistOrder(Member member, String merchantPayKey, int totalPrice) {
		Product product = productPersistence.save(createProduct("product-" + merchantPayKey, totalPrice));
		return orderPersistence.saveAndFlush(createOrder(member, product, merchantPayKey));
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
