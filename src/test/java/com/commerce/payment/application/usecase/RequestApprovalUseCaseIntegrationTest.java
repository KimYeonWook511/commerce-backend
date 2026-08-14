package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

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
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.dto.ApprovalResult;
import com.commerce.payment.application.dto.ApprovalStatus;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.PgCallType;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 승인 요청 경로를 실제 DB 위에서 확인한다. 결제사만 대역으로 두고, 결제 행의 상태 전이·활성 슬롯·
 * 호출 기록은 실제 저장소가 거동을 정한다.
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
	PgCallLogPersistenceTestSupport.class
})
class RequestApprovalUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;
	private static final String PG_PAYMENT_ID = "pg-payment-1";

	@Autowired
	private RequestApprovalUseCase requestApprovalUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@MockitoBean
	private NotificationPort notificationPort;

	@MockitoSpyBean
	private PaymentRepository paymentRepository;

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
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	// ── 확정 경로 ────────────────────────────────────────────────

	@DisplayName("승인이 끝나면 결제가 성공으로 확정되고 주문이 결제완료가 된다")
	@Test
	void approve_whenApproved_succeedsPaymentAndCompletesOrder() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, fixture.amount());

		ApprovalResult result = approve(fixture);

		assertThat(result.status()).isEqualTo(ApprovalStatus.SUCCESS);
		assertThat(result.pgPaymentId()).isEqualTo(PG_PAYMENT_ID);

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		// 성공한 결제는 슬롯을 계속 쥔다 — 놓으면 같은 주문에 결제가 둘 성립한다.
		assertThat(payment.getActiveOrderKey()).isEqualTo(fixture.order().getId());
		assertThat(orderPersistence.getOrderStatusById(fixture.order().getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("결제사가 승인한 금액과 거래 번호가 결제 행에 남는다")
	@Test
	void approve_whenApproved_storesApprovedAmountAndTransactionId() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, fixture.amount());

		approve(fixture);

		Payment payment = reload(fixture);
		assertThat(payment.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(payment.getPgTransactionId()).isEqualTo("hist-1");
	}

	@DisplayName("같은 결제에 승인이 두 번 와도 결제사를 다시 부르지 않고 앞선 결과가 그대로 나간다")
	@Test
	void approve_whenApprovalArrivesTwice_returnsPreviousResultWithoutCallingGateway() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, fixture.amount());
		approve(fixture);

		ApprovalResult second = approve(fixture);

		assertThat(second.status()).isEqualTo(ApprovalStatus.SUCCESS);
		then(paymentGatewayPort).should().approve(any(Payment.class));
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	// ── 소유와 종결된 시도 ───────────────────────────────────────

	@DisplayName("남의 결제 키로는 결제사를 부르지 못하고 그 번호가 있는지도 드러나지 않는다")
	@Test
	void approve_whenPaymentKeyBelongsToAnotherMember_rejectsWithoutCallingGateway() {
		Fixture fixture = readyPayment();
		Member stranger = saveMember();

		assertThatThrownBy(() -> requestApprovalUseCase.approve(
			stranger.getId(), fixture.payment().getPaymentKey(), PG_PAYMENT_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_NOT_FOUND.getMessage());

		then(paymentGatewayPort).should(never()).approve(any(Payment.class));
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.READY);
	}

	@DisplayName("종결된 결제로 승인 요청이 오면 결제사를 부르지 않는다")
	@Test
	void approve_whenPaymentAlreadyClosed_doesNotCallGateway() {
		Fixture fixture = readyPayment();
		Payment closed = fixture.payment();
		closed.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
		closed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentPersistence.save(closed);

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED.getMessage());

		then(paymentGatewayPort).should(never()).approve(any(Payment.class));
	}

	@DisplayName("자리를 내준 결제창에서 인증이 돌아와도 결제사를 부르지 않는다")
	@Test
	void approve_whenPaymentWasSuperseded_doesNotCallGateway() {
		Fixture fixture = readyPayment();
		Payment superseded = fixture.payment();
		superseded.expire(PaymentCloseCode.SUPERSEDED);
		paymentPersistence.save(superseded);

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED.getMessage());

		then(paymentGatewayPort).should(never()).approve(any(Payment.class));
	}

	@DisplayName("이미 결제된 주문에 승인이 들어오면 중복 결제로 거절하고 결제를 반려로 종결한다")
	@Test
	void approve_whenOrderAlreadyPaid_rejectsAsDuplicate() {
		Fixture fixture = readyPayment();
		Order order = orderPersistence.findById(fixture.order().getId()).orElseThrow();
		order.completePayment();
		orderPersistence.saveAndFlush(order);
		givenApproveSucceeded(fixture, fixture.amount());

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_DUPLICATE.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.ORDER_NOT_PAYABLE);
		assertThat(payment.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	// ── 결제사 응답 갈래 ─────────────────────────────────────────

	@DisplayName("결제사가 이미 처리된 건이라고 하면 이력을 읽어 확정한다")
	@Test
	void approve_whenGatewayAnswersUnsettled_confirmsFromHistory() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 완료된 결제", respondedRecord("AlreadyComplete")));
		givenHistory(PgHistoryResult.succeeded(
			List.of(approvalEntry(fixture, fixture.amount())), "성공"));

		ApprovalResult result = approve(fixture);

		assertThat(result.status()).isEqualTo(ApprovalStatus.SUCCESS);
		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(payment.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(payment.getPgTransactionId()).isEqualTo("hist-1");
	}

	@DisplayName("결제사가 아직 처리 중이라 이력에도 없으면 결과 불명으로 두고 확인 중이라 답한다")
	@Test
	void approve_whenHistoryHasNothing_leavesUnknown() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 진행 중인 결제", respondedRecord("AlreadyOnGoing")));
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());

		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("이력이 우리 것이 아닌 성공한 취소를 보이면 결제를 실패로 종결하고 이미 취소됐다고 답한다")
	@Test
	void approve_whenHistoryShowsForeignCancel_closesPaymentAsExternallyCanceled() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 완료된 결제", respondedRecord("AlreadyComplete")));
		givenHistory(PgHistoryResult.succeeded(List.of(
			approvalEntry(fixture, fixture.amount()),
			refundEntry(fixture.amount(), true, null)
		), "성공"));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_ALREADY_CANCELED.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.EXTERNALLY_CANCELED);
		// 돈이 이미 돌아갔으므로 되돌릴 것이 없지만, 승인이 났었다는 사실은 그 행에 남는다.
		assertThat(payment.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(payment.getActiveOrderKey()).isNull();
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("이력의 취소가 실패한 시도뿐이면 그것을 근거로 결제를 닫지 않는다")
	@Test
	void approve_whenHistoryCancelFailed_doesNotClosePayment() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 완료된 결제", respondedRecord("AlreadyComplete")));
		givenHistory(PgHistoryResult.succeeded(List.of(
			approvalEntry(fixture, fixture.amount()),
			refundEntry(fixture.amount(), false, null)
		), "성공"));

		ApprovalResult result = approve(fixture);

		assertThat(result.status()).isEqualTo(ApprovalStatus.SUCCESS);
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("승인 응답을 받지 못하면 이력을 읽지 않고 결과 불명으로 둔다")
	@Test
	void approve_whenAnswerLost_leavesUnknownWithoutReadingHistory() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unanswered("결제사 응답을 받지 못했다", timedOutRecord()));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		// 그 자리에서 다시 부르지 않는다 — 결제사가 원천사로 승인을 보내 놓고 기다리는 중일 수 있다.
		then(paymentGatewayPort).should().approve(any(Payment.class));
		then(paymentGatewayPort).should(never()).readHistory(any(Payment.class), any(PgHistoryScope.class));
	}

	@DisplayName("요청 흐름의 첫 승인 호출이 거절되면 결제를 실패로 종결하고 슬롯을 반납한다")
	@Test
	void approve_whenFirstCallDeclined_closesPaymentAsFailed() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.terminalFailure(true, "승인 거절", respondedRecord("Fail")));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.PG_DECLINED);
		assertThat(payment.getApprovedAmount()).isNull();
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("점검·장애로 승인이 안 되면 거절과 다른 종결 코드로 종결한다")
	@Test
	void approve_whenGatewayUnavailable_closesWithDistinctCloseCode() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.retryableFailure(true, "서비스 점검 중", respondedRecord("MaintenanceOngoing")));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_APPROVAL_FAILED.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.PG_UNAVAILABLE);
	}

	// ── 응답 검증 ────────────────────────────────────────────────

	@DisplayName("승인 금액이 주문 금액과 다르면 반려로 종결하고 결제사가 승인한 금액을 남긴다")
	@Test
	void approve_whenApprovedAmountDiffers_rejectsAndStoresGatewayAmount() {
		Fixture fixture = readyPayment();
		int approvedAmount = fixture.amount() + 2_000;
		givenApproveSucceeded(fixture, approvedAmount);

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.AMOUNT_MISMATCH);
		// 되돌릴 때 기준이 되는 값이라 우리 기록이 아니라 결제사가 승인한 금액을 담는다.
		assertThat(payment.getApprovedAmount()).isEqualTo(approvedAmount);
		assertThat(payment.getActiveOrderKey()).isNull();
	}

	@DisplayName("승인 금액이 0이면 확정하지 않고 결과 불명으로 두며 승인 금액을 담지 않는다")
	@Test
	void approve_whenApprovedAmountIsZero_leavesUnknownWithoutStoringAmount() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, 0);

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getApprovedAmount()).isNull();
		// 결과를 모르므로 슬롯을 놓지 않는다. 놓으면 회원이 다시 결제해 돈이 두 번 나갈 수 있다.
		assertThat(payment.getActiveOrderKey()).isEqualTo(fixture.order().getId());
	}

	@DisplayName("승인 응답의 회원이 결제 행의 회원과 다르면 확정하지도 종결하지도 않는다")
	@Test
	void approve_whenMemberKeyDiffers_neitherConfirmsNorCloses() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class))).willReturn(PgApproveResult.succeeded(
			fixture.payment().getPaymentKey(), "999999", fixture.amount(), "hist-1", "성공",
			respondedRecord("Success")));

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(payment.getCloseCode()).isNull();
		assertThat(payment.getApprovedAmount()).isNull();
		// 종결하면 슬롯이 풀려 두 번 나갈 수 있어 그대로 쥔 채 남긴다.
		assertThat(payment.getActiveOrderKey()).isEqualTo(fixture.order().getId());
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	// ── 결제 키가 어긋난 응답 ────────────────────────────────────

	@DisplayName("결제 키가 다른 승인 응답은 우리 결제를 종결하고 그 주인의 결제를 회수한다")
	@Test
	void approve_whenPaymentKeyDiffers_closesOursAndReclaimsCounterpart() {
		Fixture fixture = readyPayment();
		Fixture counterpart = readyPayment();
		givenApproveSucceededWithKey(counterpart.payment().getPaymentKey(), fixture.amount());

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_KEY_MISMATCH.getMessage());

		Payment ours = reload(fixture);
		assertThat(ours.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(ours.getCloseCode()).isEqualTo(PaymentCloseCode.PAYMENT_KEY_MISMATCH);

		Payment reclaimed = reload(counterpart);
		assertThat(reclaimed.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(reclaimed.getPgPaymentId()).isEqualTo(PG_PAYMENT_ID);
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("승인을 이미 부른 결제는 회수 대상이 아니어서 상태가 그대로 남는다")
	@Test
	void approve_whenCounterpartAlreadyCalled_leavesCounterpartUntouched() {
		Fixture fixture = readyPayment();
		Fixture counterpart = readyPayment();
		Payment inFlight = counterpart.payment();
		inFlight.markInProgress("pg-payment-other", LocalDateTime.now());
		paymentPersistence.save(inFlight);
		givenApproveSucceededWithKey(counterpart.payment().getPaymentKey(), fixture.amount());

		assertThatThrownBy(() -> approve(fixture))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_KEY_MISMATCH.getMessage());

		Payment untouched = reload(counterpart);
		assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(untouched.getPgPaymentId()).isEqualTo("pg-payment-other");
		// 회수하지 않는 경우에도 통지는 한다.
		then(notificationPort).should().notifyManualReviewRequired(
			any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("회수 저장이 실패하면 우리 결제의 종결도 함께 없던 일이 되고 상대의 번호는 그 행에 남는다")
	@Test
	void approve_whenReclaimSaveFails_rollsBackOurCloseToo() {
		Fixture fixture = readyPayment();
		Fixture counterpart = readyPayment();
		givenApproveSucceededWithKey(counterpart.payment().getPaymentKey(), fixture.amount());
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED))
			.given(paymentRepository)
			.saveChecked(argThat(payment -> counterpart.payment().getId().equals(payment.getId())));

		assertThatThrownBy(() -> approve(fixture)).isInstanceOf(PaymentException.class);

		Payment ours = reload(fixture);
		assertThat(ours.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(ours.getCloseCode()).isNull();
		// 부르기 직전에 커밋해 둔 번호가 그 행에 남아 있어 대사가 같은 판정을 다시 한다.
		assertThat(ours.getPgPaymentId()).isEqualTo(PG_PAYMENT_ID);
		assertThat(reload(counterpart).getStatus()).isEqualTo(PaymentStatus.READY);
	}

	@DisplayName("같은 결제사 번호를 두 결제가 가져도 이력에 실린 결제 키가 자기 것인 쪽만 확정한다")
	@Test
	void approve_whenTwoPaymentsSharePgPaymentId_confirmsOnlyTheOneInHistory() {
		Fixture fixture = readyPayment();
		Fixture other = readyPayment();
		Payment sharing = other.payment();
		sharing.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
		paymentPersistence.save(sharing);

		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 완료된 결제", respondedRecord("AlreadyComplete")));
		givenHistory(PgHistoryResult.succeeded(List.of(approvalEntry(fixture, fixture.amount())), "성공"));

		approve(fixture);

		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(reload(other).getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
	}

	// ── 결제사 호출 기록 ─────────────────────────────────────────

	@DisplayName("승인 호출이 기록에 쌓이고 응답 원본이 함께 담긴다")
	@Test
	void approve_whenCalled_appendsCallLogWithRawResponse() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, fixture.amount());

		approve(fixture);

		List<PgCallLog> logs = pgCallLogPersistence.findAll();
		assertThat(logs).hasSize(1);
		PgCallLog log = logs.get(0);
		assertThat(log.getPaymentId()).isEqualTo(fixture.payment().getId());
		assertThat(log.getCallType()).isEqualTo(PgCallType.APPROVE);
		assertThat(log.getPgIdempotencyKey()).isEqualTo(fixture.payment().getPaymentKey() + "-1");
		assertThat(log.getRawResponse()).isEqualTo("{\"code\":\"Success\"}");
		assertThat(log.getResultCode()).isEqualTo("Success");
		assertThat(log.getRespondedAt()).isNotNull();
		assertThat(log.getRefundId()).isNull();
	}

	@DisplayName("응답을 받지 못한 호출도 기록에 남고 그 원인이 구분돼 담긴다")
	@Test
	void approve_whenAnswerLost_keepsCallLogWithoutRespondedAt() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unanswered("결제사 응답을 받지 못했다", timedOutRecord()));

		assertThatThrownBy(() -> approve(fixture)).isInstanceOf(PaymentException.class);

		List<PgCallLog> logs = pgCallLogPersistence.findAll();
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).getRequestedAt()).isNotNull();
		assertThat(logs.get(0).getRespondedAt()).isNull();
		assertThat(logs.get(0).getErrorType()).isEqualTo(PgErrorType.TIMEOUT);
	}

	@DisplayName("부르는 도중 흐름이 끊겨 결과를 채우지 못해도 부르기 직전에 남긴 기록은 그대로 남는다")
	@Test
	void approve_whenCallDiesMidway_keepsCallLogWrittenBeforeTheCall() {
		Fixture fixture = readyPayment();
		// 부르는 도중 프로세스가 죽으면 그 뒤로는 아무 코드도 실행되지 않는다. 결과를 채우는 자리에도
		// 닿지 못하므로, 행이 남아 있다는 것 자체가 기록을 부르기 직전에 커밋했다는 뜻이다.
		willThrow(new RuntimeException("승인 호출 도중 중단"))
			.given(paymentGatewayPort).approve(any(Payment.class));

		assertThatThrownBy(() -> approve(fixture)).isInstanceOf(RuntimeException.class);

		List<PgCallLog> logs = pgCallLogPersistence.findAll();
		assertThat(logs).hasSize(1);
		PgCallLog log = logs.get(0);
		assertThat(log.getPaymentId()).isEqualTo(fixture.payment().getId());
		assertThat(log.getCallType()).isEqualTo(PgCallType.APPROVE);
		assertThat(log.getRequestedAt()).isNotNull();
		assertThat(log.getRespondedAt()).isNull();
		assertThat(log.getErrorType()).isNull();
		assertThat(log.getRawResponse()).isNull();
	}

	@DisplayName("이력 조회는 호출 기록에 쌓이지 않는다")
	@Test
	void approve_whenHistoryRead_doesNotAppendCallLog() {
		Fixture fixture = readyPayment();
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 완료된 결제", respondedRecord("AlreadyComplete")));
		givenHistory(PgHistoryResult.succeeded(List.of(approvalEntry(fixture, fixture.amount())), "성공"));

		approve(fixture);

		// 승인 호출 하나만 남는다 — 조회 횟수가 섞이면 몇 번 시도했는지가 묻힌다.
		assertThat(pgCallLogPersistence.findAll()).hasSize(1);
	}

	@DisplayName("호출 기록을 남겨도 결제 행의 버전이 오르지 않는다")
	@Test
	void approve_whenCallLogged_doesNotBumpPaymentVersion() {
		Fixture fixture = readyPayment();
		givenApproveSucceeded(fixture, fixture.amount());

		approve(fixture);
		Long versionAfterApproval = reload(fixture).getVersion();

		// 부르기 직전 전이와 확정만 버전을 올린다. 순수한 기록 남기기가 낙관 락 충돌을 일으키면
		// 기록 때문에 판정이 흔들린다.
		assertThat(versionAfterApproval).isEqualTo(2L);
	}

	// ── 픽스처 ───────────────────────────────────────────────────

	private ApprovalResult approve(Fixture fixture) {
		return requestApprovalUseCase.approve(
			fixture.member().getId(), fixture.payment().getPaymentKey(), PG_PAYMENT_ID);
	}

	private Payment reload(Fixture fixture) {
		return paymentPersistence.findById(fixture.payment().getId()).orElseThrow();
	}

	private void givenApproveSucceeded(Fixture fixture, int approvedAmount) {
		givenApproveSucceededWithKey(fixture.payment().getPaymentKey(), approvedAmount);
	}

	private void givenApproveSucceededWithKey(String paymentKey, int approvedAmount) {
		given(paymentGatewayPort.approve(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			return PgApproveResult.succeeded(
				paymentKey, String.valueOf(payment.getMemberId()), approvedAmount, "hist-1", "성공",
				respondedRecord("Success"));
		});
	}

	private void givenHistory(PgHistoryResult history) {
		given(paymentGatewayPort.readHistory(any(Payment.class), any(PgHistoryScope.class))).willReturn(history);
	}

	private PgHistoryEntry approvalEntry(Fixture fixture, int amount) {
		return new PgHistoryEntry(PgHistoryEntryType.APPROVAL, true, amount, LocalDateTime.now(),
			fixture.payment().getPaymentKey(), null, "hist-1");
	}

	private PgHistoryEntry refundEntry(int amount, boolean succeeded, String refundAttemptKey) {
		return new PgHistoryEntry(PgHistoryEntryType.REFUND, succeeded, amount, LocalDateTime.now(),
			null, refundAttemptKey, "hist-2");
	}

	private PgCallRecord respondedRecord(String resultCode) {
		return new PgCallRecord(PgErrorType.NONE, resultCode, 200, "{\"code\":\"" + resultCode + "\"}");
	}

	private PgCallRecord timedOutRecord() {
		return new PgCallRecord(PgErrorType.TIMEOUT, null, null, null);
	}

	private Fixture readyPayment() {
		Member member = saveMember();
		Order order = saveOrder(member);
		Payment payment = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + (++uniqueSuffix), "idem-" + uniqueSuffix, order.getTotalPrice());
		return new Fixture(member, order, paymentPersistence.save(payment));
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("approve-" + (++uniqueSuffix) + "@example.com", "password123", "approve-user"));
	}

	private Order saveOrder(Member member) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}

	private record Fixture(Member member, Order order, Payment payment) {

		private int amount() {
			return payment.getAmount();
		}
	}
}
