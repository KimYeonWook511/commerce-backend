package com.commerce.payment.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.dto.StartPaymentResult;
import com.commerce.payment.application.usecase.StartPaymentUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결제 시작에 요청이 겹칠 때의 불변식을 확인한다. 선점 층과 유일 제약이 이 방어의 전부라 실제 Redis와
 * 실제 DB 위에서만 거동이 재현된다.
 *
 * <p>어느 쪽이 이기는지는 단언하지 않는다. 승자는 타이밍에 달려 있고, 지켜야 하는 것은 "활성 결제가
 * 하나", "시도가 하나"라는 불변식이다.
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
	PaymentPersistenceTestSupport.class
})
class StartPaymentUseCaseConcurrencyTest {

	private static final int UNIT_PRICE = 5_000;
	private static final int THREADS = 2;

	@Autowired
	private StartPaymentUseCase startPaymentUseCase;

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

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("같은 주문에 결제 시작이 서로 다른 멱등키로 동시에 들어와도 활성 결제는 하나뿐이다")
	@Test
	void start_whenTwoRequestsRaceOnSameOrder_leavesOneActivePayment() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member, 1);

		Outcome outcome = runConcurrently(index ->
			startPaymentUseCase.start(command(member, order, "race-key-" + index)));

		// 슬롯 유일 제약이 진 쪽을 막고, 그 위반이 "같은 요청이 처리 중"으로 바뀌어 나간다.
		assertThat(outcome.rejectedWith(PaymentErrorCode.PAYMENT_REQUEST_IN_PROGRESS) + outcome.successes.size())
			.isEqualTo(THREADS);
		assertThat(outcome.successes).isNotEmpty();
		assertThat(outcome.unexpected).isEmpty();

		assertThat(paymentPersistence.findAll().stream()
			.filter(payment -> payment.getActiveOrderKey() != null)
			.count()).isEqualTo(1);
	}

	@DisplayName("같은 멱등키로 결제 시작이 동시에 두 번 와도 결제 시도가 하나만 생긴다")
	@Test
	void start_whenSameIdempotencyKeyRaces_createsSingleAttempt() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member, 1);

		Outcome outcome = runConcurrently(index ->
			startPaymentUseCase.start(command(member, order, "same-key")));

		assertThat(outcome.rejectedWith(PaymentErrorCode.PAYMENT_REQUEST_IN_PROGRESS) + outcome.successes.size())
			.isEqualTo(THREADS);
		assertThat(outcome.successes).isNotEmpty();
		assertThat(outcome.unexpected).isEmpty();
		// 결제창을 여는 값이 두 번 발급되지 않는다.
		assertThat(outcome.successes.stream().map(StartPaymentResult::merchantPayKey).distinct()).hasSize(1);
		assertThat(paymentPersistence.count()).isEqualTo(1);
	}

	@DisplayName("승인을 호출하고 응답을 기다리는 결제가 있으면 새 결제 요청이 겹쳐 와도 그 행이 자리를 지킨다")
	@Test
	void start_whenApprovalInFlight_keepsSlotAndRejectsNewAttempts() throws InterruptedException {
		Member member = saveMember();
		Order order = saveOrder(member, 1);
		Payment inFlight = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-in-flight", "idem-in-flight", order.getTotalPrice());
		inFlight.markInProgress("pg-payment-1", LocalDateTime.now());
		paymentPersistence.save(inFlight);

		Outcome outcome = runConcurrently(index ->
			startPaymentUseCase.start(command(member, order, "in-flight-key-" + index)));

		assertThat(outcome.successes).isEmpty();
		assertThat(outcome.rejectedWith(PaymentErrorCode.PAYMENT_RESULT_PENDING)).isEqualTo(THREADS);
		assertThat(outcome.unexpected).isEmpty();

		// 진행 중인 승인이 슬롯을 반납하면 그 승인이 성공했을 때 한 주문에 승인이 둘 성립한다.
		Payment kept = paymentPersistence.findById(inFlight.getId()).orElseThrow();
		assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(kept.getActiveOrderKey()).isEqualTo(order.getId());
		assertThat(paymentPersistence.count()).isEqualTo(1);
	}

	private Outcome runConcurrently(RequestSender sender) throws InterruptedException {
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
					outcome.successes.add(sender.send(index));
				} catch (PaymentException ex) {
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

	private StartPaymentCommand command(Member member, Order order, String idempotencyKey) {
		return new StartPaymentCommand(member.getId(), order.getId(), PaymentPg.NAVERPAY, idempotencyKey);
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("start-payment-race-" + (++uniqueSuffix) + "@example.com", "password123", "race-user"));
	}

	private Order saveOrder(Member member, int quantity) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), quantity, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}

	@FunctionalInterface
	private interface RequestSender {
		StartPaymentResult send(int index);
	}

	private static class Outcome {
		private final List<StartPaymentResult> successes = new CopyOnWriteArrayList<>();
		private final ConcurrentLinkedQueue<PaymentException> rejections = new ConcurrentLinkedQueue<>();
		private final ConcurrentLinkedQueue<Exception> unexpected = new ConcurrentLinkedQueue<>();

		private long rejectedWith(PaymentErrorCode errorCode) {
			return rejections.stream().filter(ex -> errorCode.equals(ex.getErrorCode())).count();
		}
	}
}
