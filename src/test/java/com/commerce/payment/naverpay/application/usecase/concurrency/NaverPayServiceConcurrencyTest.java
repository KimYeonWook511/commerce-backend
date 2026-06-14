package com.commerce.payment.naverpay.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.naverpay.application.usecase.NaverPayApprovalService;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.result.NaverPayApproveStatus;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.TestcontainersSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest(properties = {
	"spring.datasource.hikari.maximum-pool-size=30",
	"spring.datasource.hikari.minimum-idle=10",
	"spring.datasource.hikari.connection-timeout=30000"
})
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, PaymentReservationPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
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

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@MockitoBean
	private NaverPayGateway naverPayGateway;

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

	@DisplayName("같은 결제 승인 요청이 동시에 들어와도 payment는 하나만 생성되고 cancel은 호출되지 않는다")
	@Test
	void approve_whenConcurrentRequest_createSinglePaymentWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-1";
		String pgPaymentId = "pg-naver-con-1";
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return NaverPayApproveResult.success(merchantPayKey, 1000);
			}
			return NaverPayApproveResult.processing();
		}).when(naverPayGateway).approve(pgPaymentId);

		// when
		runConcurrent(20, () -> results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId)), errors);

		// then
		// race window 발생 시 일부 요청은 unique 위반(DataIntegrityViolationException),
		// USED 예약 후 미결제 구간 PAYMENT_NOT_FOUND, 또는 winner 성공 후 진입 가드 PAYMENT_DUPLICATE 에 도달한다.
		errors.forEach(e -> assertRaceOrPaymentError(e, PaymentErrorCode.PAYMENT_NOT_FOUND, PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusById(order.getId()))
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(results).isNotEmpty();
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.anyMatch(status -> status == NaverPayApproveStatus.SUCCESS);
		assertThat(paymentPersistence.findPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 AlreadyComplete 응답이 들어와도 history 경로로 payment는 하나만 생성된다")
	@Test
	void approve_whenConcurrentRequestAndAlreadyComplete_createSinglePayment() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-2";
		String pgPaymentId = "pg-naver-con-2";
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() == 1) {
				return NaverPayApproveResult.alreadyComplete();
			}
			return NaverPayApproveResult.processing();
		}).when(naverPayGateway).approve(pgPaymentId);
		given(naverPayGateway.getApprovalHistory(pgPaymentId))
			.willReturn(NaverPayHistoryResult.approved(merchantPayKey, 1000));

		// when
		runConcurrent(20, () -> results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId)), errors);

		// then
		// race window 발생 시 일부 요청은 unique 위반(DataIntegrityViolationException),
		// USED 예약 후 미결제 구간 PAYMENT_NOT_FOUND, 또는 winner 성공 후 진입 가드 PAYMENT_DUPLICATE 에 도달한다.
		errors.forEach(e -> assertRaceOrPaymentError(e, PaymentErrorCode.PAYMENT_NOT_FOUND, PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusById(order.getId()))
			.isEqualTo(OrderStatus.PAID);
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS || status == NaverPayApproveStatus.PROCESSING);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 merchantPayKey가 다른 승인 응답이 들어오면 payment 없이 approve payment만 FAILED가 된다")
	@Test
	void approve_whenConcurrentRequestAndMerchantPayKeyMismatch_failApproveWithoutCancel() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-3";
		String pgPaymentId = "pg-naver-con-3";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(pgPaymentId))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId), errors);

		// then
		// race window 시 일부 요청은 unique 위반(안전망 500), 나머지는 도메인 예외(MERCHANT_KEY_MISMATCH 또는 NOT_FOUND).
		assertThat(errors).hasSize(20);
		errors.forEach(e -> assertRaceOrPaymentError(
			e, PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND
		));
		assertThat(paymentPersistence.findApproveSucceeded(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(paymentPersistence.findPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("동시에 금액이 다른 승인 응답이 들어오면 payment 없이 cancel payment는 REQUESTED로 유지된다")
	@Test
	void approve_whenConcurrentRequestAndAmountMismatch_keepSingleCancelRequested() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-4";
		String pgPaymentId = "pg-naver-con-4";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(pgPaymentId))
			.willReturn(NaverPayApproveResult.success(merchantPayKey, 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId), errors);

		// then
		// race window 시 일부 요청은 unique 위반(안전망 500), 나머지는 도메인 AMOUNT_MISMATCH.
		// USED 예약 후 미결제 구간에서 PAYMENT_NOT_FOUND 도 발생할 수 있다.
		assertThat(errors).isNotEmpty();
		errors.forEach(e -> assertRaceOrPaymentError(e,
			PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND));
		assertThat(paymentPersistence.findApproveSucceeded(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getFailCode()).isEqualTo(PaymentFailCode.AMOUNT_MISMATCH);
		assertThat(paymentPersistence.countCancelPayments(merchantPayKey)).isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.CANCEL
		).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("SUCCEEDED Payment가 이미 있으면 동시 요청도 모두 멱등 응답을 반환한다")
	@Test
	void approve_whenConcurrentPaymentAlreadySucceeded_returnIdempotent() throws Exception {
		// given: 결제가 이미 완료된 상태 (reservation USED + SUCCEEDED payment) — 실제 결제 완료 상태와 동일
		// create()가 use()+payment를 원자적으로 처리하므로 SUCCEEDED payment가 있으면 reservation은 항상 USED다.
		// 동시 재진입은 USED 분기에서 같은 pgPaymentId payment를 찾아 멱등 응답으로 흡수한다.
		String merchantPayKey = "PAY-NAVER-CON-5";
		String pgPaymentId = "pg-naver-con-5";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey(merchantPayKey).orElseThrow();
		reservation.use();
		reservationPersistence.save(reservation);
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		payment.succeed(LocalDateTime.now());
		paymentPersistence.save(payment);

		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId), errors);

		// then: SUCCEEDED Payment 발견 → findApproveSucceeded 에서 조기 반환, 에러 없음
		assertThat(errors).isEmpty();
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.findApproveSucceeded(merchantPayKey)).isPresent();
	}

	@DisplayName("같은 예약에 다른 pgPaymentId 승인 2건이 동시에 들어오면 한쪽만 payment를 생성하고 PG를 호출하며 나머지는 PG 호출 전에 차단된다")
	@Test
	void approve_whenConcurrentDifferentPgPaymentId_onlyOnePaymentCreatedAndOtherBlockedBeforePg() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-8";
		String pgPaymentIdA = "pg-naver-con-8-a";
		String pgPaymentIdB = "pg-naver-con-8-b";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);

		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<NaverPayApproveResponse> results = new ConcurrentLinkedQueue<>();

		given(naverPayGateway.approve(any()))
			.willReturn(NaverPayApproveResult.success(merchantPayKey, 1000));

		// when: 2개 스레드가 같은 예약에 다른 pgPaymentId로 동시 승인
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> {
				try {
					startLatch.await();
					results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentIdA));
				} catch (Throwable ex) {
					errors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			executor.submit(() -> {
				try {
					startLatch.await();
					results.add(naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentIdB));
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

		// then: payment는 정확히 1건만 생성됨 (진 쪽 reservation·payment 트랜잭션 롤백)
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		// 이긴 쪽(winner)은 SUCCESS 응답
		assertThat(results).hasSize(1);
		assertThat(results.stream().map(NaverPayApproveResponse::getStatus))
			.allMatch(status -> status == NaverPayApproveStatus.SUCCESS);
		// 진 쪽(loser)은 에러로 차단됨 (concurrent·sequential 모두 PAYMENT_RESERVATION_ALREADY_USED로 일관)
		assertThat(errors).hasSize(1);
		assertThat(errors.stream().findFirst().get()).isInstanceOf(PaymentException.class);
		assertThat(((PaymentException) errors.stream().findFirst().get()).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED);
		// PG approve는 정확히 1번 호출됨 (진 쪽은 PG 호출 전에 차단됨)
		then(naverPayGateway).should(Mockito.times(1)).approve(any());
	}

	@DisplayName("approve mismatch와 history mismatch가 섞여 동시에 들어와도 외부에는 PAYMENT_MERCHANT_KEY_MISMATCH 또는 PAYMENT_NOT_FOUND만 노출되고 approve payment는 MERCHANT_PAY_KEY_MISMATCH로 FAILED가 된다")
	@Test
	void approve_whenConcurrentApproveAndHistoryMismatch_failApproveConsistently() throws Exception {
		// given
		String merchantPayKey = "PAY-NAVER-CON-7";
		String pgPaymentId = "pg-naver-con-7";
		Member member = memberPersistence.save(createMember());
		persistOrder(member, merchantPayKey, 1000);
		AtomicInteger approveCallCount = new AtomicInteger();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		Mockito.doAnswer(invocation -> {
			if (approveCallCount.incrementAndGet() % 2 == 0) {
				return NaverPayApproveResult.alreadyComplete();
			}
			return NaverPayApproveResult.success("OTHER-PAY", 1000);
		}).when(naverPayGateway).approve(pgPaymentId);
		given(naverPayGateway.getApprovalHistory(pgPaymentId))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when
		runConcurrent(20, () -> naverPayApprovalService.approve(member.getId(), merchantPayKey, pgPaymentId), errors);

		// then
		// race window 시 일부 요청은 unique 위반(안전망 500), 나머지는 도메인 mismatch 예외.
		assertThat(errors).hasSize(20);
		errors.forEach(e -> assertRaceOrPaymentError(
			e, PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH, PaymentErrorCode.PAYMENT_NOT_FOUND
		));
		assertThat(paymentPersistence.findApproveSucceeded(merchantPayKey)).isEmpty();
		assertThat(paymentPersistence.countPayments(merchantPayKey, pgPaymentId, PaymentType.APPROVE))
			.isEqualTo(1L);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(paymentPersistence.getPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE
		).getFailCode()).isEqualTo(PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH);
		assertThat(paymentPersistence.findPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.CANCEL
		)).isEmpty();
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	/**
	 * find-first 정책에서 race window 에 빠진 요청은 unique 위반(DataIntegrityViolationException)
	 * 으로 안전망 500 에 도달하거나, 사전 find 분기에 진입해 도메인 예외(PaymentException) 가 발생한다.
	 * @Version 낙관적 락 충돌 시 PAYMENT_RESERVATION_ALREADY_USED 는 항상 유효한 race 에러로 허용한다.
	 * 두 형태 모두 허용한다.
	 */
	private static void assertRaceOrPaymentError(Throwable error, PaymentErrorCode... allowedDomainCodes) {
		if (error instanceof DataIntegrityViolationException) {
			return;
		}
		assertThat(error).isInstanceOf(PaymentException.class);
		PaymentErrorCode errorCode = (PaymentErrorCode) ((PaymentException) error).getErrorCode();
		if (errorCode == PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED) {
			return;
		}
		assertThat(errorCode).isIn((Object[]) allowedDomainCodes);
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
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));
		reservationPersistence.save(
			PaymentReservation.createReserved(order.getId(), member.getId(), totalPrice, PaymentProvider.NAVERPAY,
				merchantPayKey, LocalDateTime.now().plusMinutes(15))
		);
		return order;
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}
}
