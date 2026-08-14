package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 승인 반려가 결제 종결과 환불 생성을 한 트랜잭션 한 저장으로 하는지 실제 DB 위에서 확인한다.
 * 한쪽만 반영되면 결제는 종착이라 아무도 다시 보지 않고 되돌릴 근거도 없어, 그 경계는 진짜 트랜잭션
 * 위에서만 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class ClosePaymentUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;
	private static final String PG_TRANSACTION_ID = "pg-tx-1";

	@Autowired
	private ClosePaymentUseCase closePaymentUseCase;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoSpyBean
	private RefundRepository refundRepository;

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
	private RefundPersistenceTestSupport refundPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			refundPersistence, paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	// ── 반려가 되돌릴 근거를 남긴다 ──────────────────────────────

	@DisplayName("주문이 결제 가능한 상태가 아니면 결제를 반려로 종결하고 되돌릴 환불이 함께 커밋된다")
	@Test
	void rejectOrderNotPayable_whenCalled_closesPaymentAndOpensRefundTogether() {
		Payment payment = inProgressPayment();

		closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		Payment closed = reload(payment);
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.ORDER_NOT_PAYABLE);
		assertThat(closed.getApprovedAmount()).isEqualTo(payment.getAmount());
		assertThat(closed.getActiveOrderKey()).isNull();

		Refund refund = onlyRefund();
		assertThat(refund.getPaymentId()).isEqualTo(payment.getId());
		assertThat(refund.getRequester()).isEqualTo(RefundRequester.SYSTEM);
		assertThat(refund.getReason()).isEqualTo(RefundReason.ORDER_NOT_PAYABLE);
		assertThat(refund.getAmount()).isEqualTo(payment.getAmount());
		assertThat(refund.getIdempotencyKey()).isEqualTo(RefundReason.ORDER_NOT_PAYABLE.name());
		then(notificationPort).should(never()).notifyManualReviewRequired(any(), any(), any());
	}

	@DisplayName("금액 불일치 반려는 결제사가 승인한 금액을 되돌리고 사유가 주문 취소 반려와 갈린다")
	@Test
	void rejectAmountMismatch_whenCalled_opensRefundForTheGatewayAmount() {
		Payment payment = inProgressPayment();
		int approvedAmount = payment.getAmount() + 2_000;

		closePaymentUseCase.rejectAmountMismatch(payment, approvedAmount, PG_TRANSACTION_ID);

		Payment closed = reload(payment);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.AMOUNT_MISMATCH);
		assertThat(closed.getApprovedAmount()).isEqualTo(approvedAmount);

		Refund refund = onlyRefund();
		assertThat(refund.getReason()).isEqualTo(RefundReason.AMOUNT_MISMATCH);
		assertThat(refund.getAmount()).isEqualTo(approvedAmount);
	}

	@DisplayName("환불을 만들면 결제 행의 환불 합이 오르고 버전도 함께 오른다")
	@Test
	void rejectOrderNotPayable_whenCalled_raisesTotalRefundedAmountAndVersion() {
		Payment payment = inProgressPayment();
		Long versionBefore = reload(payment).getVersion();

		closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		Payment closed = reload(payment);
		// 이 갱신이 없으면 동시에 온 두 요청이 서로를 감지하지 못한다.
		assertThat(closed.getTotalRefundedAmount()).isEqualTo(payment.getAmount());
		assertThat(closed.getVersion()).isGreaterThan(versionBefore);
	}

	@DisplayName("환불 상태를 바꿔도 결제 행의 환불 합과 버전은 그대로다")
	@Test
	void refundTransition_whenApplied_leavesPaymentRowUntouched() {
		Payment payment = inProgressPayment();
		closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);
		Payment afterRejection = reload(payment);

		Refund refund = refundRepository.findById(onlyRefund().getId()).orElseThrow();
		refund.markInProgress(LocalDateTime.now());
		refundRepository.saveChecked(refund);

		Payment afterTransition = reload(payment);
		// 여기서 결제를 함께 저장하면 대사가 한 바퀴 돌 때마다 회원의 환불 요청이 밀린다.
		assertThat(afterTransition.getTotalRefundedAmount()).isEqualTo(afterRejection.getTotalRefundedAmount());
		assertThat(afterTransition.getVersion()).isEqualTo(afterRejection.getVersion());
	}

	// ── 재실행과 어긋난 상태 ─────────────────────────────────────

	@DisplayName("같은 승인 반려를 두 번 실행해도 환불 사건이 하나다")
	@Test
	void rejectOrderNotPayable_whenExecutedTwice_keepsSingleRefund() {
		Payment payment = inProgressPayment();
		closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		closePaymentUseCase.rejectOrderNotPayable(
			reload(payment), OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		// 금액을 다시 계산하면 앞서 만든 환불이 한도를 잡고 있어 남은 한도가 0이 된다.
		Refund refund = onlyRefund();
		assertThat(refund.getAmount()).isEqualTo(payment.getAmount());
		assertThat(reload(payment).getTotalRefundedAmount()).isEqualTo(payment.getAmount());
	}

	@DisplayName("결제가 이미 종착이어도 반려 환불은 만들어지고 정합성 이상으로 알린다")
	@Test
	void rejectOrderNotPayable_whenPaymentAlreadyClosed_stillOpensRefundAndNotifies() {
		Payment payment = inProgressPayment();
		payment.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentPersistence.save(payment);

		closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		Payment closed = reload(payment);
		// 전이가 안 됐다고 환불까지 롤백하면 이미 나간 돈이 그대로 남는다.
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.PG_DECLINED);
		assertThat(onlyRefund().getAmount()).isEqualTo(payment.getAmount());
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("남은 한도가 0이면 환불도 결제 전이도 하지 않고 알리기만 한다")
	@Test
	void rejectOrderNotPayable_whenNoLimitIsLeft_neitherOpensRefundNorCloses() {
		Payment payment = inProgressPayment();
		exhaustRefundLimit(payment);

		closePaymentUseCase.rejectOrderNotPayable(
			reload(payment), OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID);

		Payment untouched = reload(payment);
		// 환불이 딸리지 않은 반려 행을 만들면 "반려된 결제에는 되돌릴 근거가 있다"는 대조가 무력해진다.
		assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(untouched.getCloseCode()).isNull();
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(refundPersistence.findAll().get(0).getRequester()).isEqualTo(RefundRequester.MEMBER);
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("환불 저장이 실패하면 결제 종결도 함께 없던 일이 된다")
	@Test
	void rejectOrderNotPayable_whenRefundSaveFails_rollsBackTheCloseToo() {
		Payment payment = inProgressPayment();
		willThrow(new PaymentException(PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED))
			.given(refundRepository).save(any(Refund.class));

		assertThatThrownBy(() -> closePaymentUseCase.rejectOrderNotPayable(
			payment, OrderErrorCode.ORDER_ALREADY_PAID, payment.getAmount(), PG_TRANSACTION_ID))
			.isInstanceOf(PaymentException.class);

		Payment untouched = reload(payment);
		assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(untouched.getCloseCode()).isNull();
		assertThat(untouched.getTotalRefundedAmount()).isZero();
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	// ── 되돌릴 대상이 아닌 종결 ──────────────────────────────────

	@DisplayName("승인 응답의 결제 키가 우리 것이 아니면 결제만 종결하고 환불을 만들지 않는다")
	@Test
	void closeKeyMismatch_whenCalled_closesPaymentWithoutOpeningRefund() {
		Payment payment = inProgressPayment();
		Payment counterpart = readyPayment();

		closePaymentUseCase.closeKeyMismatch(payment, counterpart.getPaymentKey(), "pg-payment-1");

		Payment closed = reload(payment);
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.PAYMENT_KEY_MISMATCH);
		// 나간 돈은 그 키의 주인 결제의 것이라 우리가 되돌릴 대상이 아니다.
		assertThat(refundPersistence.findAll()).isEmpty();
		assertThat(closed.getTotalRefundedAmount()).isZero();
	}

	// ── 픽스처 ───────────────────────────────────────────────────

	private Payment reload(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}

	private Refund onlyRefund() {
		List<Refund> refunds = refundPersistence.findAll();
		assertThat(refunds).hasSize(1);
		return refunds.get(0);
	}

	/** 회원 환불이 승인 금액을 다 잡아 남은 한도가 0인 결제를 만든다. 정상 경로로는 닿지 않는 상태다 */
	private void exhaustRefundLimit(Payment payment) {
		payment.recordApproval(payment.getAmount(), PG_TRANSACTION_ID);
		Refund taken = payment.openRefund(
			Optional.empty(), payment.getAmount(), RefundReason.ORDER_CANCELED, "IDEM-taken");
		refundPersistence.save(taken);
		paymentPersistence.save(payment);
	}

	private Payment inProgressPayment() {
		Payment payment = readyPayment();
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		return paymentPersistence.save(payment);
	}

	private Payment readyPayment() {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + (++uniqueSuffix), "idem-" + uniqueSuffix, order.getTotalPrice());
		return paymentPersistence.save(payment);
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("reject-" + (++uniqueSuffix) + "@example.com", "password123", "reject-user"));
	}

	private Order saveOrder(Member member) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 2, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}
}
