package com.commerce.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

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

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 환불 전이 단위작업이 결제 행을 어디까지 바꾸는지 실제 DB 위에서 확인한다. 확정 하나만 결제를 함께
 * 갱신하고 나머지는 환불만 바꾸는데, 커밋 경계와 낙관 락 버전은 대역으로 재현되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class RefundServiceIntegrationTest {

	private static final int APPROVED_AMOUNT = 10_000;
	private static final int REFUND_AMOUNT = 3_000;
	private static final String PG_TRANSACTION_ID = "pg-cancel-tx-1";

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

	@DisplayName("환불이 확정되면 결제의 실제로 돌아간 금액만 오르고 돌려주기로 한 금액은 그대로다")
	@Test
	void complete_whenRefundSettles_raisesSucceededAmountOnly() {
		Payment payment = savePayment();
		Refund refund = pendingRefund(payment, REFUND_AMOUNT);

		refundService.complete(refund.getId(), PG_TRANSACTION_ID);

		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		Payment stored = reloadPayment(payment);
		assertThat(stored.getRefundSucceededAmount()).isEqualTo(REFUND_AMOUNT);
		// 열릴 때 이미 한도를 잡았으므로 확정이 그 값을 또 올리면 같은 몫이 두 번 잡힌다.
		assertThat(stored.getRefundOpenedAmount()).isEqualTo(REFUND_AMOUNT);
	}

	@DisplayName("확정은 결제 행의 낙관 락 버전을 올린다")
	@Test
	void complete_whenRefundSettles_bumpsPaymentVersion() {
		Payment payment = savePayment();
		Refund refund = pendingRefund(payment, REFUND_AMOUNT);
		Long versionBefore = reloadPayment(payment).getVersion();

		refundService.complete(refund.getId(), PG_TRANSACTION_ID);

		// 버전을 안 올리면 다른 트랜잭션이 들고 있던 옛 값이 이 컬럼을 덮는다 — 변경 감지가 낸 UPDATE는
		// 모든 컬럼을 함께 쓴다.
		assertThat(reloadPayment(payment).getVersion()).isGreaterThan(versionBefore);
	}

	@DisplayName("승인 반려로 종결된 결제의 환불이 확정돼도 실제로 돌아간 금액이 오른다")
	@Test
	void complete_whenPaymentIsClosed_stillRaisesSucceededAmount() {
		Payment payment = rejectedPaymentWithRefund();
		Refund refund = refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getPaymentId().equals(payment.getId()))
			.findFirst()
			.orElseThrow();

		refundService.complete(refund.getId(), PG_TRANSACTION_ID);

		Payment stored = reloadPayment(payment);
		// 반려는 환불을 먼저 열고 그 뒤에 결제를 닫으므로 종결된 결제에 열린 환불이 딸린 것이 정상이다.
		assertThat(stored.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(stored.getRefundSucceededAmount()).isEqualTo(APPROVED_AMOUNT);
	}

	@DisplayName("같은 환불을 두 번 확정해도 실제로 돌아간 금액은 한 번만 오른다")
	@Test
	void complete_whenCalledTwice_raisesSucceededAmountOnce() {
		Payment payment = savePayment();
		Refund refund = pendingRefund(payment, REFUND_AMOUNT);
		refundService.complete(refund.getId(), PG_TRANSACTION_ID);

		// 앞 확정이 이미 커밋된 뒤에 다시 오는 확정은 환불의 상태 가드가 거부한다.
		assertThatThrownBy(() -> refundService.complete(refund.getId(), PG_TRANSACTION_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_STATUS_TRANSITION_NOT_ALLOWED.getMessage());

		assertThat(reloadPayment(payment).getRefundSucceededAmount()).isEqualTo(REFUND_AMOUNT);
	}

	@DisplayName("확정 말고 다른 환불 전이는 결제 행의 두 금액과 버전을 그대로 둔다")
	@Test
	void otherTransitions_whenApplied_leavePaymentRowUntouched() {
		Payment payment = savePayment();
		Refund refund = pendingRefund(payment, REFUND_AMOUNT);
		Payment before = reloadPayment(payment);

		refundService.markUnknown(refund.getId());
		refundService.recordRetryableFailure(refund.getId());
		refundService.recordReconciled(refund.getId(), LocalDateTime.now());
		refundService.recordNotified(refund.getId(), LocalDateTime.now());
		refundService.flagForReview(refund.getId(), RefundReviewCode.CANCEL_NOT_ALLOWED, "취소 불가");

		Payment after = reloadPayment(payment);
		// 결제를 함께 저장하면 대사가 한 바퀴 돌 때마다 회원의 환불 요청이 낙관 락 충돌로 밀린다.
		assertThat(after.getRefundOpenedAmount()).isEqualTo(before.getRefundOpenedAmount());
		assertThat(after.getRefundSucceededAmount()).isEqualTo(before.getRefundSucceededAmount());
		assertThat(after.getVersion()).isEqualTo(before.getVersion());
	}

	@DisplayName("금액 불변식이 깨진 결제의 환불을 확정하면 경합과 다른 실패 값으로 거부되고 환불 전이도 함께 되돌아간다")
	@Test
	void complete_whenAmountInvariantBroken_rejectsAndRollsBackTheRefundTransition() {
		Payment payment = savePayment();
		Refund refund = pendingUncountedRefund(payment, REFUND_AMOUNT);

		assertThatThrownBy(() -> refundService.complete(refund.getId(), PG_TRANSACTION_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.REFUND_SUCCEEDED_AMOUNT_INVARIANT_BROKEN.getMessage());

		Payment stored = reloadPayment(payment);
		assertThat(stored.getRefundSucceededAmount()).isZero();
		assertThat(stored.getRefundOpenedAmount()).isZero();
		// 금액 갱신이 환불 전이와 한 트랜잭션이라 거부되면 그 환불도 미결로 남는다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
	}

	// ── 헬퍼 ──

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			700L + suffix, 800L + suffix, PaymentPg.NAVERPAY, "PK-S-" + suffix, "idem-s-" + suffix, APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-s-" + suffix, LocalDateTime.now());
		payment.succeed(APPROVED_AMOUNT, "pg-tx-s-" + suffix);
		return paymentPersistence.save(payment);
	}

	/** 결제사를 부르고 응답을 기다리는 환불. 만드는 관문이 결제 안에 있어 결제도 함께 저장한다 */
	private Refund pendingRefund(Payment payment, int amount) {
		Refund refund = payment.openRefund(
			Optional.empty(), amount, RefundReason.ORDER_CANCELED, "IDEM-S-" + uniqueSuffix);
		refund.markInProgress(LocalDateTime.now());
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	/**
	 * 결제를 거치지 않고 만들어 붙인 환불. 그 금액이 돌려주기로 한 금액에 안 들어 있어, 확정하려 하면
	 * 불변식 가드에 걸린다. 정상 흐름으로는 이 상태가 만들어지지 않는다.
	 */
	private Refund pendingUncountedRefund(Payment payment, int amount) {
		Refund refund = Refund.open(
			payment.getId(), "RF-" + UUID.randomUUID().toString().replace("-", ""),
			RefundRequester.MEMBER, "IDEM-UNCOUNTED-" + uniqueSuffix, amount, RefundReason.ORDER_CANCELED);
		refund.markInProgress(LocalDateTime.now());
		return refundPersistence.save(refund);
	}

	/** 승인 반려로 닫힌 결제와 그 반려가 연 환불. 환불이 먼저 열리고 그 뒤에 결제가 닫힌다 */
	private Payment rejectedPaymentWithRefund() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			900L + suffix, 950L + suffix, PaymentPg.NAVERPAY, "PK-J-" + suffix, "idem-j-" + suffix, APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-j-" + suffix, LocalDateTime.now());
		payment.recordApproval(APPROVED_AMOUNT, "pg-tx-j-" + suffix);
		Payment saved = paymentPersistence.save(payment);

		Refund refund = saved.openRejectionRefund(Optional.empty(), RefundReason.ORDER_NOT_PAYABLE).orElseThrow();
		refund.markInProgress(LocalDateTime.now());
		refundPersistence.save(refund);
		saved.reject(PaymentCloseCode.ORDER_NOT_PAYABLE, "이미 취소된 주문");
		return paymentPersistence.save(saved);
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}

	private Payment reloadPayment(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}
}
