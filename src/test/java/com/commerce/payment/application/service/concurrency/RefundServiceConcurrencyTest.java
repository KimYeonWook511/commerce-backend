package com.commerce.payment.application.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 환불 확정이 겹칠 때 결제의 실제로 돌아간 금액이 어긋나지 않는지 확인한다. 되돌리는 것이 환불 행과
 * 결제 행의 낙관 락이고 그 둘은 한 트랜잭션이 실제로 커밋될 때만 걸리므로, 실제 DB 위에서만 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고 지켜야 하는 것은 "성공한 환불 금액의
 * 합과 결제가 든 금액이 같다"는 불변식이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class RefundServiceConcurrencyTest {

	private static final int APPROVED_AMOUNT = 10_000;
	private static final int THREADS = 2;
	private static final String PG_TRANSACTION_ID = "pg-cancel-tx";

	@Autowired
	private RefundService refundService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

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
		persistenceCleanup.deleteAllInBatch(refundPersistence, paymentPersistence);
	}

	@DisplayName("같은 환불을 동시에 확정하면 실제로 돌아간 금액이 한 번만 오른다")
	@Test
	void complete_whenSameRefundIsSettledConcurrently_raisesSucceededAmountOnce() throws InterruptedException {
		Payment payment = savePayment();
		Refund refund = pendingRefund(payment, 3_000, "same");

		runConcurrently(index -> refundService.complete(refund.getId(), PG_TRANSACTION_ID));

		// 상태 가드는 둘 다 통과시킨다 — 각자 자기 트랜잭션이 읽은 값을 보기 때문이다. 진 쪽을 되돌리는
		// 것은 환불 행의 낙관 락이고, 환불을 먼저 저장하므로 결제 행에 닿기 전에 거기서 걸린다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(reloadPayment(payment).getRefundSucceededAmount()).isEqualTo(refund.getAmount());
	}

	@DisplayName("같은 결제의 서로 다른 환불 둘을 동시에 확정해도 두 금액이 어긋나지 않는다")
	@Test
	void complete_whenTwoRefundsOfOnePaymentAreSettledConcurrently_keepsAmountsConsistent()
		throws InterruptedException {
		Payment payment = savePayment();
		List<Refund> refunds = twoPendingRefunds(payment, 3_000, 2_000);

		runConcurrently(index -> refundService.complete(refunds.get(index).getId(), PG_TRANSACTION_ID));

		// 낙관 락에서 한쪽이 지면 그 환불만 미결로 남고 대사가 다시 집는다. 실제로 돌아간 금액이 둘의
		// 합인데 성공한 환불이 하나뿐인 조합은 나오지 않는다.
		int settledSum = refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getStatus() == RefundStatus.SUCCEEDED)
			.mapToInt(Refund::getAmount)
			.sum();
		assertThat(reloadPayment(payment).getRefundSucceededAmount()).isEqualTo(settledSum);
	}

	// ── 헬퍼 ──

	/** 모든 thread 를 같은 순간에 풀어 준다. 어느 쪽이 밀렸는지는 타이밍에 달려 있어 세지 않는다 */
	private void runConcurrently(IntConsumer request) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);

		for (int i = 0; i < THREADS; i++) {
			int index = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					request.accept(index);
				} catch (Exception ignored) {
					// 진 쪽이 무엇을 받는지는 이 테스트가 보는 것이 아니다. 저장된 상태만 단언한다.
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await();
		executor.shutdown();
	}

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			300L + suffix, 400L + suffix, PaymentPg.NAVERPAY, "PK-C-" + suffix, "idem-c-" + suffix, APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-c-" + suffix, LocalDateTime.now());
		payment.succeed(APPROVED_AMOUNT, "pg-tx-c-" + suffix);
		return paymentPersistence.save(payment);
	}

	/** 결제사를 부르고 응답을 기다리는 환불. 만드는 관문이 결제 안에 있어 결제도 함께 저장한다 */
	private Refund pendingRefund(Payment payment, int amount, String key) {
		Refund refund = payment.openRefund(
			Optional.empty(), amount, RefundReason.ORDER_CANCELED, "IDEM-C-" + key + "-" + uniqueSuffix);
		refund.markInProgress(LocalDateTime.now());
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	/**
	 * 한 결제에 결제사 응답을 기다리는 환불 둘. 결제는 한 번만 저장한다 — 두 번 나눠 저장하면 앞 저장으로
	 * 낡아진 버전이 뒤 저장에 부딪힌다.
	 */
	private List<Refund> twoPendingRefunds(Payment payment, int firstAmount, int secondAmount) {
		Refund first = payment.openRefund(
			Optional.empty(), firstAmount, RefundReason.ORDER_CANCELED, "IDEM-C-first-" + uniqueSuffix);
		Refund second = payment.openRefund(
			Optional.empty(), secondAmount, RefundReason.ORDER_CANCELED, "IDEM-C-second-" + uniqueSuffix);
		first.markInProgress(LocalDateTime.now());
		second.markInProgress(LocalDateTime.now());

		Refund savedFirst = refundPersistence.save(first);
		Refund savedSecond = refundPersistence.save(second);
		paymentPersistence.save(payment);
		return List.of(savedFirst, savedSecond);
	}

	private Refund reload(Refund refund) {
		List<Refund> refunds = refundPersistence.findAll();
		return refunds.stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}

	private Payment reloadPayment(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}
}
