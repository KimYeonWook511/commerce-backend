package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결과를 모르는 환불을 대사가 어떻게 확정하고 어떤 조건에서 다시 보내는지 실제 DB 위에서 확인한다.
 * 대상 조회가 상태·집은 횟수·시각 위에 서 있고, 집었다는 기록이 결제사 호출과 다른 트랜잭션이라는 것도
 * 진짜 커밋 경계에서만 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class
})
class ReconcileRefundUseCaseIntegrationTest {

	private static final int AMOUNT = 10_000;
	private static final String HISTORY_TRANSACTION_ID = "hist-cancel-1";

	@Autowired
	private ReconcileRefundUseCase reconcileRefundUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

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
		persistenceCleanup.deleteAllInBatch(pgCallLogPersistence, refundPersistence, paymentPersistence);
	}

	// ── 이력으로 확정한다 ────────────────────────────────────────

	@DisplayName("이력에 우리 시도가 완료로 있으면 환불을 성공으로 확정하고 결제사 환불 번호를 남긴다")
	@Test
	void reconcile_whenHistoryHasOurSettledAttempt_completesWithTransactionId() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), true)), "성공"));

		reconcileRefundUseCase.reconcile();

		Refund settled = reload(refund);
		assertThat(settled.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		// 판정에 쓰지 않지만 정산 대조와 문의 조사 때 그 취소를 가리킬 값이 이것뿐이다.
		assertThat(settled.getPgTransactionId()).isEqualTo(HISTORY_TRANSACTION_ID);
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("결제사는 성공했는데 우리 저장이 못 따라간 환불을 대사가 이력에서 되찾는다")
	@Test
	void reconcile_whenResultWasNeverStored_recoversItFromHistory() {
		Payment payment = savePayment();
		// 결과를 기록하지 못했으니 상태가 바뀔 수 없어 응답을 기다리는 상태에 그대로 머문 건이다.
		Refund refund = inProgressRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), true)), "성공"));

		reconcileRefundUseCase.reconcile();

		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	@DisplayName("다른 사건의 항목은 판정에 넣지 않는다 — 사건 키 하나가 다른 키의 앞부분과 같아도 섞이지 않는다")
	@Test
	void reconcile_whenHistoryHasAnotherEventsAttempt_doesNotConfirmThisOne() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		String lookalike = refund.getRefundKey() + "9-1";
		givenHistory(PgHistoryResult.succeeded(List.of(
			refundEntry(lookalike, true), refundEntry("RF-someone-else-1", true)), "성공"));
		givenRefundResult(PgRefundResult.unanswered("응답 없음", callRecord(PgErrorType.TIMEOUT)));

		reconcileRefundUseCase.reconcile();

		// 남의 사건을 우리 것으로 읽으면 나가지 않은 돈을 나간 것으로 확정한다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
		then(paymentGatewayPort).should().refund(any(), any(), eq(PgCallSource.BATCH));
	}

	@DisplayName("이력의 우리 항목이 실패뿐이면 성공으로 확정하지 않는다")
	@Test
	void reconcile_whenOurAttemptFailedInHistory_doesNotComplete() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), false)), "성공"));
		givenRefundResult(PgRefundResult.unanswered("응답 없음", callRecord(PgErrorType.TIMEOUT)));

		reconcileRefundUseCase.reconcile();

		// 실패한 시도도 이력에 한 줄로 남으므로 존재만으로 완료를 단정하지 않는다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	@DisplayName("이력 조회가 거절되면 확정하지도 다시 보내지도 않는다")
	@Test
	void reconcile_whenHistoryReadRejected_neitherConfirmsNorResends() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.failed(PgOutcome.RETRYABLE_FAILURE, "인증 거절"));

		reconcileRefundUseCase.reconcile();

		// 묻지 못한 것을 "없다"로 읽으면 이중환불을 막던 확인이 통째로 무력해진다.
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	// ── 이력에 없으면 같은 키로 그 자리에서 다시 보낸다 ──────────

	@DisplayName("이력에 우리 시도가 없으면 시도 번호를 올리지 않고 같은 멱등키로 다시 부른다")
	@Test
	void reconcile_whenHistoryHasNoAttempt_resendsWithTheSameIdempotencyKey() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		String keyBefore = refund.getPgIdempotencyKey();
		LocalDateTime requestedBefore = refund.getLastRequestedAt();
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenRefundResult(PgRefundResult.succeeded("pg-cancel-1", "성공", callRecord(PgErrorType.NONE)));

		reconcileRefundUseCase.reconcile();

		Refund resent = reload(refund);
		// 새 키로 보내면 완료된 취소 위에 또 하나가 실행되고, 부분환불이면 잔액이 남아 거절되지도 않는다.
		assertThat(resent.getAttemptSeq()).isEqualTo(1);
		assertThat(pgCallLogPersistence.findAll()).singleElement()
			.satisfies(log -> assertThat(log.getPgIdempotencyKey()).isEqualTo(keyBefore));
		// 안 찍으면 다른 주기가 방금 보낸 건을 또 집는다.
		assertThat(resent.getLastRequestedAt()).isAfter(requestedBefore);
		assertThat(resent.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	@DisplayName("이력에 실패로 있고 사유를 모르면 같은 멱등키로 불러 되돌아오는 이전 응답에서 사유를 얻는다")
	@Test
	void reconcile_whenHistoryHasFailedAttempt_resendsWithTheSameIdempotencyKey() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		String keyBefore = refund.getPgIdempotencyKey();
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), false)), "성공"));
		givenRefundResult(PgRefundResult.retryableFailure(true, "서비스 점검 중", callRecord(PgErrorType.NONE)));

		reconcileRefundUseCase.reconcile();

		// 이력에는 성공·실패만 담겨 왜 실패했는지가 없어, 그 사유를 되돌아오는 응답이 알려준다.
		assertThat(pgCallLogPersistence.findAll()).singleElement()
			.satisfies(log -> assertThat(log.getPgIdempotencyKey()).isEqualTo(keyBefore));
		// 사유를 알았으니 그때부터 새 키다.
		assertThat(reload(refund).getAttemptSeq()).isEqualTo(2);
	}

	@DisplayName("다시 보낼 때 상태를 되돌리지 않아 이력 확인이 낡지 않는다")
	@Test
	void reconcile_whenResending_keepsStatusInsteadOfRewindingIt() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenRefundResult(PgRefundResult.unanswered("응답 없음", callRecord(PgErrorType.TIMEOUT)));

		reconcileRefundUseCase.reconcile();

		// 되돌렸다가 나중에 보내면 그 사이 결제사가 이력에 반영할 수 있어 보내기 직전에 또 읽어야 한다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	@DisplayName("다시 시도할 수 없는 실패를 받으면 검토 코드를 채우되 한도에서 풀어 주지 않는다")
	@Test
	void reconcile_whenTerminalFailure_flagsForReviewAndKeepsTheAmountCounted() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenRefundResult(PgRefundResult.terminalFailure(
			RefundReviewCode.CANCEL_DEADLINE_EXPIRED, "취소 기한 만료", callRecord(PgErrorType.NONE)));

		reconcileRefundUseCase.reconcile();

		Refund flagged = reload(refund);
		// 자동으로 갈 수 있는 종착은 성공 하나뿐이라, 이 상태는 종결이 아니라 사람이 이어받아야 한다는 뜻이다.
		assertThat(flagged.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
		assertThat(flagged.getReviewCode()).isEqualTo(RefundReviewCode.CANCEL_DEADLINE_EXPIRED);
		// 자동으로 못 푼다고 몫을 풀어 주면 아직 돈이 안 돌아간 사건이 미결인 채 새 환불이 끼어든다.
		assertThat(reloadPayment(payment).getTotalRefundedAmount()).isEqualTo(AMOUNT);
	}

	@DisplayName("다시 시도할 수 있는 실패를 받으면 상태를 그대로 두고 다음 호출이 새 키로 나가게 한다")
	@Test
	void reconcile_whenRetryableFailure_keepsStatusAndOpensNextAttempt() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenRefundResult(PgRefundResult.retryableFailure(true, "앞선 취소가 처리 중", callRecord(PgErrorType.NONE)));

		reconcileRefundUseCase.reconcile();

		Refund stored = reload(refund);
		assertThat(stored.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
		assertThat(stored.getAttemptSeq()).isEqualTo(2);
	}

	@DisplayName("다른 도메인에 정당성을 묻지 않고 환불 행이 있으면 그대로 집행한다")
	@Test
	void reconcile_whenRefundRowExists_executesWithoutAskingAnotherDomain() {
		// 이 결제가 가리키는 주문 행은 존재하지 않는다. 집행 전에 주문 상태를 되묻는 경로가 있었다면
		// 여기서 멈췄을 것이고, 행이 있다는 것만으로 집행한다는 규칙이 그 경로를 두지 않은 결과다.
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		givenHistory(PgHistoryResult.succeeded(List.of(), "성공"));
		givenRefundResult(PgRefundResult.succeeded("pg-cancel-1", "성공", callRecord(PgErrorType.NONE)));

		reconcileRefundUseCase.reconcile();

		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	// ── 무엇을 집고 무엇을 안 집나 ───────────────────────────────

	@DisplayName("결과가 이미 정해진 환불은 대사가 다시 보지 않아 상태가 그대로다")
	@Test
	void reconcile_whenRefundAlreadySettled_leavesItUntouched() {
		Refund succeeded = inProgressRefund(savePayment());
		succeeded.complete("pg-cancel-done");
		refundPersistence.save(succeeded);

		Refund review = inProgressRefund(savePayment());
		review.flagForReview(RefundReviewCode.CANCEL_NOT_ALLOWED, "취소 불가");
		refundPersistence.save(review);

		reconcileRefundUseCase.reconcile();

		then(paymentGatewayPort).should(never()).readHistory(any(), any(), any());
		assertThat(reload(succeeded).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(reload(review).getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
	}

	@DisplayName("방금 다시 보낸 환불은 대사 유예 안이라 여러 주기를 지난 건이어도 집지 않는다")
	@Test
	void reconcile_whenRefundWasJustResent_isNotPickedAgain() {
		Payment payment = savePayment();
		Refund refund = inProgressRefund(payment);
		// 여러 주기를 지나 이미 오래된 행이지만, 마지막으로 부른 것은 방금이다.
		for (int round = 0; round < 5; round++) {
			refund.recordReconciled(LocalDateTime.now().minusHours(1));
		}
		refund.recordRequested(LocalDateTime.now());
		refundPersistence.save(refund);

		reconcileRefundUseCase.reconcile();

		// 행이 만들어진 시각으로 재면 다시 보낸 건이 보내는 즉시 지연을 넘긴 것으로 읽혀 계속 나간다.
		then(paymentGatewayPort).should(never()).readHistory(any(), any(), any());
		assertThat(reload(refund).getReconcileCount()).isEqualTo(5);
	}

	@DisplayName("집었다는 기록은 결제사를 부르기 전에 따로 커밋되어 호출이 깨져도 남는다")
	@Test
	void reconcile_whenGatewayCallBreaks_keepsThePickRecord() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		given(paymentGatewayPort.readHistory(any(), any(), any()))
			.willThrow(new IllegalStateException("이력 조회 중 끊김"));

		reconcileRefundUseCase.reconcile();

		Refund picked = reload(refund);
		// 함께 롤백되면 회차가 오르지 않아 다시 집는 간격이 첫 값에 머물고, 장애가 길어질수록 더 세게 두드린다.
		assertThat(picked.getReconcileCount()).isEqualTo(1);
		assertThat(picked.getLastReconcileAt()).isNotNull();
		assertThat(picked.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	@DisplayName("여러 번 집히고 통지까지 나간 환불도 계속 대사 대상이 된다")
	@Test
	void reconcile_whenPickedManyTimes_keepsRecovering() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		for (int round = 0; round < 12; round++) {
			refund.recordReconciled(LocalDateTime.now().minusHours(6));
		}
		refund.recordNotified(LocalDateTime.now().minusHours(3));
		refundPersistence.save(refund);
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), true)), "성공"));

		reconcileRefundUseCase.reconcile();

		// 멈추면 돈이 안 돌아간 채 남고 그 금액만큼 한도가 계속 막힌다.
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	@DisplayName("환불을 확정해도 결제 버전이 오르지 않아 회원의 환불 요청이 밀리지 않는다")
	@Test
	void reconcile_whenRefundSettled_doesNotBumpPaymentVersion() {
		Payment payment = savePayment();
		Refund refund = unknownRefund(payment);
		Long versionBefore = reloadPayment(payment).getVersion();
		givenHistory(PgHistoryResult.succeeded(List.of(refundEntry(refund.attemptKey(), true)), "성공"));

		reconcileRefundUseCase.reconcile();

		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		// 결제 버전이 오르면 대사가 한 바퀴 돌 때마다 회원의 환불 요청이 낙관 락 충돌로 밀린다.
		assertThat(reloadPayment(payment).getVersion()).isEqualTo(versionBefore);
	}

	// ── 헬퍼 ──

	private void givenHistory(PgHistoryResult history) {
		given(paymentGatewayPort.readHistory(any(), any(PgHistoryScope.class), any())).willReturn(history);
	}

	private void givenRefundResult(PgRefundResult result) {
		given(paymentGatewayPort.refund(any(), any(), any())).willReturn(result);
	}

	private PgHistoryEntry refundEntry(String refundAttemptKey, boolean succeeded) {
		return new PgHistoryEntry(PgHistoryEntryType.REFUND, succeeded, AMOUNT, LocalDateTime.now(),
			null, refundAttemptKey, HISTORY_TRANSACTION_ID);
	}

	private PgCallRecord callRecord(PgErrorType errorType) {
		return new PgCallRecord(errorType, errorType == PgErrorType.NONE ? "Success" : null,
			errorType == PgErrorType.NONE ? 200 : null, "{}");
	}

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			100L + suffix, 200L + suffix, PaymentPg.NAVERPAY, "PK-" + suffix, "idem-" + suffix, AMOUNT);
		payment.markInProgress("pg-payment-" + suffix, LocalDateTime.now());
		payment.succeed(AMOUNT, "pg-tx-" + suffix);
		return paymentPersistence.save(payment);
	}

	/** 결제사를 부르고 응답을 기다리는 환불. 부른 지 대사 유예가 지나 대상이 된다 */
	private Refund inProgressRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-" + uniqueSuffix + "-" + payment.getId());
		refund.markInProgress(LocalDateTime.now().minusMinutes(5));
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	/** 답을 못 받아 결과를 모르는 환불. 이 상태에는 대사 유예가 없다 */
	private Refund unknownRefund(Payment payment) {
		Refund refund = inProgressRefund(payment);
		refund.markUnknown();
		return refundPersistence.save(refund);
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
