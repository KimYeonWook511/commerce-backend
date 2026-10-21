package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.common.exception.CustomException;
import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.service.ExpireOrderService;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.outbox.infrastructure.persistence.support.OutboxPersistenceTestSupport;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.usecase.ConfirmApprovalUseCase;
import com.commerce.payment.application.usecase.RequestApprovalUseCase;
import com.commerce.payment.application.usecase.StartPaymentUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 승인 요청이 다른 흐름과 겹칠 때의 불변식을 확인한다. 부르기 직전 전이의 커밋과 확정 트랜잭션의 주문
 * 행 잠금이 이 방어의 전부라 실제 DB 위에서만 거동이 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고, 지켜야 하는 것은 "승인 호출이
 * 하나", "성공한 결제가 하나", "반려가 일어나지 않는다"는 불변식이다.
 *
 * <p>승인 요청이 결제를 읽은 뒤 주문 만료가 먼저 커밋되는 경합만은 그 순서를 이 시험이 잡는다. 승인 요청을
 * 읽은 자리에 세워 두고 그 사이 만료를 커밋시킬 뿐, 결과는 진짜 저장소의 상태 전이가 정한다.
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
	OutboxPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class RequestApprovalUseCaseConcurrencyTest {

	private static final int UNIT_PRICE = 5_000;
	private static final int THREADS = 2;
	private static final String PG_PAYMENT_ID = "pg-payment-1";
	private static final long WAIT_SECONDS = 10;

	@Autowired
	private RequestApprovalUseCase requestApprovalUseCase;

	@Autowired
	private StartPaymentUseCase startPaymentUseCase;

	@Autowired
	private ConfirmApprovalUseCase confirmApprovalUseCase;

	@Autowired
	private ExpireOrderService expireOrderService;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@MockitoBean
	private NotificationPort notificationPort;

	/** 승인 요청이 결제를 읽는 시점을 잡으려고 감싼다 */
	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private OutboxPersistenceTestSupport outboxPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	private final AtomicInteger approveCallCount = new AtomicInteger();

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		approveCallCount.set(0);
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, paymentPersistence, memberPersistence, productPersistence, orderPersistence,
			outboxPersistence
		);
	}

	@DisplayName("같은 결제에 승인 요청이 겹쳐 와도 결제사에는 승인이 한 번만 나간다")
	@Test
	void approve_whenTwoRequestsRaceOnSamePayment_callsGatewayOnce() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = savePayment(order, member);
		givenApproveSucceeded(payment.getPaymentKey(), order.getTotalPrice());

		Outcome outcome = runConcurrently(() ->
			requestApprovalUseCase.approve(member.getId(), payment.getPaymentKey(), PG_PAYMENT_ID));

		assertThat(outcome.unexpected).isEmpty();
		// 부르기 직전 전이에 진 쪽은 부르지 않는다. 그러지 않으면 같은 결제에 승인이 두 번 나간다.
		assertThat(approveCallCount.get()).isEqualTo(1);
		assertThat(pgCallLogPersistence.findAll()).hasSize(1);

		Payment settled = paymentPersistence.findById(payment.getId()).orElseThrow();
		assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("인증 콜백과 새 결제 생성이 겹쳐도 한 주문에 승인 호출은 하나만 나간다")
	@Test
	void approve_whenRacingWithNewPaymentStart_leavesSingleApprovalCall() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = savePayment(order, member);
		givenApproveSucceeded(payment.getPaymentKey(), order.getTotalPrice());

		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		Outcome outcome = new Outcome();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		executor.submit(task(ready, start, done, outcome,
			() -> requestApprovalUseCase.approve(member.getId(), payment.getPaymentKey(), PG_PAYMENT_ID)));
		executor.submit(task(ready, start, done, outcome,
			() -> startPaymentUseCase.start(new StartPaymentCommand(
				member.getId(), order.getId(), PaymentPg.NAVERPAY, "race-key"))));

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();

		assertThat(outcome.unexpected).isEmpty();
		assertThat(approveCallCount.get()).isLessThanOrEqualTo(1);

		Payment old = paymentPersistence.findById(payment.getId()).orElseThrow();
		if (approveCallCount.get() == 0) {
			// 새 결제 쪽이 이기면 옛 결제는 자리를 내주고 승인을 호출하지 않는다.
			assertThat(old.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		} else {
			// 인증 쪽이 이기면 그 결제가 승인을 호출하고 슬롯을 놓지 않는다.
			assertThat(old.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		}

		// 한 주문에 살아 있는 결제는 언제나 하나다.
		assertThat(paymentPersistence.findAll().stream()
			.filter(candidate -> candidate.getActiveOrderKey() != null)
			.count()).isEqualTo(1);
	}

	@DisplayName("같은 결제에 승인 확정이 겹쳐도 반려가 일어나지 않고 앞선 결과가 그대로 남는다")
	@Test
	void confirm_whenTwoConfirmsRaceOnSamePayment_doesNotReject() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = savePayment(order, member);
		payment.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
		Payment inProgress = paymentPersistence.save(payment);

		Outcome outcome = runConcurrently(() ->
			confirmApprovalUseCase.confirm(inProgress, order.getTotalPrice(), "hist-1"));

		assertThat(outcome.unexpected).isEmpty();

		Payment settled = paymentPersistence.findById(payment.getId()).orElseThrow();
		assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(settled.getCloseCode()).isNull();
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("승인 요청이 결제를 읽은 뒤 주문 만료가 그 결제를 종결해 먼저 커밋하면 결제사를 부르지 않고 결과 확인 중으로 답한다")
	@Test
	void approve_whenOrderExpiryCommitsAfterPaymentRead_doesNotCallGateway() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = savePayment(order, member);
		givenApproveSucceeded(payment.getPaymentKey(), order.getTotalPrice());

		Pause pause = new Pause();
		willAnswer(pause::callRealThenWait).given(paymentRepository).findByPaymentKeyAndMemberId(any(), any());
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread approval = new Thread(() -> {
			try {
				requestApprovalUseCase.approve(member.getId(), payment.getPaymentKey(), PG_PAYMENT_ID);
			} catch (Throwable ex) {
				thrown.set(ex);
			}
		});
		approval.start();

		assertThat(pause.reached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		expireOrderService.expireOrder(order.getId(), LocalDateTime.now());
		pause.resume.countDown();
		approval.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

		// 읽은 시점에는 승인 전이었으므로 구분된 실패가 아니라 결과 확인 중으로 접힌다.
		assertThat(pause.resumedBySignal).isTrue();
		assertThat(thrown.get())
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());
		assertThat(pgCallLogPersistence.findAll()).isEmpty();
		Payment closed = paymentPersistence.findById(payment.getId()).orElseThrow();
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.ORDER_EXPIRED);
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);

		// 다시 시도하면 읽는 시점에 종결이 보여 재주문 안내를 받는다.
		assertThatThrownBy(() -> requestApprovalUseCase.approve(member.getId(), payment.getPaymentKey(), PG_PAYMENT_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_ORDER_EXPIRED.getMessage());
		then(paymentGatewayPort).should(never()).approve(any(Payment.class));
	}

	private Outcome runConcurrently(Runnable action) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		Outcome outcome = new Outcome();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			executor.submit(task(ready, start, done, outcome, action));
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
		return outcome;
	}

	private Runnable task(
		CountDownLatch ready,
		CountDownLatch start,
		CountDownLatch done,
		Outcome outcome,
		Runnable action
	) {
		return () -> {
			ready.countDown();
			try {
				start.await();
				action.run();
			} catch (CustomException ex) {
				outcome.rejections.add(ex);
			} catch (Exception ex) {
				outcome.unexpected.add(ex);
			} finally {
				done.countDown();
			}
		};
	}

	private void givenApproveSucceeded(String paymentKey, int approvedAmount) {
		given(paymentGatewayPort.approve(any(Payment.class))).willAnswer(invocation -> {
			approveCallCount.incrementAndGet();
			Payment payment = invocation.getArgument(0);
			return PgApproveResult.succeeded(
				paymentKey, String.valueOf(payment.getMemberId()), approvedAmount, "hist-1", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{\"code\":\"Success\"}"));
		});
	}

	private Payment savePayment(Order order, Member member) {
		return paymentPersistence.save(Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + (++uniqueSuffix), "idem-" + uniqueSuffix, order.getTotalPrice()));
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("approve-race-" + (++uniqueSuffix) + "@example.com", "password123", "race-user"));
	}

	private Order saveOrder(Member member) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}

	/** 승인 요청이 결제를 읽은 자리에 세운다. 창이 실제로 열렸는지도 남긴다 */
	private static final class Pause {

		private final CountDownLatch reached = new CountDownLatch(1);
		private final CountDownLatch resume = new CountDownLatch(1);
		private final AtomicBoolean resumedBySignal = new AtomicBoolean(false);

		private Object callRealThenWait(InvocationOnMock invocation) throws Throwable {
			Object result = invocation.callRealMethod();
			if (reached.getCount() > 0) {
				reached.countDown();
				resumedBySignal.set(resume.await(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			return result;
		}
	}

	private static class Outcome {
		private final ConcurrentLinkedQueue<CustomException> rejections = new ConcurrentLinkedQueue<>();
		private final List<Exception> unexpected = new CopyOnWriteArrayList<>();
	}
}
