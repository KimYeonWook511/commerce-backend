package com.commerce.order.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
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
 * 주문 취소에 요청이 겹칠 때의 불변식을 확인한다. 선점 층·주문 행 락·환불 요청 멱등키 유일 제약이 이
 * 방어의 전부라 실제 Redis와 실제 DB 위에서만 거동이 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고, 지켜야 하는 것은 "환불 사건이
 * 하나", "결제사에 취소가 한 번만 나간다"라는 불변식이다.
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
class CancelOrderUseCaseConcurrencyTest {

	private static final int UNIT_PRICE = 10_000;
	private static final int THREADS = 2;

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

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

	private final AtomicInteger refundCalls = new AtomicInteger();

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@BeforeEach
	void stubGateway() {
		refundCalls.set(0);
		given(paymentGatewayPort.refund(any(), any(), any())).willAnswer(invocation -> {
			refundCalls.incrementAndGet();
			return PgRefundResult.succeeded("pg-cancel-tx", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}"));
		});
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, refundPersistence, paymentPersistence,
			memberPersistence, productPersistence, orderPersistence, stockPersistence
		);
	}

	@DisplayName("같은 환불 요청 멱등키로 주문 취소가 동시에 와도 환불 사건이 하나만 생긴다")
	@Test
	void cancel_whenSameIdempotencyKeyRaces_createsSingleRefund() throws InterruptedException {
		Fixture fixture = paidOrder();

		Outcome outcome = runConcurrently(index ->
			cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(), "same-cancel-key"));

		assertThat(outcome.successes).isNotEmpty();
		assertThat(outcome.unexpected).isEmpty();
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(refundCalls.get()).isEqualTo(1);
		assertThat(orderPersistence.getOrderStatusById(fixture.orderId())).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("주문 취소가 서로 다른 멱등키로 동시에 두 번 와도 환불이 하나이고 결제사에 한 번만 나간다")
	@Test
	void cancel_whenTwoRequestsRaceOnSameOrder_createsSingleRefund() throws InterruptedException {
		Fixture fixture = paidOrder();

		Outcome outcome = runConcurrently(index ->
			cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(), "cancel-key-" + index));

		assertThat(outcome.successes).isNotEmpty();
		assertThat(outcome.unexpected).isEmpty();
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(refundCalls.get()).isEqualTo(1);

		// 환불 총액이 승인 금액을 넘지 않는다.
		Payment payment = paymentPersistence.findById(fixture.paymentId()).orElseThrow();
		assertThat(payment.getTotalRefundedAmount()).isEqualTo(UNIT_PRICE);
	}

	// ── 헬퍼 ──

	private Outcome runConcurrently(IntFunction<OrderCancelResult> sender) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		Outcome outcome = new Outcome();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			int index = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					outcome.successes.add(sender.apply(index));
				} catch (RuntimeException ex) {
					outcome.rejections.add(ex);
				} catch (Exception ex) {
					outcome.unexpected.add(ex);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
		return outcome;
	}

	private Fixture paidOrder() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(Member.createUser(
			"cancel-race-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5)));
		Product product = productPersistence.save(Product.create(
			"상품-" + UUID.randomUUID().toString().substring(0, 6), UNIT_PRICE, null, null, ProductStatus.ON_SALE));

		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-race-" + suffix, "idem-race-" + suffix, UNIT_PRICE);
		payment.markInProgress("pg-payment-race-" + suffix, LocalDateTime.now());
		payment.succeed(UNIT_PRICE, "pg-tx-race-" + suffix);
		Payment savedPayment = paymentPersistence.save(payment);

		return new Fixture(member.getId(), savedOrder.getId(), savedPayment.getId());
	}

	private record Fixture(Long memberId, Long orderId, Long paymentId) {
	}

	private static class Outcome {
		private final List<OrderCancelResult> successes = new CopyOnWriteArrayList<>();
		private final ConcurrentLinkedQueue<RuntimeException> rejections = new ConcurrentLinkedQueue<>();
		private final ConcurrentLinkedQueue<Exception> unexpected = new ConcurrentLinkedQueue<>();
	}
}
