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
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.TestcontainersSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, PaymentReservationPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentApprovalServiceConcurrencyTest {

	@Autowired
	private PaymentApprovalService paymentApprovalService;

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

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("동시에 결제 완료를 호출해도 payment는 하나만 생성된다")
	@Test
	void succeedApproval_whenConcurrentCall_createSinglePayment() throws Exception {
		// given
		String merchantPayKey = "PAY-CON-1";
		String pgPaymentId = "pg-con-1";
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("product-" + merchantPayKey, 1000));
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(order.getId(), member.getId(), 1000, PaymentProvider.NAVERPAY,
				merchantPayKey, LocalDateTime.now().plusMinutes(15))
		);
		Payment payment = paymentPersistence.save(createApprovePayment(reservation, pgPaymentId));
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

		// when
		runConcurrent(20, () -> paymentApprovalService.succeedApproval(payment, LocalDateTime.now()), errors);

		// then
		assertThat(paymentPersistence.countPaymentsByMerchantPayKey(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusById(order.getId()))
			.isEqualTo(OrderStatus.PAID);
		assertThat(errors.stream().allMatch(this::isAllowedConcurrentException)).isTrue();
	}

	private boolean isAllowedConcurrentException(Throwable throwable) {
		if (throwable instanceof PaymentException paymentException) {
			// saveApproved가 uk_payment_approved_order_key 위반을 PAYMENT_DUPLICATE로 매핑
			return paymentException.getErrorCode() == PaymentErrorCode.PAYMENT_DUPLICATE
				// Order FOR UPDATE 직렬화 후 두 번째 스레드가 이미 SUCCEEDED 상태인 payment를 다시 mark할 때 발생
				|| paymentException.getErrorCode() == PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED;
		}
		if (throwable instanceof OrderException orderException) {
			return orderException.getErrorCode() == OrderErrorCode.ORDER_PAID_NOT_ALLOWED;
		}
		return false;
	}

	private void runConcurrent(int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						task.run();
					} catch (Throwable ex) {
						errors.add(ex);
					} finally {
						doneLatch.countDown();
					}
				});
			}
			startLatch.countDown();
			doneLatch.await(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("payment-con-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}

	private Payment createApprovePayment(PaymentReservation reservation, String pgPaymentId) {
		return Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
	}
}
