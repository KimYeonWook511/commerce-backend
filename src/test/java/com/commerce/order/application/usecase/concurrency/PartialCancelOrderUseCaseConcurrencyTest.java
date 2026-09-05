package com.commerce.order.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderCancelLine;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.application.usecase.ReconcileRefundUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 부분취소 요청이 겹칠 때 지켜야 하는 것을 실제 DB 위에서 확인한다. 수량 축은 주문 행 잠금이, 금액 축은
 * 결제 행의 낙관 락이 지키는데 둘 다 트랜잭션 경계와 잠금이 진짜일 때만 성립하므로 협력 객체를 대역으로
 * 바꾸면 경합의 무대가 사라진다. 잠금 뒤에 품목을 지연 로딩으로 읽는 경로도 그대로 지난다 — 품목을 미리
 * 적재하면 앞 요청이 커밋한 취소수량을 읽는지가 확인되지 않는다.
 *
 * <p>어느 요청이 먼저 처리되는지는 단언하지 않는다. 그것은 타이밍에 달려 있고 지켜야 하는 것은 성공한
 * 것만 반영된 최종 상태가 어느 순서에서도 같다는 점이다.
 *
 * <p>결제 행 경합만 순서를 이 시험이 잡는다. 승자를 정하는 것이 스케줄러가 아니라 커밋 순서이고 그 순서는
 * 결제사 응답 시간이 정하므로, 우연에 맡기면 무엇을 재는지 흐려지고 간헐 실패가 결함인지 기대 동작인지
 * 가릴 수 없다. 두 참여자가 결제 행을 읽은 뒤 저장하는 시점만 통제하며, 읽는 값도 저장 결과도 진짜
 * 리포지토리와 진짜 낙관 락이 정한다.
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
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class,
	StockPersistenceTestSupport.class
})
class PartialCancelOrderUseCaseConcurrencyTest {

	private static final int APPLE_UNIT_PRICE = 10_000;
	private static final int PEAR_UNIT_PRICE = 20_000;
	private static final int APPLE_QUANTITY = 3;
	private static final int PEAR_QUANTITY = 1;
	private static final int THREADS = 2;

	/** 순서를 강제할 참여자를 이 이름으로 돌려, 대역이 그 참여자만 세워 두게 한다 */
	private static final String PAUSED_PARTICIPANT = "partial-cancel-paused-participant";

	private static final long WAIT_SECONDS = 10;

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

	@Autowired
	private RefundService refundService;

	@Autowired
	private ReconcileRefundUseCase reconcileRefundUseCase;

	/** 결제 행을 읽는 시점을 잡으려고 감싼다. 읽는 값과 저장 결과는 진짜 리포지토리가 정한다 */
	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

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

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, refundPersistence, paymentPersistence,
			memberPersistence, productPersistence, orderPersistence, stockPersistence
		);
	}

	// ── 수량 축 — 주문 행 잠금이 지킨다 ──

	@DisplayName("서로 다른 품목을 동시에 취소하면 성공한 것만 반영되고 거절된 요청은 자국을 남기지 않는다")
	@Test
	void cancel_whenDifferentItemsRace_appliesOnlySucceededRequests() throws InterruptedException {
		BaseOrder base = baseOrder();
		givenGatewaySucceeds();

		Race race = runConcurrently(index -> index == 0
			? cancel(base, "race-apple", List.of(new OrderCancelLine(base.appleItemId(), 1)))
			: cancel(base, "race-pear", List.of(new OrderCancelLine(base.pearItemId(), 1))));

		assertThat(race.unexpected).isEmpty();
		assertThat(race.succeeded).isNotEmpty();
		assertRejectedByPaymentRowContention(race);

		int apple = race.succeeded.containsKey(0) ? 1 : 0;
		int pear = race.succeeded.containsKey(1) ? 1 : 0;
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(apple);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isEqualTo(pear);
		assertThat(appleStock(base)).isEqualTo(apple);
		assertThat(pearStock(base)).isEqualTo(pear);
		assertThat(refundPersistence.findAll()).hasSize(apple + pear);
		assertThat(reload(base).getRefundOpenedAmount())
			.isEqualTo(apple * APPLE_UNIT_PRICE + pear * PEAR_UNIT_PRICE);
	}

	@DisplayName("같은 품목의 마지막 잔여를 동시에 취소하면 하나만 성공해 취소수량이 주문수량을 넘지 않는다")
	@Test
	void cancel_whenLastRemainingOfSameItemRaces_keepsTotalWithinOrderedQuantity() throws InterruptedException {
		BaseOrder base = baseOrder();
		givenGatewaySucceeds();
		// 사과 잔여를 1로 만든다.
		cancel(base, "seed-apple", List.of(new OrderCancelLine(base.appleItemId(), APPLE_QUANTITY - 1)));

		Race race = runConcurrently(index ->
			cancel(base, "last-apple-" + index, List.of(new OrderCancelLine(base.appleItemId(), 1))));

		assertThat(race.unexpected).isEmpty();
		assertThat(race.succeeded).hasSize(1);
		// 진 쪽이 잔여 초과로 걸린다는 것은 앞 요청이 커밋한 취소수량을 읽었다는 뜻이다. 낡은 값을 읽었다면
		// 여기를 지나 결제 행 경합에서야 걸린다.
		assertThat(race.rejected.values()).allSatisfy(rejection -> assertThat(rejection)
			.isInstanceOf(OrderException.class)
			.extracting(ex -> ((OrderException) ex).getErrorCode())
			.isEqualTo(OrderErrorCode.ORDER_CANCEL_QUANTITY_EXCEEDED));
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(APPLE_QUANTITY);
		assertThat(appleStock(base)).isEqualTo(APPLE_QUANTITY);
		assertThat(refundPersistence.findAll()).hasSize(2);
	}

	@DisplayName("마지막 잔여를 서로 다른 품목에서 동시에 취소해도 주문 전이는 한 번만 일어난다")
	@Test
	void cancel_whenLastRemainingOfEachItemRaces_transitionsOrderOnce() throws InterruptedException {
		BaseOrder base = baseOrder();
		givenGatewaySucceeds();
		// 사과 잔여도 배 잔여도 1로 만든다.
		cancel(base, "seed-apple", List.of(new OrderCancelLine(base.appleItemId(), APPLE_QUANTITY - 1)));

		Race race = runConcurrently(index -> index == 0
			? cancel(base, "last-apple", List.of(new OrderCancelLine(base.appleItemId(), 1)))
			: cancel(base, "last-pear", List.of(new OrderCancelLine(base.pearItemId(), 1))));

		assertThat(race.unexpected).isEmpty();
		assertThat(race.succeeded).isNotEmpty();
		assertRejectedByPaymentRowContention(race);

		if (race.succeeded.size() == THREADS) {
			assertThat(orderPersistence.getOrderStatusById(base.orderId())).isEqualTo(OrderStatus.CANCELED);
			// 마지막 잔여를 지운 요청 하나만 전이를 답으로 들고 온다.
			assertThat(race.succeeded.values().stream()
				.filter(result -> result.getStatus() == OrderStatus.CANCELED)
				.count()).isEqualTo(1);
		} else {
			// 거절된 쪽의 잔여가 남아 주문은 결제완료를 유지한다.
			assertThat(orderPersistence.getOrderStatusById(base.orderId())).isEqualTo(OrderStatus.PAID);
			assertThat(orderPersistence.getCancelledQuantity(base.appleItemId()))
				.isEqualTo(race.succeeded.containsKey(0) ? APPLE_QUANTITY : APPLE_QUANTITY - 1);
			assertThat(orderPersistence.getCancelledQuantity(base.pearItemId()))
				.isEqualTo(race.succeeded.containsKey(1) ? PEAR_QUANTITY : 0);
		}
	}

	// ── 금액 축 — 결제 행 경합. 두 순서를 각각 세운다 ──

	@DisplayName("앞선 환불의 확정이 먼저 커밋되면 이번 취소가 거절되고 아무 상태도 바뀌지 않는다")
	@Test
	void cancel_whenEarlierRefundIsSettledFirst_rejectsAndChangesNothing() throws InterruptedException {
		BaseOrder base = baseOrder();
		givenGatewayUnanswered();
		cancel(base, "settled-apple", List.of(new OrderCancelLine(base.appleItemId(), 1)));
		Long appleRefundId = onlyRefund().getId();

		CountDownLatch settlementCommitted = new CountDownLatch(1);
		AtomicBoolean paused = new AtomicBoolean(true);
		CountDownLatch cancelReadPayment = pauseAfterCancelReadsPayment(settlementCommitted, paused);

		AtomicReference<Throwable> rejection = new AtomicReference<>();
		Thread cancelling = startPausedParticipant(rejection, () ->
			cancel(base, "pear-after-settlement", List.of(new OrderCancelLine(base.pearItemId(), 1))));
		assertThat(cancelReadPayment.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		refundService.complete(appleRefundId, "pg-cancel-tx-apple");
		settlementCommitted.countDown();
		cancelling.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		// 순서가 서지 않으면 아래 단언이 순차 실행도 통과시키므로 창이 실제로 열렸는지를 먼저 본다.
		assertThat(paused).isTrue();
		assertThat(rejection.get()).isInstanceOf(OptimisticLockingFailureException.class);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isZero();
		assertThat(pearStock(base)).isZero();
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(orderPersistence.getOrderStatusById(base.orderId())).isEqualTo(OrderStatus.PAID);
		Payment payment = reload(base);
		assertThat(payment.getRefundOpenedAmount()).isEqualTo(APPLE_UNIT_PRICE);
		assertThat(payment.getRefundSucceededAmount()).isEqualTo(APPLE_UNIT_PRICE);
	}

	@DisplayName("이번 취소가 먼저 커밋되면 앞선 확정이 밀리고 대사가 그 환불을 확정한다")
	@Test
	void cancel_whenCommittedBeforeEarlierSettlement_letsReconciliationSettleIt() throws InterruptedException {
		BaseOrder base = baseOrder();
		givenGatewayUnanswered();
		cancel(base, "unsettled-apple", List.of(new OrderCancelLine(base.appleItemId(), 1)));
		Refund appleRefund = onlyRefund();

		CountDownLatch cancelCommitted = new CountDownLatch(1);
		AtomicBoolean paused = new AtomicBoolean(true);
		CountDownLatch settlementReadPayment = pauseAfterSettlementReadsPayment(cancelCommitted, paused);

		AtomicReference<Throwable> pushedBack = new AtomicReference<>();
		givenGatewaySucceeds();
		Thread settling = startPausedParticipant(pushedBack, () ->
			refundService.complete(appleRefund.getId(), "pg-cancel-tx-apple"));
		assertThat(settlementReadPayment.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		OrderCancelResult canceled = cancel(base, "pear-before-settlement",
			List.of(new OrderCancelLine(base.pearItemId(), 1)));
		cancelCommitted.countDown();
		settling.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		assertThat(paused).isTrue();
		// 이번 취소는 성공하고, 앞선 확정은 결제 행 경합으로 통째로 밀려 그 환불이 미확정으로 남는다.
		assertThat(canceled.getRefundedAmount()).isEqualTo(PEAR_UNIT_PRICE);
		assertThat(pushedBack.get()).isInstanceOf(PaymentException.class);
		assertThat(((PaymentException) pushedBack.get()).getErrorCode())
			.isEqualTo(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED);
		assertThat(reload(appleRefund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
		assertThat(reload(base).getRefundSucceededAmount()).isEqualTo(PEAR_UNIT_PRICE);

		givenGatewayHasSettled(appleRefund);
		reconcileRefundUseCase.reconcile();

		assertThat(reload(appleRefund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		// 밀린 확정이 다시 계상되지 않아 실제로 돌아간 금액이 두 번 오르지 않는다.
		assertThat(reload(base).getRefundSucceededAmount()).isEqualTo(APPLE_UNIT_PRICE + PEAR_UNIT_PRICE);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isEqualTo(1);
		assertThat(pearStock(base)).isEqualTo(1);
	}

	// ── 순서 통제 ──

	/**
	 * 취소가 결제 행을 읽은 자리에 세운다. 상대가 커밋을 마친 뒤에 저장하게 되어 그 저장이 낙관 락에
	 * 걸리는지가 드러난다.
	 *
	 * @param resume 상대가 커밋을 마쳤음을 알리는 신호
	 * @param paused 창이 실제로 열렸는지. 세워 둔 참여자가 신호를 못 받고 그냥 지나가면 경합이 서지 않는데,
	 *               그때도 단언이 순차 실행을 통과시키므로 부르는 쪽이 이 값을 함께 확인한다
	 * @return 취소가 결제 행을 읽고 멈춰 섰음을 알리는 신호
	 */
	private CountDownLatch pauseAfterCancelReadsPayment(CountDownLatch resume, AtomicBoolean paused) {
		CountDownLatch reachedPause = new CountDownLatch(1);
		willAnswer(invocation -> {
			Object loaded = invocation.callRealMethod();
			if (isPausedParticipant()) {
				reachedPause.countDown();
				paused.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return loaded;
		}).given(paymentRepository).findSucceededByMemberIdAndOrderId(any(), any());
		return reachedPause;
	}

	/** 환불 확정이 결제 행을 읽은 자리에 세운다. 인자의 뜻은 취소 쪽 통제와 같다 */
	private CountDownLatch pauseAfterSettlementReadsPayment(CountDownLatch resume, AtomicBoolean paused) {
		CountDownLatch reachedPause = new CountDownLatch(1);
		willAnswer(invocation -> {
			Object loaded = invocation.callRealMethod();
			if (isPausedParticipant()) {
				reachedPause.countDown();
				paused.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return loaded;
		}).given(paymentRepository).findById(any());
		return reachedPause;
	}

	private boolean isPausedParticipant() {
		return PAUSED_PARTICIPANT.equals(Thread.currentThread().getName());
	}

	private Thread startPausedParticipant(AtomicReference<Throwable> thrown, Runnable participant) {
		Thread thread = new Thread(() -> {
			try {
				participant.run();
			} catch (Throwable ex) {
				thrown.set(ex);
			}
		}, PAUSED_PARTICIPANT);
		thread.start();
		return thread;
	}

	// ── 헬퍼 ──

	private Race runConcurrently(IntFunction<OrderCancelResult> request) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		Race race = new Race();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			int index = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					race.succeeded.put(index, request.apply(index));
				} catch (RuntimeException ex) {
					race.rejected.put(index, ex);
				} catch (Exception ex) {
					race.unexpected.add(ex);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
		return race;
	}

	private void assertRejectedByPaymentRowContention(Race race) {
		assertThat(race.rejected.values()).allSatisfy(rejection ->
			assertThat(rejection).isInstanceOf(OptimisticLockingFailureException.class));
	}

	private OrderCancelResult cancel(BaseOrder base, String idempotencyKey, List<OrderCancelLine> lines) {
		return cancelOrderUseCase.cancel(base.memberId(), base.orderId(), idempotencyKey, lines);
	}

	private void givenGatewaySucceeds() {
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.succeeded("pg-cancel-tx-" + (++uniqueSuffix), "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}")));
	}

	/** 답을 못 받아 결과를 모르는 환불로 남긴다. 그 확정을 이 시험이 손에 쥐고 순서를 세운다 */
	private void givenGatewayUnanswered() {
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.unanswered("응답 없음",
				new PgCallRecord(PgErrorType.TIMEOUT, null, null, "{}")));
	}

	/** 결제사 이력에 그 환불이 완료로 있다. 대사가 이것을 읽어 확정한다 */
	private void givenGatewayHasSettled(Refund refund) {
		given(paymentGatewayPort.readHistory(any(), any(), any())).willReturn(PgHistoryResult.succeeded(
			List.of(new PgHistoryEntry(PgHistoryEntryType.REFUND, true, refund.getAmount(), LocalDateTime.now(),
				null, reload(refund).attemptKey(), "pg-history-tx-apple")), "성공"));
	}

	private Refund onlyRefund() {
		List<Refund> refunds = refundPersistence.findAll();
		assertThat(refunds).hasSize(1);
		return refunds.get(0);
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}

	private Payment reload(BaseOrder base) {
		return paymentPersistence.findById(base.paymentId()).orElseThrow();
	}

	private int appleStock(BaseOrder base) {
		return stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity();
	}

	private int pearStock(BaseOrder base) {
		return stockPersistence.findByProductId(base.pearProductId()).orElseThrow().getQuantity();
	}

	/** 1만원 사과 3개와 2만원 배 1개로 이루어진 기준 주문. 전액 승인이 끝났고 두 상품의 재고는 0이다 */
	private BaseOrder baseOrder() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(Member.createUser(
			"partial-race-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5)));
		Product apple = productPersistence.save(product(APPLE_UNIT_PRICE));
		Product pear = productPersistence.save(product(PEAR_UNIT_PRICE));

		Order order = Order.create(member.getId());
		order.addOrderItem(apple.getId(), APPLE_QUANTITY, APPLE_UNIT_PRICE);
		order.addOrderItem(pear.getId(), PEAR_QUANTITY, PEAR_UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(apple.getId(), 0));
		stockPersistence.save(Stock.create(pear.getId(), 0));

		int totalPrice = savedOrder.getTotalPrice();
		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-partial-" + suffix, "idem-partial-" + suffix, totalPrice);
		payment.markInProgress("pg-payment-partial-" + suffix, LocalDateTime.now());
		payment.succeed(totalPrice, "pg-tx-partial-" + suffix);
		Payment savedPayment = paymentPersistence.save(payment);

		return new BaseOrder(member.getId(), savedOrder.getId(), savedPayment.getId(),
			apple.getId(), pear.getId(),
			savedOrder.getOrderItems().get(0).getId(), savedOrder.getOrderItems().get(1).getId());
	}

	private Product product(int price) {
		return Product.create("상품-" + UUID.randomUUID().toString().substring(0, 6),
			price, null, null, ProductStatus.ON_SALE);
	}

	private record BaseOrder(
		Long memberId, Long orderId, Long paymentId,
		Long appleProductId, Long pearProductId, Long appleItemId, Long pearItemId) {
	}

	/** 겹쳐 들어간 요청들이 각각 어떻게 끝났나. 어느 쪽이 이겼는지가 아니라 성공한 조합이 무엇인지를 담는다 */
	private static class Race {
		private final Map<Integer, OrderCancelResult> succeeded = new ConcurrentHashMap<>();
		private final Map<Integer, RuntimeException> rejected = new ConcurrentHashMap<>();
		private final ConcurrentLinkedQueue<Exception> unexpected = new ConcurrentLinkedQueue<>();
	}
}
