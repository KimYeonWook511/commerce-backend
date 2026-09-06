package com.commerce.order.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.dto.ApprovalResult;
import com.commerce.payment.application.dto.ApprovalStatus;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.usecase.RequestApprovalUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
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
 * 승인 확정과 회원의 취소 요청이 같은 주문 행을 두고 겹칠 때를 실제 DB 위에서 확인한다. 두 흐름 모두
 * 주문 행을 잠근 뒤에야 자기 판정을 하므로, 어느 쪽이 먼저 잠그느냐로 결말이 갈린다.
 *
 * <p>승인 쪽 참여자는 반드시 {@link RequestApprovalUseCase}를 통째로 부른다. 주문 예외를 받아 반려
 * 사유 환불을 여는 자리가 그 흐름의 catch이지, 주문 행을 잠그는 확정 트랜잭션 자신이 아니기 때문이다 —
 * 그 트랜잭션만 직접 몰면 반려 환불이 원리적으로 만들어질 수 없어 회귀가 있어도 통과한다.
 *
 * <p>어느 쪽이 이기는지는 이 시험이 각 시험마다 강제한다. 실제 운영에서 어느 쪽이 먼저 잠그는지는
 * 타이밍에 달려 있어 단언하지 않는다.
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
class CancelOrderUseCaseSettlementConcurrencyTest {

	private static final int UNIT_PRICE = 10_000;
	private static final long WAIT_SECONDS = 10;

	/** 순서를 강제할 참여자를 이 이름으로 돌려, 대역이 그 참여자만 세워 두게 한다 */
	private static final String PAUSED_PARTICIPANT = "settlement-race-paused-participant";

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

	@Autowired
	private RequestApprovalUseCase requestApprovalUseCase;

	/** 주문 행을 잠그는 시점을 잡으려고 감싼다. 잠그는 값과 그 뒤 판정은 진짜 리포지토리와 진짜 도메인이 정한다 */
	@MockitoSpyBean
	private OrderRepository orderRepository;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

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

	@DisplayName("취소가 주문 행을 먼저 잠그면 거부되고, 뒤이은 승인 확정은 정상 결제완료로 끝나 반려 사유 환불이 생기지 않는다")
	@Test
	void cancelAndApprove_whenCancelLocksOrderFirst_rejectsCancelAndSettlesWithoutRejectionRefund()
		throws InterruptedException {
		Fixture fixture = readyPayment();
		givenApproveSucceeds();

		AtomicBoolean paused = new AtomicBoolean(true);
		CountDownLatch resume = new CountDownLatch(1);
		CountDownLatch orderLocked = pauseParticipantAfterOrderLocked(resume, paused);

		AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
		Thread cancelling = startPausedParticipant(cancelFailure, () ->
			cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(), "member-cancel-key", List.of()));
		assertThat(orderLocked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

		AtomicReference<ApprovalResult> approved = new AtomicReference<>();
		AtomicReference<Throwable> approveFailure = new AtomicReference<>();
		Thread approving = startThread("settlement-participant", approveFailure, () ->
			approved.set(requestApprovalUseCase.approve(fixture.memberId(), fixture.paymentKey(), "pg-payment-1")));
		resume.countDown();
		cancelling.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		approving.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		// 순서가 서지 않으면 아래 단언이 순차 실행도 통과시키므로 창이 실제로 열렸는지를 먼저 본다.
		assertThat(paused).isTrue();
		assertThat(cancelFailure.get()).isInstanceOf(OrderException.class);
		assertThat(((OrderException) cancelFailure.get()).getErrorCode())
			.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		assertThat(approveFailure.get()).isNull();
		assertThat(approved.get().status()).isEqualTo(ApprovalStatus.SUCCESS);
		assertThat(orderPersistence.getOrderStatusById(fixture.orderId())).isEqualTo(OrderStatus.PAID);
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("승인이 주문 행을 먼저 잠그면 결제완료로 확정되고, 뒤이은 취소는 결제완료 취소 경로로 정상 사유 환불을 연다")
	@Test
	void cancelAndApprove_whenApprovalLocksOrderFirst_settlesThenCancelsThroughPaidOrderPath()
		throws InterruptedException {
		Fixture fixture = readyPayment();
		givenApproveSucceeds();
		givenRefundSucceeds();

		AtomicBoolean paused = new AtomicBoolean(true);
		CountDownLatch resume = new CountDownLatch(1);
		CountDownLatch orderLocked = pauseParticipantAfterOrderLocked(resume, paused);

		AtomicReference<ApprovalResult> approved = new AtomicReference<>();
		AtomicReference<Throwable> approveFailure = new AtomicReference<>();
		Thread approving = startPausedParticipant(approveFailure, () ->
			approved.set(requestApprovalUseCase.approve(fixture.memberId(), fixture.paymentKey(), "pg-payment-1")));
		assertThat(orderLocked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

		AtomicReference<OrderCancelResult> canceled = new AtomicReference<>();
		AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
		Thread cancelling = startThread("cancel-participant", cancelFailure, () ->
			canceled.set(cancelOrderUseCase.cancel(
				fixture.memberId(), fixture.orderId(), "member-cancel-key", List.of())));
		resume.countDown();
		approving.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
		cancelling.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		assertThat(paused).isTrue();
		assertThat(approveFailure.get()).isNull();
		assertThat(approved.get().status()).isEqualTo(ApprovalStatus.SUCCESS);
		assertThat(cancelFailure.get()).isNull();
		assertThat(canceled.get().getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(orderPersistence.getOrderStatusById(fixture.orderId())).isEqualTo(OrderStatus.CANCELED);

		List<Refund> refunds = refundPersistence.findAll();
		assertThat(refunds).hasSize(1);
		assertThat(refunds.get(0).getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
	}

	// ── 순서 통제 ──

	/**
	 * 세워 둔 참여자가 주문 행을 잠근 자리에 세운다. 상대가 자기 트랜잭션을 마칠 때까지 그 잠금을 쥔 채
	 * 기다리게 되어, 상대의 같은 행 잠금 시도가 진짜 DB 락 대기로 막힌다.
	 *
	 * @param resume 세워 둔 참여자를 풀어 주는 신호
	 * @param paused 창이 실제로 열렸는지. 세워 둔 참여자가 신호를 못 받고 그냥 지나가면 경합이 서지 않는데,
	 *               그때도 단언이 순차 실행을 통과시키므로 부르는 쪽이 이 값을 함께 확인한다
	 * @return 세워 둔 참여자가 주문 행을 잠그고 멈춰 섰음을 알리는 신호
	 */
	private CountDownLatch pauseParticipantAfterOrderLocked(CountDownLatch resume, AtomicBoolean paused) {
		CountDownLatch reachedPause = new CountDownLatch(1);
		willAnswer(invocation -> {
			Object loaded = invocation.callRealMethod();
			if (isPausedParticipant()) {
				reachedPause.countDown();
				paused.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return loaded;
		}).given(orderRepository).findByIdForUpdate(any());
		willAnswer(invocation -> {
			Object loaded = invocation.callRealMethod();
			if (isPausedParticipant()) {
				reachedPause.countDown();
				paused.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return loaded;
		}).given(orderRepository).findByIdAndMemberIdForUpdate(any(), any());
		return reachedPause;
	}

	private boolean isPausedParticipant() {
		return PAUSED_PARTICIPANT.equals(Thread.currentThread().getName());
	}

	private Thread startPausedParticipant(AtomicReference<Throwable> thrown, Runnable participant) {
		return startThread(PAUSED_PARTICIPANT, thrown, participant);
	}

	private Thread startThread(String name, AtomicReference<Throwable> thrown, Runnable participant) {
		Thread thread = new Thread(() -> {
			try {
				participant.run();
			} catch (Throwable ex) {
				thrown.set(ex);
			}
		}, name);
		thread.start();
		return thread;
	}

	// ── 헬퍼 ──

	private void givenApproveSucceeds() {
		given(paymentGatewayPort.approve(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			return PgApproveResult.succeeded(
				payment.getPaymentKey(), String.valueOf(payment.getMemberId()), payment.getAmount(),
				"hist-1", "성공", new PgCallRecord(PgErrorType.NONE, "Success", 200, "{\"code\":\"Success\"}"));
		});
	}

	private void givenRefundSucceeds() {
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.succeeded("pg-cancel-tx-1", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}")));
	}

	private Fixture readyPayment() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(Member.createUser(
			"settlement-race-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5)));
		Product product = productPersistence.save(Product.create(
			"상품-" + UUID.randomUUID().toString().substring(0, 6), UNIT_PRICE, null, null, ProductStatus.ON_SALE));

		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-settlement-" + suffix, "idem-settlement-" + suffix, savedOrder.getTotalPrice());
		Payment savedPayment = paymentPersistence.save(payment);

		return new Fixture(member.getId(), savedOrder.getId(), savedPayment.getPaymentKey());
	}

	private record Fixture(Long memberId, Long orderId, String paymentKey) {
	}
}
