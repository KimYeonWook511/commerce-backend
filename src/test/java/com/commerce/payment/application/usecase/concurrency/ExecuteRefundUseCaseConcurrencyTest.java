package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.usecase.ExecuteRefundUseCase;
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
 * 같은 환불을 둘이 동시에 보내려 할 때 부르기 직전 전이가 하나만 통과시키는지 확인한다. 그 커밋에 지면
 * 부르지 않는 것이 겹친 호출을 막는 유일한 장치이고, 낙관 락은 실제 DB 위에서만 걸린다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class ExecuteRefundUseCaseConcurrencyTest {

	private static final int AMOUNT = 10_000;
	private static final int THREADS = 2;

	@Autowired
	private ExecuteRefundUseCase executeRefundUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

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
		persistenceCleanup.deleteAllInBatch(pgCallLogPersistence, refundPersistence, paymentPersistence);
	}

	@DisplayName("같은 환불을 둘이 동시에 보내려 하면 결제사에 한 번만 나간다")
	@Test
	void send_whenTwoSendersRace_callsGatewayOnce() throws InterruptedException {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);

		Outcome outcome = runConcurrently(payment, refund);

		assertThat(outcome.unexpected).isEmpty();
		// 진 쪽은 부르지 않는다. 그래도 회원에게는 "처리 중"이라 응답이 실패로 나가지 않는다.
		assertThat(refundCalls.get()).isEqualTo(1);
		assertThat(outcome.statuses).hasSize(THREADS);
		assertThat(outcome.statuses).contains(RefundStatus.IN_PROGRESS);
		// 한 행이 한 호출을 나타내므로 이 환불의 기록도 하나뿐이다.
		assertThat(pgCallLogPersistence.findAll().stream()
			.filter(log -> refund.getId().equals(log.getRefundId()))
			.count()).isEqualTo(1);
	}

	// ── 헬퍼 ──

	private Outcome runConcurrently(Payment payment, Refund refund) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		Outcome outcome = new Outcome();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					// 각자 자기 손에 든 환불로 보낸다 — 요청 흐름과 발송 배치가 겹친 모양이다.
					outcome.statuses.add(executeRefundUseCase.send(payment, reload(refund), PgCallSource.BATCH));
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

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			500L + suffix, 600L + suffix, PaymentPg.NAVERPAY, "PK-R-" + suffix, "idem-r-" + suffix, AMOUNT);
		payment.markInProgress("pg-payment-r-" + suffix, LocalDateTime.now());
		payment.succeed(AMOUNT, "pg-tx-r-" + suffix);
		return paymentPersistence.save(payment);
	}

	private Refund saveRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-R-" + uniqueSuffix);
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}

	private static class Outcome {
		private final List<RefundStatus> statuses = new CopyOnWriteArrayList<>();
		private final ConcurrentLinkedQueue<Exception> unexpected = new ConcurrentLinkedQueue<>();
	}
}
