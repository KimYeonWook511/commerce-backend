package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;

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
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결과가 정해지지 않은 결제를 운영자에게 알리는 주기를 실제 DB 위에서 확인한다. 통지 시각을 보낸 뒤에
 * 남긴다는 것이 이 배치의 핵심이고, 그 순서는 커밋 경계 위에서만 드러난다.
 *
 * <p>통지 승급 시간을 0으로 두어 만들어지자마자 대상이 되게 한다. 행이 만들어진 시각은 감사 컬럼이
 * 채우므로 과거로 옮길 수 없고, 임계값을 낮추는 것이 같은 조건을 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@TestPropertySource(properties = "payment.postprocess.notify.escalation=0s")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class
})
class NotifyPaymentUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;
	private static final String PG_PAYMENT_ID = "pg-payment-1";

	@Autowired
	private NotifyPaymentUseCase notifyPaymentUseCase;

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

	@DisplayName("승급 시간을 넘긴 결제를 알리고 마지막으로 알린 시각을 남긴다")
	@Test
	void notifyStalled_whenThresholdPassed_notifiesAndStampsTime() {
		Payment payment = unknownPayment();

		notifyPaymentUseCase.notifyStalled();

		then(notificationPort).should()
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
		assertThat(reload(payment).getLastNotifyAt()).isNotNull();
	}

	@DisplayName("통지 간격이 지나기 전에는 다시 알리지 않는다")
	@Test
	void notifyStalled_whenIntervalHasNotPassed_doesNotNotifyAgain() {
		unknownPayment();
		notifyPaymentUseCase.notifyStalled();

		notifyPaymentUseCase.notifyStalled();

		then(notificationPort).should(times(1))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("통지 간격이 지나면 상태가 이어지는 동안 다시 알린다")
	@Test
	void notifyStalled_whenIntervalPassed_notifiesAgain() {
		Payment payment = unknownPayment();
		payment.recordNotified(LocalDateTime.now().minusHours(3));
		paymentPersistence.save(payment);

		notifyPaymentUseCase.notifyStalled();

		then(notificationPort).should()
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
		assertThat(reload(payment).getLastNotifyAt()).isAfter(LocalDateTime.now().minusMinutes(1));
	}

	@DisplayName("통지 전송이 실패하면 알린 것으로 남기지 않아 다음 주기에 다시 대상이 된다")
	@Test
	void notifyStalled_whenSendingFails_leavesItAsATargetAgain() {
		Payment payment = unknownPayment();
		willThrow(new IllegalStateException("전송 실패"))
			.given(notificationPort).notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));

		notifyPaymentUseCase.notifyStalled();

		// 먼저 남기고 보내면 한 번도 안 나갔는데 다시 대상이 되지 않는다.
		assertThat(reload(payment).getLastNotifyAt()).isNull();
		notifyPaymentUseCase.notifyStalled();
		then(notificationPort).should(times(2))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("종결된 결제는 통지 대상에서 저절로 빠진다")
	@Test
	void notifyStalled_whenPaymentIsSettled_stopsNotifying() {
		Payment payment = unknownPayment();
		payment.fail(PaymentCloseCode.UNCONFIRMED_CLOSED, "승인 가능 시간 초과");
		paymentPersistence.save(payment);

		notifyPaymentUseCase.notifyStalled();

		then(notificationPort).shouldHaveNoInteractions();
	}

	private Payment reload(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}

	private Payment unknownPayment() {
		Member member = memberPersistence.save(
			Member.createUser("notify-" + (++uniqueSuffix) + "@example.com", "password123", "notify-user"));
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);

		Payment payment = Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + uniqueSuffix, "idem-" + uniqueSuffix, saved.getTotalPrice());
		payment.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
		payment.markUnknown();
		return paymentPersistence.save(payment);
	}
}
