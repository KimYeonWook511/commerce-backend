package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.usecase.NotifyPaymentUseCase;
import com.commerce.payment.application.usecase.ReconcilePaymentUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 후처리 주기 둘이 같은 결제를 동시에 집을 때의 불변식을 확인한다. 집는 자리의 낙관 락과 통지 시각을
 * 남기는 순서가 이 방어의 전부라 실제 DB 위에서만 거동이 재현된다.
 *
 * <p>대사와 통지를 한 클래스에 둔다. 둘 다 같은 진입점이 깨우는 주기이고 겹쳤을 때 무엇을 지켜야 하는지가
 * 한 쌍으로 읽혀야 한다 — 대사는 두 번 반영되지 않아야 하고, 통지는 두 번 나가더라도 유실되지 않아야 한다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@TestPropertySource(properties = "payment.postprocess.notify.escalation=0s")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class PaymentPostProcessConcurrencyTest {

	private static final int UNIT_PRICE = 5_000;
	private static final int THREADS = 2;
	private static final String PG_PAYMENT_ID = "pg-payment-1";
	private static final String PG_TRANSACTION_ID = "hist-1";

	@Autowired
	private ReconcilePaymentUseCase reconcilePaymentUseCase;

	@Autowired
	private NotifyPaymentUseCase notifyPaymentUseCase;

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
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	private final AtomicInteger notifyCount = new AtomicInteger();

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		notifyCount.set(0);
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("대사 둘이 같은 결제를 집어도 확정은 한 번만 반영된다")
	@Test
	void reconcile_whenTwoCyclesPickTheSamePayment_settlesItOnce() throws InterruptedException {
		Payment payment = unknownPayment();
		Order order = orderPersistence.findById(payment.getOrderId()).orElseThrow();
		given(paymentGatewayPort.readHistory(any(), any())).willReturn(PgHistoryResult.succeeded(
			List.of(new PgHistoryEntry(PgHistoryEntryType.APPROVAL, true, payment.getAmount(),
				LocalDateTime.now(), payment.getPaymentKey(), null, PG_TRANSACTION_ID)), "성공"));

		List<Exception> unexpected = runConcurrently(() -> reconcilePaymentUseCase.reconcile());

		assertThat(unexpected).isEmpty();
		Payment settled = paymentPersistence.findById(payment.getId()).orElseThrow();
		// 진 쪽이 물러나거나 이미 성공한 것을 그대로 두므로 상태가 두 번 바뀌지 않는다.
		assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(settled.getCloseCode()).isNull();
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("통지가 겹쳐 돌면 두 번 나갈 수 있고, 보내지 못한 통지가 보낸 것으로 남지 않는다")
	@Test
	void notifyStalled_whenTwoCyclesOverlap_neverMarksAnUnsentNotification() throws InterruptedException {
		Payment payment = unknownPayment();
		willAnswer(invocation -> {
			notifyCount.incrementAndGet();
			return null;
		}).given(notificationPort).notifyManualReviewRequired(any(), any(), any());

		List<Exception> unexpected = runConcurrently(() -> notifyPaymentUseCase.notifyStalled());

		assertThat(unexpected).isEmpty();
		// 시각을 먼저 찍어 하나만 통과시키면 전송이 실패했을 때 다시 대상이 되지 못한다. 중복을 택한다.
		assertThat(notifyCount.get()).isBetween(1, THREADS);
		assertThat(paymentPersistence.findById(payment.getId()).orElseThrow().getLastNotifyAt()).isNotNull();
	}

	private List<Exception> runConcurrently(Runnable action) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		List<Exception> unexpected = new CopyOnWriteArrayList<>();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					action.run();
				} catch (Exception ex) {
					unexpected.add(ex);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
		return unexpected;
	}

	private Payment unknownPayment() {
		Member member = memberPersistence.save(
			Member.createUser("postprocess-" + (++uniqueSuffix) + "@example.com", "password123", "batch-user"));
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);

		Payment payment = Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + uniqueSuffix, "idem-" + uniqueSuffix, saved.getTotalPrice());
		payment.markInProgress(PG_PAYMENT_ID, LocalDateTime.now().minusMinutes(5));
		payment.markUnknown();
		return paymentPersistence.save(payment);
	}
}
