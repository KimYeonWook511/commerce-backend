package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결과를 모르는 결제를 대사가 어떻게 확정하는지 실제 DB 위에서 확인한다. 대상 조회가 인덱스와 상태
 * 조건 위에 서 있고, 집었다는 기록이 결제사 호출과 다른 트랜잭션이라는 것도 진짜 커밋 경계에서만
 * 드러난다.
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
	PgCallLogPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class
})
class ReconcilePaymentUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;
	private static final String PG_PAYMENT_ID = "pg-payment-1";
	private static final String PG_TRANSACTION_ID = "hist-1";

	@Autowired
	private ReconcilePaymentUseCase reconcilePaymentUseCase;

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
			pgCallLogPersistence, refundPersistence, paymentPersistence,
			memberPersistence, productPersistence, orderPersistence
		);
	}

	// ── 이력으로 확정한다 ────────────────────────────────────────

	@DisplayName("이력에 우리 승인이 있으면 결제를 성공으로 확정하고 승인 금액과 거래 번호를 남긴다")
	@Test
	void reconcile_whenHistoryHasOurApproval_confirmsWithAmountAndTransactionId() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(approvalEntry(fixture, fixture.amount())), "성공"));

		reconcilePaymentUseCase.reconcile();

		Payment settled = reload(fixture);
		assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		// 승인 금액이 비면 그 주문은 영영 환불할 수 없다 — 돈은 나갔는데 되돌릴 근거가 없다.
		assertThat(settled.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(settled.getPgTransactionId()).isEqualTo(PG_TRANSACTION_ID);
		assertThat(orderPersistence.getOrderStatusById(fixture.order().getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("승인을 부른 지 대사 유예가 지나지 않은 결제는 집지 않는다")
	@Test
	void reconcile_whenApprovalCallIsStillInFlight_leavesPaymentUntouched() {
		Fixture fixture = readyPayment();
		Payment calling = fixture.payment();
		calling.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
		paymentPersistence.save(calling);

		reconcilePaymentUseCase.reconcile();

		// 요청 흐름이 아직 그 호출을 쥐고 있을 수 있어, 집으면 같은 결제에 승인이 겹쳐 나간다.
		then(paymentGatewayPort).should(never()).readHistory(any(Payment.class), any(PgHistoryScope.class));
		assertThat(reload(fixture).getReconcileCount()).isZero();
	}

	@DisplayName("종착에 이른 결제는 대사가 다시 보지 않아 상태가 그대로다")
	@Test
	void reconcile_whenPaymentAlreadySettled_leavesItUntouched() {
		Fixture succeeded = unknownPayment();
		Payment done = succeeded.payment();
		done.succeed(succeeded.amount(), PG_TRANSACTION_ID);
		paymentPersistence.save(done);

		Fixture failed = unknownPayment();
		Payment closed = failed.payment();
		closed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentPersistence.save(closed);

		reconcilePaymentUseCase.reconcile();

		then(paymentGatewayPort).should(never()).readHistory(any(Payment.class), any(PgHistoryScope.class));
		assertThat(reload(succeeded).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(reload(failed).getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	@DisplayName("이력 조회가 거절되면 확정하지도 승인을 다시 부르지도 않는다")
	@Test
	void reconcile_whenHistoryReadRejected_confirmsNothing() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.failed(PgOutcome.RETRYABLE_FAILURE, "인증 거절"));

		reconcilePaymentUseCase.reconcile();

		// 조회가 거절된 것은 그 거래가 없다는 뜻이 아니라 우리가 제대로 묻지 못했다는 뜻이다.
		then(paymentGatewayPort).should(never()).approve(any(Payment.class));
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(reload(fixture).getCloseCode()).isNull();
	}

	// ── 이력에 없으면 승인을 다시 부른다 ─────────────────────────

	@DisplayName("이력이 비어 있으면 그 자리에서 승인을 다시 불러 닿지 않았던 요청을 살린다")
	@Test
	void reconcile_whenHistoryIsEmpty_requestsApprovalAgainAndConfirms() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenApproveSucceeded(fixture);

		reconcilePaymentUseCase.reconcile();

		Payment settled = reload(fixture);
		assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(orderPersistence.getOrderStatusById(fixture.order().getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("결과를 모르는 동안 승인 재요청이 같은 멱등키로 나가고 부른 시각은 갱신된다")
	@Test
	void reconcile_whenRequestingApprovalAgain_keepsIdempotencyKeyAndStampsRequestedAt() {
		Fixture fixture = unknownPayment();
		LocalDateTime firstCall = reload(fixture).getLastRequestedAt();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.unsettled("이미 진행 중인 결제", respondedRecord("AlreadyOnGoing")));

		reconcilePaymentUseCase.reconcile();

		Payment payment = reload(fixture);
		// 시도 번호가 그대로라 결제사가 저장해 둔 응답을 되돌려주고, 이미 나간 승인이 또 나가지 않는다.
		assertThat(payment.getAttemptSeq()).isEqualTo(1);
		assertThat(onlyCallLog().getPgIdempotencyKey()).isEqualTo(payment.getPaymentKey() + "-1");
		// 안 찍으면 다음 주기가 아직 부르는 중인 건과 구분하지 못한다.
		assertThat(payment.getLastRequestedAt()).isAfter(firstCall);
	}

	@DisplayName("승인 재요청에 승인 가능 시간이 지났다고 답하면 결제를 실패로 확정하고 슬롯을 반납한다")
	@Test
	void reconcile_whenApprovalWindowClosed_closesPaymentAndReleasesSlot() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.approvalWindowClosed("승인 가능 시간 초과", respondedRecord("TimeExpired")));

		reconcilePaymentUseCase.reconcile();

		Payment closed = reload(fixture);
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.UNCONFIRMED_CLOSED);
		// 슬롯이 풀려야 회원이 그 주문을 다시 결제할 수 있다.
		assertThat(closed.getActiveOrderKey()).isNull();
		// 승인이 성립한 적이 없어 되돌릴 것이 없다.
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("이번 승인 재요청이 실패했다는 답만으로는 결제를 확정하지 않는다")
	@Test
	void reconcile_whenThisCallFailed_leavesPaymentUnsettled() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.terminalFailure(true, "본인 카드 인증 실패", respondedRecord("OwnerAuthFail")));

		reconcilePaymentUseCase.reconcile();

		Payment payment = reload(fixture);
		// 이번 호출에 대한 답이지 앞선 호출에 대한 답이 아니다.
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(payment.getCloseCode()).isNull();
	}

	@DisplayName("다시 시도할 수 있는 실패를 받으면 상태를 그대로 두고 시도 번호만 올린다")
	@Test
	void reconcile_whenRetryableFailure_raisesAttemptSeqOnly() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		given(paymentGatewayPort.approve(any(Payment.class)))
			.willReturn(PgApproveResult.retryableFailure(true, "서비스 점검 중", respondedRecord("MaintenanceOngoing")));

		reconcilePaymentUseCase.reconcile();

		Payment payment = reload(fixture);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		// 다음 호출이 새 키로 나가야 결제사가 저장해 둔 실패 응답을 되풀이하지 않는다.
		assertThat(payment.getAttemptSeq()).isEqualTo(2);
	}

	// ── 이력이 우리 것이 아닌 사건을 보일 때 ─────────────────────

	@DisplayName("이력이 우리 것이 아닌 성공한 취소를 보이면 결제를 종결하고 환불을 만들지 않는다")
	@Test
	void reconcile_whenHistoryShowsForeignCancel_closesWithoutOpeningRefund() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(
			approvalEntry(fixture, fixture.amount()),
			refundEntry(fixture.amount(), true, null)
		), "성공"));

		reconcilePaymentUseCase.reconcile();

		Payment closed = reload(fixture);
		assertThat(closed.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(closed.getCloseCode()).isEqualTo(PaymentCloseCode.EXTERNALLY_CANCELED);
		// 승인이 났었다는 사실은 그 행만 보고도 읽혀야 한다.
		assertThat(closed.getApprovedAmount()).isEqualTo(fixture.amount());
		assertThat(closed.getActiveOrderKey()).isNull();
		// 돈이 이미 돌아갔으므로 되돌릴 것이 없다.
		assertThat(refundPersistence.findAll()).isEmpty();
		// 되돌릴 것이 없고 조사만 남는 일이라 그 자리에서 한 번 알린다.
		then(notificationPort).should(times(1))
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("이력의 취소가 실패한 시도뿐이면 그것을 근거로 결제를 닫지 않는다")
	@Test
	void reconcile_whenHistoryCancelFailed_doesNotClosePayment() {
		Fixture fixture = unknownPayment();
		givenHistory(PgHistoryResult.succeeded(List.of(
			approvalEntry(fixture, fixture.amount()),
			refundEntry(fixture.amount(), false, null)
		), "성공"));

		reconcilePaymentUseCase.reconcile();

		// 실패한 취소를 근거로 닫으면 돈이 나가 있는 결제가 슬롯을 반납해 회원이 다시 결제할 때 두 번 나간다.
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("이력의 승인에 남의 결제 키가 실려 있으면 우리 결제를 종결하고 그 주인의 결제를 회수한다")
	@Test
	void reconcile_whenHistoryApprovalBelongsToAnother_closesOursAndReclaimsCounterpart() {
		Fixture fixture = unknownPayment();
		Fixture counterpart = readyPayment();
		givenHistory(PgHistoryResult.succeeded(
			List.of(approvalEntry(counterpart, counterpart.amount())), "성공"));

		reconcilePaymentUseCase.reconcile();

		Payment ours = reload(fixture);
		assertThat(ours.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(ours.getCloseCode()).isEqualTo(PaymentCloseCode.PAYMENT_KEY_MISMATCH);

		Payment reclaimed = reload(counterpart);
		assertThat(reclaimed.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(reclaimed.getPgPaymentId()).isEqualTo(PG_PAYMENT_ID);
		then(notificationPort).should()
			.notifyManualReviewRequired(any(Long.class), any(String.class), any(String.class));
	}

	@DisplayName("회수 저장이 실패하면 우리 결제의 종결도 함께 없던 일이 된다")
	@Test
	void reconcile_whenReclaimSaveFails_rollsBackOurCloseToo() {
		Fixture fixture = unknownPayment();
		Fixture counterpart = readyPayment();
		givenHistory(PgHistoryResult.succeeded(
			List.of(approvalEntry(counterpart, counterpart.amount())), "성공"));
		willThrow(new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED))
			.given(paymentRepository)
			.saveChecked(argThat(payment -> counterpart.payment().getId().equals(payment.getId())));

		reconcilePaymentUseCase.reconcile();

		Payment ours = reload(fixture);
		assertThat(ours.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
		assertThat(ours.getCloseCode()).isNull();
		// 그 행에 남은 결제사 번호로 다음 주기가 같은 판정을 다시 한다.
		assertThat(ours.getPgPaymentId()).isEqualTo(PG_PAYMENT_ID);
		assertThat(reload(counterpart).getStatus()).isEqualTo(PaymentStatus.READY);
	}

	// ── 집었다는 기록 ────────────────────────────────────────────

	@DisplayName("집었다는 기록은 결제사를 부르기 전에 따로 커밋되어 호출이 깨져도 남는다")
	@Test
	void reconcile_whenGatewayCallBreaks_keepsThePickRecord() {
		Fixture fixture = unknownPayment();
		given(paymentGatewayPort.readHistory(any(Payment.class), any(PgHistoryScope.class)))
			.willThrow(new IllegalStateException("이력 조회 중 끊김"));

		reconcilePaymentUseCase.reconcile();

		Payment payment = reload(fixture);
		// 함께 롤백되면 회차가 오르지 않아 다시 집는 간격이 첫 값에 머물고, 장애가 길어질수록 더 세게 두드린다.
		assertThat(payment.getReconcileCount()).isEqualTo(1);
		assertThat(payment.getLastReconcileAt()).isNotNull();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("여러 번 집힌 결제도 통지 시간을 넘긴 뒤까지 계속 대사 대상이 된다")
	@Test
	void reconcile_whenPickedManyTimes_keepsRecovering() {
		Fixture fixture = unknownPayment();
		Payment stale = fixture.payment();
		for (int round = 0; round < 12; round++) {
			stale.recordReconciled(LocalDateTime.now().minusHours(6));
		}
		stale.recordNotified(LocalDateTime.now().minusHours(3));
		paymentPersistence.save(stale);
		givenHistory(PgHistoryResult.succeeded(List.of(approvalEntry(fixture, fixture.amount())), "성공"));

		reconcilePaymentUseCase.reconcile();

		// 멈추면 그 결제가 활성 슬롯을 쥔 채 남아 그 주문을 영영 결제할 수 없다.
		assertThat(reload(fixture).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	// ── 픽스처 ───────────────────────────────────────────────────

	private Payment reload(Fixture fixture) {
		return paymentPersistence.findById(fixture.payment().getId()).orElseThrow();
	}

	private PgCallLog onlyCallLog() {
		List<PgCallLog> callLogs = pgCallLogPersistence.findAll();
		assertThat(callLogs).hasSize(1);
		return callLogs.get(0);
	}

	private void givenHistory(PgHistoryResult history) {
		given(paymentGatewayPort.readHistory(any(Payment.class), any(PgHistoryScope.class))).willReturn(history);
	}

	private void givenApproveSucceeded(Fixture fixture) {
		given(paymentGatewayPort.approve(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			return PgApproveResult.succeeded(
				fixture.payment().getPaymentKey(), String.valueOf(payment.getMemberId()),
				fixture.amount(), PG_TRANSACTION_ID, "성공", respondedRecord("Success"));
		});
	}

	private PgHistoryEntry approvalEntry(Fixture fixture, int amount) {
		return new PgHistoryEntry(PgHistoryEntryType.APPROVAL, true, amount, LocalDateTime.now(),
			fixture.payment().getPaymentKey(), null, PG_TRANSACTION_ID);
	}

	private PgHistoryEntry refundEntry(int amount, boolean succeeded, String refundAttemptKey) {
		return new PgHistoryEntry(PgHistoryEntryType.REFUND, succeeded, amount, LocalDateTime.now(),
			null, refundAttemptKey, "hist-2");
	}

	private PgCallRecord respondedRecord(String resultCode) {
		return new PgCallRecord(PgErrorType.NONE, resultCode, 200, "{\"code\":\"" + resultCode + "\"}");
	}

	/** 승인을 부르고 응답을 못 받은 채 대사 유예를 넘긴 결제 */
	private Fixture unknownPayment() {
		Fixture fixture = readyPayment();
		Payment payment = fixture.payment();
		payment.markInProgress(PG_PAYMENT_ID, LocalDateTime.now().minusMinutes(5));
		payment.markUnknown();
		return new Fixture(fixture.member(), fixture.order(), paymentPersistence.save(payment));
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
			Member.createUser("reconcile-" + (++uniqueSuffix) + "@example.com", "password123", "recon-user"));
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
