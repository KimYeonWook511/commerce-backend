package com.commerce.payment.naverpay.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.service.NaverPayService;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.repository.PaymentAttemptRepository;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.payment.service.PaymentService;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;
import com.commerce.test.support.TestcontainersSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
class NaverPayServiceConcurrencyTest {

	@Autowired
	private NaverPayService naverPayService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@MockitoBean
	private NaverPayClient naverPayClient;

	@MockitoSpyBean
	private PaymentService paymentService;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		paymentAttemptRepository.deleteAllInBatch();
		paymentRepository.deleteAllInBatch();
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("같은 결제 승인 요청이 동시에 들어와도 payment는 하나만 생성되고 cancel은 호출되지 않는다")
	@Test
	void approve_whenConcurrentRequest_createSinglePaymentWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-1";
		String paymentId = "pg-naver-con-1";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResult> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return buildApprovalResponse(merchantPayKey, 1000, "Success", "SUCCESS", paymentId);
			}
			return buildApprovalResponse(merchantPayKey, 1000, "AlreadyOnGoing", "SUCCESS", paymentId);
		}).when(naverPayClient).approve(paymentId);

		// when
		runConcurrent(20, () -> results.add(naverPayService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.count()).isEqualTo(1L);
		assertThat(orderRepository.findByMerchantPayKey(merchantPayKey).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(results).isNotEmpty();
		assertThat(results.stream().map(NaverPayApproveResult::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		assertThat(results.stream().map(NaverPayApproveResult::getStatus))
			.anyMatch(status -> status == NaverPayApproveStatus.SUCCESS);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("동시에 AlreadyComplete 응답이 들어와도 history 경로로 payment는 하나만 생성된다")
	@Test
	void approve_whenConcurrentRequestAndAlreadyComplete_createSinglePayment() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-2";
		String paymentId = "pg-naver-con-2";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResult> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return buildApprovalResponse(merchantPayKey, 1000, "AlreadyComplete", "SUCCESS", paymentId);
			}
			return buildApprovalResponse(merchantPayKey, 1000, "AlreadyOnGoing", "SUCCESS", paymentId);
		}).when(naverPayClient).approve(paymentId);
		given(naverPayClient.getAllHistory(paymentId))
			.willReturn(buildHistoryResponse(merchantPayKey, 1000, "SUCCESS", "01", paymentId));

		// when
		runConcurrent(20, () -> results.add(naverPayService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.count()).isEqualTo(1L);
		assertThat(orderRepository.findByMerchantPayKey(merchantPayKey).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		assertThat(results.stream().map(NaverPayApproveResult::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("동시에 merchantPayKey가 다른 승인 응답이 들어오면 payment 없이 approve attempt만 FAILED가 된다")
	@Test
	void approve_whenConcurrentRequestAndMerchantPayKeyMismatch_failApproveWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-3";
		String paymentId = "pg-naver-con-3";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayClient.approve(paymentId))
			.willReturn(buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS", paymentId));

		// when
		runConcurrent(20, () -> naverPayService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).hasSize(20);
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode())
					.isIn(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		assertThat(paymentRepository.findByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("동시에 금액이 다른 승인 응답이 들어오면 payment 없이 cancel attempt는 REQUESTED로 유지된다")
	@Test
	void approve_whenConcurrentRequestAndAmountMismatch_keepSingleCancelRequested() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-4";
		String paymentId = "pg-naver-con-4";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayClient.approve(paymentId))
			.willReturn(buildApprovalResponse(merchantPayKey, 2000, "Success", "SUCCESS", paymentId));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("AlreadyOnGoing", paymentId));

		// when
		runConcurrent(20, () -> naverPayService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).isNotEmpty();
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
			});
		assertThat(paymentRepository.findByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getFailCode()).isEqualTo(PaymentAttemptFailCode.AMOUNT_MISMATCH);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.CANCEL
		).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	@DisplayName("동시에 복구 요청이 들어와도 SUCCEEDED approve attempt는 history 기반으로 payment 하나만 반영한다")
	@Test
	void approve_whenConcurrentRecoveryRequest_createSinglePaymentByHistory() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-5";
		String paymentId = "pg-naver-con-5";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			merchantPayKey, paymentId, 1000, PaymentProvider.NAVERPAY
		);
		attempt.approveSucceed(LocalDateTime.now());
		paymentAttemptRepository.saveAndFlush(attempt);

		ConcurrentLinkedQueue<NaverPayApproveResult> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		given(naverPayClient.getAllHistory(paymentId))
			.willReturn(buildHistoryResponse(merchantPayKey, 1000, "SUCCESS", "01", paymentId));

		// when
		runConcurrent(20, () -> results.add(naverPayService.approve(member.getId(), merchantPayKey, paymentId)), errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(paymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.count()).isEqualTo(1L);
		assertThat(orderRepository.findByMerchantPayKey(merchantPayKey).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PAID);
		assertThat(results).isNotEmpty();
		assertThat(results.stream().map(NaverPayApproveResult::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS);
	}

	@DisplayName("동시에 중복 결제 보상 취소 경로로 들어가도 cancel attempt는 하나만 생성된다")
	@Test
	void approve_whenConcurrentDuplicateCompensation_createSingleCancelAttempt() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-6";
		String paymentId = "pg-naver-con-6";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayClient.approve(paymentId))
			.willReturn(buildApprovalResponse(merchantPayKey, 1000, "Success", "SUCCESS", paymentId));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("AlreadyOnGoing", paymentId));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentService)
			.completeApprove(eq(merchantPayKey), eq(PaymentProvider.NAVERPAY), eq(paymentId), any(LocalDateTime.class));

		// when
		runConcurrent(20, () -> naverPayService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).isNotEmpty();
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
		assertThat(paymentAttemptRepository.findAll().stream()
			.filter(attempt -> attempt.getMerchantPayKey().equals(merchantPayKey))
			.filter(attempt -> attempt.getType() == PaymentAttemptType.CANCEL)
			.count()).isEqualTo(1L);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.CANCEL
			).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
		then(naverPayClient).should(atLeastOnce()).cancel(any());
	}

	@DisplayName("approve mismatch와 history mismatch가 섞여 동시에 들어와도 외부에는 PAYMENT_MERCHANT_KEY_MISMATCH 또는 PAYMENT_NOT_FOUND만 노출되고 approve attempt는 MERCHANT_PAY_KEY_MISMATCH로 FAILED가 된다")
	@Test
	void approve_whenConcurrentApproveAndHistoryMismatch_failApproveConsistently() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-7";
		String paymentId = "pg-naver-con-7";
		Member member = createMember();
		createOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() % 2 == 0) {
				return buildApprovalResponse(merchantPayKey, 1000, "AlreadyComplete", "SUCCESS", paymentId);
			}
			return buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS", paymentId);
		}).when(naverPayClient).approve(paymentId);
		given(naverPayClient.getAllHistory(paymentId))
			.willReturn(buildHistoryResponse("OTHER-PAY", 1000, "SUCCESS", "01", paymentId));

		// when
		runConcurrent(20, () -> naverPayService.approve(member.getId(), merchantPayKey, paymentId), errors);

		// then
		assertThat(errors).hasSize(20);
		assertThat(errors)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(PaymentException.class);
				assertThat(((PaymentException)error).getErrorCode())
					.isIn(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
		assertThat(paymentRepository.findByMerchantPayKey(merchantPayKey)).isEmpty();
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.APPROVE
		).orElseThrow().getFailCode()).isEqualTo(PaymentAttemptFailCode.MERCHANT_PAY_KEY_MISMATCH);
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			PaymentAttemptType.CANCEL
		)).isEmpty();
		then(naverPayClient).should(never()).cancel(any());
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
		return memberRepository.save(
			Member.builder()
				.email("naverpay-con-" + suffix + "@example.com")
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
				.status(ProductStatus.ON_SALE)
				.build()
		);

		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return orderRepository.saveAndFlush(order);
	}

	private NaverPayResponse<NaverPayApproveBody> buildApprovalResponse(
		String merchantPayKey,
		int amount,
		String code,
		String state,
		String paymentId
	) {
		NaverPayResponse<NaverPayApproveBody> response = new NaverPayResponse<>();
		NaverPayApproveBody body = new NaverPayApproveBody();
		NaverPayApproveBody.Detail detail = new NaverPayApproveBody.Detail();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(detail, "paymentId", paymentId);
		ReflectionTestUtils.setField(detail, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(detail, "admissionState", state);
		ReflectionTestUtils.setField(detail, "totalPayAmount", amount);
		ReflectionTestUtils.setField(body, "detail", detail);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildHistoryResponse(
		String merchantPayKey,
		int amount,
		String admissionState,
		String admissionTypeCode,
		String paymentId
	) {
		NaverPayResponse<NaverPayHistoryBody> response = new NaverPayResponse<>();
		NaverPayHistoryBody body = new NaverPayHistoryBody();
		NaverPayHistoryBody.History history = new NaverPayHistoryBody.History();
		ArrayList<NaverPayHistoryBody.History> histories = new ArrayList<>();

		ReflectionTestUtils.setField(response, "code", "Success");
		ReflectionTestUtils.setField(history, "paymentId", paymentId);
		ReflectionTestUtils.setField(history, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(history, "admissionState", admissionState);
		ReflectionTestUtils.setField(history, "admissionTypeCode", admissionTypeCode);
		ReflectionTestUtils.setField(history, "totalPayAmount", amount);
		histories.add(history);
		ReflectionTestUtils.setField(body, "list", histories);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayCancelBody> buildCancelResponse(String code, String paymentId) {
		NaverPayResponse<NaverPayCancelBody> response = new NaverPayResponse<>();
		NaverPayCancelBody body = new NaverPayCancelBody();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(body, "paymentId", paymentId);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}
}
