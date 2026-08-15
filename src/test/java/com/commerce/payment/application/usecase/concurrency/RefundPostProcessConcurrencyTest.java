package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.usecase.ExecuteRefundUseCase;
import com.commerce.payment.application.usecase.NotifyRefundUseCase;
import com.commerce.payment.application.usecase.ReconcileRefundUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 환불 후처리 주기가 겹쳐 돌 때의 불변식을 확인한다. 집는 자리의 낙관 락, 통지 시각을 남기는 순서,
 * 아직 응답을 기다리는 건을 대사가 집지 않게 하는 유예가 이 방어의 전부라 실제 DB 위에서만 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고, 지켜야 하는 것은 결과가
 * 인터리빙과 무관하게 같다는 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@TestPropertySource(properties = "payment.postprocess.notify.escalation=0s")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class RefundPostProcessConcurrencyTest {

	private static final int AMOUNT = 10_000;
	private static final int THREADS = 2;
	private static final String HISTORY_TRANSACTION_ID = "hist-cancel-1";

	@Autowired
	private ReconcileRefundUseCase reconcileRefundUseCase;

	@Autowired
	private NotifyRefundUseCase notifyRefundUseCase;

	@Autowired
	private ExecuteRefundUseCase executeRefundUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@MockitoBean
	private NotificationPort notificationPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	private final AtomicInteger notifyCount = new AtomicInteger();

	private final AtomicInteger refundCallCount = new AtomicInteger();

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		notifyCount.set(0);
		refundCallCount.set(0);
		persistenceCleanup.deleteAllInBatch(pgCallLogPersistence, refundPersistence, paymentPersistence);
	}

	@DisplayName("대사 둘이 같은 환불을 집어도 확정은 한 번만 반영된다")
	@Test
	void reconcile_whenTwoCyclesPickTheSameRefund_settlesItOnce() throws InterruptedException {
		Refund refund = unknownRefund(savePayment());
		given(paymentGatewayPort.readHistory(any(), any(), any())).willReturn(PgHistoryResult.succeeded(
			List.of(new PgHistoryEntry(PgHistoryEntryType.REFUND, true, AMOUNT, LocalDateTime.now(),
				null, refund.attemptKey(), HISTORY_TRANSACTION_ID)), "성공"));

		List<Exception> unexpected = runConcurrently(() -> reconcileRefundUseCase.reconcile());

		assertThat(unexpected).isEmpty();
		Refund settled = reload(refund);
		// 진 쪽이 물러나거나 이미 성공한 것을 그대로 두므로 상태가 두 번 바뀌지 않는다.
		assertThat(settled.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(settled.getPgTransactionId()).isEqualTo(HISTORY_TRANSACTION_ID);
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("통지가 겹쳐 돌면 두 번 나갈 수 있고, 보내지 못한 통지가 보낸 것으로 남지 않는다")
	@Test
	void notifyStalled_whenTwoCyclesOverlap_neverMarksAnUnsentNotification() throws InterruptedException {
		Refund refund = unknownRefund(savePayment());
		willAnswer(invocation -> {
			notifyCount.incrementAndGet();
			return null;
		}).given(notificationPort).notifyManualReviewRequired(any(), any(), any());

		List<Exception> unexpected = runConcurrently(() -> notifyRefundUseCase.notifyStalled());

		assertThat(unexpected).isEmpty();
		// 시각을 먼저 찍어 하나만 통과시키면 전송이 실패했을 때 다시 대상이 되지 못한다. 중복을 택한다.
		assertThat(notifyCount.get()).isBetween(1, THREADS);
		assertThat(reload(refund).getLastNotifyAt()).isNotNull();
	}

	@DisplayName("요청 흐름이 아직 결제사 응답을 기다리는 환불을 대사가 집지 않는다")
	@Test
	void reconcile_whileRequestFlowIsStillCallingTheGateway_doesNotPickIt() throws InterruptedException {
		Payment payment = savePayment();
		Refund refund = requestedRefund(payment);
		CountDownLatch reconcileFinished = new CountDownLatch(1);
		willAnswer(invocation -> {
			refundCallCount.incrementAndGet();
			// 요청 흐름이 그 호출을 붙들고 있는 구간을 그대로 재현한다.
			reconcileFinished.await(5, TimeUnit.SECONDS);
			return PgRefundResult.unanswered("응답 없음", callRecord());
		}).given(paymentGatewayPort).refund(any(), any(), any());

		CountDownLatch done = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		executor.submit(() -> {
			try {
				executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);
			} finally {
				done.countDown();
			}
		});
		executor.submit(() -> {
			try {
				reconcileRefundUseCase.reconcile();
			} finally {
				reconcileFinished.countDown();
				done.countDown();
			}
		});
		done.await();
		executor.shutdown();

		// 집으면 결과를 못 받은 건으로 보고 같은 환불을 둘이 동시에 결제사로 보낸다. 대사 유예가 그것을 막는다.
		assertThat(refundCallCount.get()).isEqualTo(1);
		then(paymentGatewayPort).should(never()).readHistory(any(), any(), any());
		assertThat(reload(refund).getReconcileCount()).isZero();
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

	private PgCallRecord callRecord() {
		return new PgCallRecord(PgErrorType.TIMEOUT, null, null, "{}");
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			100L + suffix, 200L + suffix, PaymentPg.NAVERPAY, "PK-" + suffix, "idem-" + suffix, AMOUNT);
		payment.markInProgress("pg-payment-" + suffix, LocalDateTime.now());
		payment.succeed(AMOUNT, "pg-tx-" + suffix);
		return paymentPersistence.save(payment);
	}

	/** 아직 한 번도 안 나간 환불. 요청 흐름이 이제 그것을 보낸다 */
	private Refund requestedRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-" + uniqueSuffix);
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	/** 답을 못 받아 결과를 모르는 환불. 이 상태에는 대사 유예가 없어 바로 대상이 된다 */
	private Refund unknownRefund(Payment payment) {
		Refund refund = requestedRefund(payment);
		refund.markInProgress(LocalDateTime.now().minusMinutes(5));
		refund.markUnknown();
		return refundPersistence.save(refund);
	}
}
