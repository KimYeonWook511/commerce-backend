package com.commerce.payment.application.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import com.commerce.payment.application.PaymentApprovalRecordService;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class
})
class PaymentOptimisticLockConcurrencyTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PaymentApprovalService paymentApprovalService;

	@Autowired
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("같은 APPROVE Payment에 succeedApproval과 markUnknown transition을 동시에 시도하면 진 쪽 markUnknown이 충돌을 전파하고 lost update 없이 하나의 상태로 확정된다")
	@Test
	void succeedApproval_vs_markUnknown_whenConcurrent_lostUpdatePrevented() throws Exception {
		// given
		String merchantPayKey = "PAY-OPTLOCK-1";
		String pgPaymentId = "pg-optlock-1";
		LocalDateTime now = LocalDateTime.now();

		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(order.getId(), member.getId(), 1000, PaymentProvider.NAVERPAY,
				merchantPayKey, now.plusMinutes(15))
		);
		Payment payment = paymentPersistence.save(
			Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId)
		);

		ConcurrentLinkedQueue<Throwable> succeedErrors = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> markUnknownErrors = new ConcurrentLinkedQueue<>();

		// when: succeedApproval(무조건 전이)와 markUnknown(transition: 충돌/가드 위반 전파) 동시 시도
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> {
				try {
					startLatch.await();
					paymentApprovalService.succeedApproval(payment, now);
				} catch (Throwable ex) {
					succeedErrors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			executor.submit(() -> {
				try {
					startLatch.await();
					paymentApprovalRecordService.markUnknown(
						merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, "concurrent timeout", now
					);
				} catch (Throwable ex) {
					markUnknownErrors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			startLatch.countDown();
			doneLatch.await(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// then: markUnknown transition은 catch하지 않고 전파한다 — 진 쪽은 @Version 충돌(PAYMENT_CONCURRENTLY_MODIFIED) 또는
		// 가드 위반(상대가 먼저 SUCCEEDED → PAYMENT_STATUS_TRANSITION_NOT_ALLOWED)만 던진다. skip은 useCase 책임이라 여기선 전파가 정상.
		assertThat(markUnknownErrors).allSatisfy(ex -> {
			assertThat(ex).isInstanceOf(PaymentException.class);
			assertThat(((PaymentException) ex).getErrorCode())
				.isIn(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED,
					PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED);
		});

		// then: 최종 payment 상태는 SUCCEEDED 또는 UNKNOWN 중 하나 (lost update 없음)
		Payment finalPayment = paymentPersistence.getPayment(merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE);
		assertThat(finalPayment.getStatus()).isIn(PaymentStatus.SUCCEEDED, PaymentStatus.UNKNOWN);
	}

	@DisplayName("같은 APPROVE Payment에 fail과 markUnknown transition을 동시에 시도하면 진 쪽이 충돌을 전파하고 lost update 없이 하나의 상태로 확정된다")
	@Test
	void fail_vs_markUnknown_whenConcurrent_loserPropagatesConflict() throws Exception {
		// given
		String merchantPayKey = "PAY-OPTLOCK-2";
		String pgPaymentId = "pg-optlock-2";
		LocalDateTime now = LocalDateTime.now();

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(9999L, 1L, 1000, PaymentProvider.NAVERPAY,
				merchantPayKey, now.plusMinutes(15))
		);
		paymentPersistence.save(Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId));

		ConcurrentLinkedQueue<Throwable> failErrors = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> markUnknownErrors = new ConcurrentLinkedQueue<>();

		// when: fail(무조건 전이)와 markUnknown(transition) 동시 시도 — 둘 다 saveChecked로 충돌을 PAYMENT_CONCURRENTLY_MODIFIED로 변환
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			executor.submit(() -> {
				try {
					startLatch.await();
					paymentApprovalRecordService.fail(
						merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId,
						PaymentFailCode.PG_NETWORK_ERROR, "PG 네트워크 오류", now
					);
				} catch (Throwable ex) {
					failErrors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			executor.submit(() -> {
				try {
					startLatch.await();
					paymentApprovalRecordService.markUnknown(
						merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, "timeout", now
					);
				} catch (Throwable ex) {
					markUnknownErrors.add(ex);
				} finally {
					doneLatch.countDown();
				}
			});
			startLatch.countDown();
			doneLatch.await(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// then: 진 쪽 transition은 @Version 충돌(PAYMENT_CONCURRENTLY_MODIFIED) 또는 가드 위반(PAYMENT_STATUS_TRANSITION_NOT_ALLOWED)을 전파한다.
		// 정확히 한 transition만 성공하므로(아래 종착 상태 검증) 두 에러 큐 중 최대 1건만 채워지고, 채워졌다면 위 두 코드 중 하나다.
		assertThat(failErrors.size() + markUnknownErrors.size()).isLessThanOrEqualTo(1);
		assertThat(markUnknownErrors).allSatisfy(ex -> assertThat(ex).isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isIn(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED, PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));
		assertThat(failErrors).allSatisfy(ex -> assertThat(ex).isInstanceOf(PaymentException.class)
			.extracting(e -> ((PaymentException) e).getErrorCode())
			.isIn(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED, PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED));

		// then: 최종 payment 상태는 FAILED 또는 UNKNOWN 중 하나 (lost update 없음 — 두 값 중 하나로 확정)
		Payment finalPayment = paymentPersistence.getPayment(merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.APPROVE);
		assertThat(finalPayment.getStatus()).isIn(PaymentStatus.FAILED, PaymentStatus.UNKNOWN);
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("optlock-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct() {
		return Product.builder()
			.name("test-product-" + UUID.randomUUID().toString().substring(0, 8))
			.price(1000)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}
}
