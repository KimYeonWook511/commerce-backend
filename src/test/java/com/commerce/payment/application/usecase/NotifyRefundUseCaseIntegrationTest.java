package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.Optional;

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
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 회원 돈이 안 돌아간 환불을 알리는 주기를 실제 DB 위에서 확인한다. 통지 시각을 보낸 뒤에 남긴다는 것이
 * 이 배치의 핵심이고, 그 순서는 커밋 경계 위에서만 드러난다.
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
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class NotifyRefundUseCaseIntegrationTest {

	private static final int AMOUNT = 10_000;

	@Autowired
	private NotifyRefundUseCase notifyRefundUseCase;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

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

	@DisplayName("승급 시간을 넘긴 환불을 그 주문과 결제 키로 알리고 마지막으로 알린 시각을 남긴다")
	@Test
	void notifyStalled_whenThresholdPassed_notifiesAndStampsTime() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);

		notifyRefundUseCase.notifyStalled();

		then(notificationPort).should()
			.notifyManualReviewRequired(eq(payment.getOrderId()), eq(payment.getPaymentKey()), any(String.class));
		assertThat(reload(refund).getLastNotifyAt()).isNotNull();
	}

	@DisplayName("통지 간격이 지나기 전에는 다시 알리지 않는다")
	@Test
	void notifyStalled_whenIntervalHasNotPassed_doesNotNotifyAgain() {
		unknownRefund(savePayment());
		notifyRefundUseCase.notifyStalled();

		notifyRefundUseCase.notifyStalled();

		then(notificationPort).should(times(1))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("통지 간격이 지나면 상태가 이어지는 동안 다시 알린다")
	@Test
	void notifyStalled_whenIntervalPassed_notifiesAgain() {
		Refund refund = unknownRefund(savePayment());
		refund.recordNotified(LocalDateTime.now().minusHours(3));
		refundPersistence.save(refund);

		notifyRefundUseCase.notifyStalled();

		then(notificationPort).should()
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
		assertThat(reload(refund).getLastNotifyAt()).isAfter(LocalDateTime.now().minusMinutes(1));
	}

	@DisplayName("통지 전송이 실패하면 알린 것으로 남기지 않아 다음 주기에 다시 대상이 된다")
	@Test
	void notifyStalled_whenSendingFails_leavesItAsATargetAgain() {
		Refund refund = unknownRefund(savePayment());
		willThrow(new IllegalStateException("전송 실패"))
			.given(notificationPort).notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));

		notifyRefundUseCase.notifyStalled();

		// 먼저 남기고 보내면 한 번도 안 나갔는데 다시 대상이 되지 않는다.
		assertThat(reload(refund).getLastNotifyAt()).isNull();
		notifyRefundUseCase.notifyStalled();
		then(notificationPort).should(times(2))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("사람이 처리해야 하는 환불은 승급을 기다리지 않고 바로 대상이 되며 반복해서 알린다")
	@Test
	void notifyStalled_whenRefundNeedsManualWork_keepsNotifying() {
		Refund refund = unknownRefund(savePayment());
		refund.flagForReview(RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED, "우리가 모르는 환불이 있다");
		refundPersistence.save(refund);
		notifyRefundUseCase.notifyStalled();

		Refund notified = reload(refund);
		notified.recordNotified(LocalDateTime.now().minusHours(3));
		refundPersistence.save(notified);
		notifyRefundUseCase.notifyStalled();

		// 알림이 계속 온다는 것이 곧 아직 아무도 돈을 돌려주지 않았다는 사실이다.
		then(notificationPort).should(times(2))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("성공으로 끝난 환불은 통지 대상에서 저절로 빠진다")
	@Test
	void notifyStalled_whenRefundSucceeded_stopsNotifying() {
		Refund refund = unknownRefund(savePayment());
		refund.complete("pg-cancel-1");
		refundPersistence.save(refund);

		notifyRefundUseCase.notifyStalled();

		then(notificationPort).shouldHaveNoInteractions();
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

	private Refund unknownRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-" + uniqueSuffix);
		refund.markInProgress(LocalDateTime.now().minusMinutes(5));
		refund.markUnknown();
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}
}
