package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.usecase.ClosePaymentUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 환불을 만드는 트랜잭션이 겹칠 때의 불변식을 확인한다. 결제 행의 누적 환불액 갱신과 환불 요청 멱등키
 * 유일 제약이 이 방어의 전부라 실제 DB 위에서만 거동이 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고, 지켜야 하는 것은 "환불 사건이
 * 하나", "환불 총액이 승인 금액을 넘지 않는다"라는 불변식이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class ClosePaymentUseCaseConcurrencyTest {

	private static final int UNIT_PRICE = 5_000;
	private static final int THREADS = 2;
	private static final String PG_TRANSACTION_ID = "pg-tx-1";

	@Autowired
	private ClosePaymentUseCase closePaymentUseCase;

	@Autowired
	private RefundRepository refundRepository;

	@MockitoBean
	private NotificationPort notificationPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			refundPersistence, paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("같은 환불 요청 멱등키를 가진 반려가 동시에 와도 환불 사건이 하나만 선다")
	@Test
	void reject_whenSameRequestKeyRaces_opensSingleRefund() throws InterruptedException {
		Payment payment = inProgressPayment();

		int failures = runConcurrently(index -> closePaymentUseCase.rejectOrderNotPayable(
			paymentPersistence.findById(payment.getId()).orElseThrow(),
			OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID));

		// 조회가 둘 다 못 찾은 것을 유일 제약이 잡고, 그것을 지나가더라도 결제 버전이 받는다.
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(failures).isLessThan(THREADS);
		assertThat(reload(payment).getTotalRefundedAmount()).isEqualTo(payment.getAmount());
	}

	@DisplayName("사유가 다른 반려가 동시에 와도 환불 총액이 승인 금액을 넘지 않는다")
	@Test
	void reject_whenDifferentReasonsRace_keepsTotalWithinApprovedAmount() throws InterruptedException {
		Payment payment = inProgressPayment();

		int failures = runConcurrently(index -> {
			Payment loaded = paymentPersistence.findById(payment.getId()).orElseThrow();
			if (index == 0) {
				closePaymentUseCase.rejectOrderNotPayable(
					loaded, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);
			} else {
				closePaymentUseCase.rejectAmountMismatch(loaded, payment.getAmount(), PG_TRANSACTION_ID);
			}
		});

		// 키가 달라 유일 제약에는 걸리지 않는다. 그 자리는 누적 환불액이 올린 결제 버전이 받는다.
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(failures).isLessThan(THREADS);
		assertThat(reload(payment).getTotalRefundedAmount()).isEqualTo(payment.getAmount());
	}

	@DisplayName("환불 상태를 바꾸는 일과 새 환불을 만드는 일이 겹쳐도 서로 부딪히지 않는다")
	@Test
	void refundTransitionAndRejection_whenRacing_bothSucceed() throws InterruptedException {
		Payment payment = inProgressPayment();
		Refund pending = openPendingMemberRefund(payment, payment.getAmount() / 2);

		int failures = runConcurrently(index -> {
			if (index == 0) {
				Refund loaded = refundRepository.findById(pending.getId()).orElseThrow();
				loaded.complete("pg-refund-1");
				refundRepository.saveChecked(loaded);
			} else {
				closePaymentUseCase.rejectOrderNotPayable(
					paymentPersistence.findById(payment.getId()).orElseThrow(),
					OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);
			}
		});

		// 상태 전이는 한도를 바꾸지 않으므로 결제가 알 필요가 없다. 부딪히게 만들면 대사가 한 바퀴 돌
		// 때마다 회원의 환불 요청이 밀린다.
		assertThat(failures).isZero();
		assertThat(refundRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(RefundStatus.SUCCEEDED);
		List<Refund> refunds = refundPersistence.findAll();
		assertThat(refunds).hasSize(2);
		assertThat(reload(payment).getTotalRefundedAmount()).isEqualTo(payment.getAmount());
	}

	/** 밀려난 쪽의 수를 돌려준다. 어느 쪽이 밀렸는지는 타이밍에 달려 있어 단언하지 않는다 */
	private int runConcurrently(IntConsumer request) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			int index = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					request.accept(index);
				} catch (Exception ex) {
					failures.add(ex);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
		return failures.size();
	}

	private Payment reload(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}

	/** 결과를 모르는 회원 환불. 대사가 그것을 확정하는 사이에 반려가 겹치는 상황을 만든다 */
	private Refund openPendingMemberRefund(Payment payment, int amount) {
		payment.recordApproval(payment.getAmount(), PG_TRANSACTION_ID);
		Refund refund = payment.openRefund(Optional.empty(), amount, RefundReason.ORDER_CANCELED, "IDEM-pending");
		refund.markInProgress(LocalDateTime.now());
		refund.markUnknown();
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		assertThat(saved.getRequester()).isEqualTo(RefundRequester.MEMBER);
		return saved;
	}

	private Payment inProgressPayment() {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + (++uniqueSuffix), "idem-" + uniqueSuffix, order.getTotalPrice());
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		return paymentPersistence.save(payment);
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("reject-race-" + (++uniqueSuffix) + "@example.com", "password123", "race-user"));
	}

	private Order saveOrder(Member member) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 2, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}
}
