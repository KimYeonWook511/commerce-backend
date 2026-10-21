package com.commerce.order.application.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.order.application.service.ExpireOrderService;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.outbox.infrastructure.persistence.support.OutboxPersistenceTestSupport;
import com.commerce.payment.application.service.PaymentApprovalService;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 만료 배치가 같은 주문을 동시에 정리할 때 지켜야 하는 것을 실제 DB 위에서 확인한다. 주문이 한 번만
 * 취소되는 것은 저장 시점의 낙관 락 충돌이 만들고, 재고 복구 outbox 이벤트가 정확히 하나만 남는 것은
 * 진 쪽 트랜잭션이 그 이벤트까지 함께 되돌리는 것이 만든다. 리포지토리나 outbox를 대역으로 바꾸면 그
 * 경합의 무대 자체가 사라져 이 두 가지를 확인할 수 없다.
 *
 * <p>만료 반영이 결제·주문 행을 읽은 뒤 상대 흐름이 먼저 커밋하는 경합은 그 순서를 이 시험이 잡는다.
 * 반영을 읽은 자리에 세워 두고 그 사이 상대를 커밋시킬 뿐, 읽는 값과 저장 결과는 진짜 리포지토리와
 * 진짜 낙관 락이 정한다. 진 반영이 내는 예외 타입도 확인한다 — 배치가 그 타입에 재시도를 걸어 두었으므로
 * 다른 예외로 바뀌면 재시도 대신 건너뛰기가 걸린다.
 */
@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	OrderPersistenceTestSupport.class,
	OutboxPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class
})
class ExpireOrderServiceConcurrencyTest {

	private static final int THREADS = 2;
	private static final long MEMBER_ID = 1L;

	/** 순서를 강제할 만료 반영을 이 이름의 스레드로 돌려, 대역이 그 반영만 세워 두게 한다 */
	private static final String PAUSED_EXPIRATION = "expire-order-paused-expiration";

	private static final long WAIT_SECONDS = 10;

	@Autowired
	private ExpireOrderService expireOrderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentApprovalService paymentApprovalService;

	/** 만료 반영이 주문을 읽는 시점을 잡으려고 감싼다 */
	@MockitoSpyBean
	private OrderRepository orderRepository;

	/** 만료 반영이 결제를 읽거나 저장하는 시점을 잡으려고 감싼다 */
	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private OutboxPersistenceTestSupport outboxPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(paymentPersistence, orderPersistence, outboxPersistence);
	}

	@DisplayName("같은 주문에 만료 처리가 동시에 들어와도 한 번만 취소되고 재고 복구 outbox 이벤트도 하나만 남는다")
	@Test
	void expireOrder_whenConcurrentRequests_convergesToOneCancellation() throws Exception {
		// given
		Order saved = saveOrder();
		LocalDateTime requestedAt = LocalDateTime.now();

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(() -> expireOrderService.expireOrder(saved.getId(), requestedAt), errors);

		// then
		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(outboxPersistence.count()).isEqualTo(1L);
		assertThat(errors).hasSize(THREADS - 1);
	}

	// ── 반영이 결제를 읽은 뒤 상대가 그 결제 행을 바꿔 먼저 커밋한다 ──

	@DisplayName("만료 반영이 결제를 읽은 뒤 승인 호출 직전 전이가 먼저 커밋되면 만료가 성립하지 않고 승인이 이어진다")
	@Test
	void expireOrder_whenApprovalCallCommitsAfterRead_doesNotExpire() throws InterruptedException {
		// given
		Order order = saveOrder();
		Payment payment = saveReadyPayment(order);
		Expiration expiration = expireWhilePausedAfter(pauseAfterPaymentRead(), order, () ->
			paymentService.markInProgress(payment.getId(), "pg-payment-" + payment.getId(), LocalDateTime.now()));

		// then
		expiration.assertLostOptimisticLock();
		assertNotExpired(order);
		assertThat(paymentStatus(payment)).isEqualTo(PaymentStatus.IN_PROGRESS);

		// 다시 판정해도 승인을 부른 뒤의 결제라 만료하지 않고, 승인은 그대로 이어져 확정된다.
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		assertNotExpired(order);
		paymentApprovalService.complete(payment.getId(), order.getId(), payment.getAmount(), "pg-tx-1");
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
		assertThat(paymentStatus(payment)).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("만료 반영이 결제를 읽은 뒤 결제 시작이 그 결제를 대체해도 다시 판정한 반영이 새 결제를 종결한다")
	@Test
	void expireOrder_whenPaymentRestartedAfterRead_closesNewPaymentOnRejudge() throws InterruptedException {
		// given
		Order order = saveOrder();
		Payment previous = saveReadyPayment(order);
		AtomicReference<Payment> restarted = new AtomicReference<>();
		Expiration expiration = expireWhilePausedAfter(pauseAfterPaymentRead(), order, () ->
			restarted.set(paymentService.start(order.getId(), MEMBER_ID, PaymentPg.NAVERPAY,
				"idem-restart-" + (++uniqueSuffix), order.getTotalPrice())));

		// then
		expiration.assertLostOptimisticLock();
		assertNotExpired(order);
		Payment superseded = reload(previous);
		assertThat(superseded.getCloseCode()).isEqualTo(PaymentCloseCode.SUPERSEDED);
		assertThat(paymentStatus(restarted.get())).isEqualTo(PaymentStatus.READY);

		// 다시 판정한 반영은 새 결제를 읽어 함께 종결한다.
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(outboxPersistence.count()).isEqualTo(1L);
		Payment closed = reload(restarted.get());
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.ORDER_EXPIRED);
		assertThat(paymentPersistence.findActiveByOrderId(order.getId())).isEmpty();
	}

	@DisplayName("만료 반영이 결제를 읽은 뒤 그 결제의 승인이 확정되면 만료가 성립하지 않는다")
	@Test
	void expireOrder_whenApprovalConfirmedAfterRead_doesNotExpire() throws InterruptedException {
		// given
		Order order = saveOrder();
		Payment payment = saveReadyPayment(order);
		Expiration expiration = expireWhilePausedAfter(pauseAfterPaymentRead(), order, () -> {
			paymentService.markInProgress(payment.getId(), "pg-payment-" + payment.getId(), LocalDateTime.now());
			paymentApprovalService.complete(payment.getId(), order.getId(), payment.getAmount(), "pg-tx-1");
		});

		// then
		expiration.assertLostOptimisticLock();
		assertPaidAndNotExpired(order, payment);

		// 다시 판정하면 결제가 종결할 수 없다고 답해 오류 없이 멈춘다.
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		assertPaidAndNotExpired(order, payment);
	}

	@DisplayName("만료 반영이 결제를 읽은 뒤 다른 트랜잭션이 결제 행을 바꾸면 주문 취소도 함께 되돌아가고 다시 판정된다")
	@Test
	void expireOrder_whenPaymentRowChangedAfterRead_rollsBackOrderCancellation() throws InterruptedException {
		// given
		Order order = saveOrder();
		Payment payment = saveReadyPayment(order);
		Expiration expiration = expireWhilePausedAfter(pauseAfterPaymentRead(), order, () ->
			paymentService.expire(payment.getId()));

		// then
		expiration.assertLostOptimisticLock();
		assertNotExpired(order);
		// 결제 행에는 상대가 쓴 종결만 남고 반영이 쓴 종결 코드는 남지 않는다.
		assertThat(reload(payment).getCloseCode()).isEqualTo(PaymentCloseCode.SESSION_TIMEOUT);

		// 다시 판정한 반영은 활성 결제가 없음을 보고 주문을 만료시킨다.
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(outboxPersistence.count()).isEqualTo(1L);
	}

	// ── 반영이 결제를 저장한 뒤 상대가 주문 행을 바꿔 먼저 커밋한다 ──

	@DisplayName("만료 반영이 결제를 종결한 뒤 주문 행이 먼저 바뀌면 결제 종결도 함께 되돌아가 결제가 승인 전으로 남는다")
	@Test
	void expireOrder_whenOrderRowChangedAfterPaymentClosed_rollsBackPaymentClosure() throws InterruptedException {
		// given
		Order order = saveOrder();
		Payment payment = saveReadyPayment(order);
		// 상대는 결제 행을 건드리지 않는다. 반영이 그 행의 잠금을 쥐고 있어 건드리면 상대가 기다리게 된다.
		Expiration expiration = expireWhilePausedAfter(pauseAfterPaymentSave(), order, () ->
			orderPersistence.incrementVersion(order.getId()));

		// then
		expiration.assertLostOptimisticLock();
		assertNotExpired(order);
		Payment result = reload(payment);
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(result.getCloseCode()).isNull();
		assertThat(result.getActiveOrderKey()).isEqualTo(order.getId());
	}

	// ── 반영이 주문을 읽은 뒤 새 결제가 생겨 승인까지 확정된다 ──

	@DisplayName("만료 반영이 주문을 읽은 뒤 새로 생긴 결제의 승인이 먼저 확정되면 주문 행 충돌로 만료가 성립하지 않는다")
	@Test
	void expireOrder_whenNewPaymentApprovedAfterOrderRead_doesNotExpire() throws InterruptedException {
		// given
		Order order = saveOrder();
		AtomicReference<Payment> approved = new AtomicReference<>();
		Expiration expiration = expireWhilePausedAfter(pauseAfterOrderRead(), order, () -> {
			Payment started = paymentService.start(order.getId(), MEMBER_ID, PaymentPg.NAVERPAY,
				"idem-late-" + (++uniqueSuffix), order.getTotalPrice());
			paymentService.markInProgress(started.getId(), "pg-payment-" + started.getId(), LocalDateTime.now());
			paymentApprovalService.complete(started.getId(), order.getId(), started.getAmount(), "pg-tx-1");
			approved.set(started);
		});

		// then
		expiration.assertLostOptimisticLock();
		assertPaidAndNotExpired(order, approved.get());

		// 다시 판정하면 이번에는 그 결제를 읽고, 결제가 종결할 수 없다고 답해 오류 없이 멈춘다.
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		assertPaidAndNotExpired(order, approved.get());
	}

	// ── 순서 통제 ──

	/** 만료 반영이 주문을 읽은 자리에 세운다 */
	private Pause pauseAfterOrderRead() {
		Pause pause = new Pause();
		willAnswer(pause::callRealThenWait).given(orderRepository).findByIdWithItems(any());
		return pause;
	}

	/** 만료 반영이 결제를 읽은 자리에 세운다. 결제 시작도 같은 조회를 부르지만 반영 스레드만 선다 */
	private Pause pauseAfterPaymentRead() {
		Pause pause = new Pause();
		willAnswer(pause::callRealThenWait).given(paymentRepository).findActiveByOrderId(any());
		return pause;
	}

	/** 만료 반영이 결제 종결을 DB에 내보낸 자리에 세운다. 결제 행 잠금을 쥔 채 선다 */
	private Pause pauseAfterPaymentSave() {
		Pause pause = new Pause();
		willAnswer(pause::callRealThenWait).given(paymentRepository).save(any(Payment.class));
		return pause;
	}

	/**
	 * 만료 반영을 세워 둔 채 상대를 커밋시키고, 반영을 마저 진행시킨다. 상대가 끝난 뒤에야 반영이 이어지므로
	 * 커밋 순서가 늘 상대 먼저다.
	 */
	private Expiration expireWhilePausedAfter(Pause pause, Order order, Runnable counterpart)
		throws InterruptedException {
		Expiration expiration = new Expiration();
		Thread thread = new Thread(() -> {
			try {
				expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
			} catch (Throwable ex) {
				expiration.thrown.set(ex);
			}
		}, PAUSED_EXPIRATION);
		thread.start();

		assertThat(pause.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		counterpart.run();
		pause.resume.countDown();
		thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		expiration.paused.set(pause.resumedBySignal.get());
		return expiration;
	}

	private static boolean isPausedExpiration() {
		return PAUSED_EXPIRATION.equals(Thread.currentThread().getName());
	}

	private static final class Pause {

		private final CountDownLatch reached = new CountDownLatch(1);
		private final CountDownLatch resume = new CountDownLatch(1);
		/** 창이 실제로 열렸는지. 세워 둔 반영이 신호를 못 받고 그냥 지나가면 경합이 서지 않는다 */
		private final AtomicBoolean resumedBySignal = new AtomicBoolean(false);

		private Object callRealThenWait(InvocationOnMock invocation) throws Throwable {
			Object result = invocation.callRealMethod();
			if (isPausedExpiration() && reached.getCount() > 0) {
				reached.countDown();
				resumedBySignal.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return result;
		}
	}

	private static final class Expiration {

		private final AtomicReference<Throwable> thrown = new AtomicReference<>();
		private final AtomicBoolean paused = new AtomicBoolean(false);

		private void assertLostOptimisticLock() {
			assertThat(paused).isTrue();
			assertThat(thrown.get()).isInstanceOf(OptimisticLockingFailureException.class);
		}
	}

	// ── 헬퍼 ──

	private void assertNotExpired(Order order) {
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(outboxPersistence.count()).isZero();
	}

	private void assertPaidAndNotExpired(Order order, Payment payment) {
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
		assertThat(paymentStatus(payment)).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(outboxPersistence.count()).isZero();
	}

	private Order saveOrder() {
		Order order = Order.create(MEMBER_ID);
		order.addOrderItem(10L, 2, 1000);
		return orderPersistence.saveAndFlush(order);
	}

	private Payment saveReadyPayment(Order order) {
		int suffix = ++uniqueSuffix;
		return paymentPersistence.save(Payment.start(order.getId(), MEMBER_ID, PaymentPg.NAVERPAY,
			"PK-expire-" + suffix, "IDEM-expire-" + suffix, order.getTotalPrice()));
	}

	private Payment reload(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}

	private PaymentStatus paymentStatus(Payment payment) {
		return reload(payment).getStatus();
	}

	private void runConcurrent(Runnable task, ConcurrentLinkedQueue<Throwable> errors) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch readyLatch = new CountDownLatch(THREADS);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREADS);

		try {
			for (int i = 0; i < THREADS; i++) {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						task.run();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});
			}

			readyLatch.await();
			startLatch.countDown();
			boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
			assertThat(completed).isTrue();
		} finally {
			executor.shutdown();
			boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
			if (!terminated) {
				executor.shutdownNow();
			}
		}
	}
}
