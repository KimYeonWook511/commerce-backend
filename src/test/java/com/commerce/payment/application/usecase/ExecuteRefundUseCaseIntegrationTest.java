package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.PgCallType;
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
 * 환불 하나를 보내는 흐름이 부르기 직전 전이·호출 기록·결과 반영을 정해진 순서로 커밋하는지 실제 DB
 * 위에서 확인한다. 커밋 경계와 낙관 락이 이 흐름의 방어라 대역으로는 재현되지 않는다.
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
class ExecuteRefundUseCaseIntegrationTest {

	private static final int AMOUNT = 10_000;
	private static final String PG_TRANSACTION_ID = "pg-cancel-tx-1";

	@Autowired
	private ExecuteRefundUseCase executeRefundUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@MockitoSpyBean
	private PgCallLogService pgCallLogService;

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

	@DisplayName("환불이 성공하면 결제사 취소 거래 번호가 남고 사건이 성공으로 끝난다")
	@Test
	void send_whenGatewaySucceeded_completesRefund() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.succeeded(PG_TRANSACTION_ID, "성공", callRecord(PgErrorType.NONE)));

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.SUCCEEDED);
		Refund stored = reload(refund);
		assertThat(stored.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(stored.getPgTransactionId()).isEqualTo(PG_TRANSACTION_ID);
	}

	@DisplayName("결제사를 부른 사실이 그때 쓴 멱등키·요청 시각·받은 결과와 함께 기록에 쌓인다")
	@Test
	void send_whenCalled_writesCallLogWithKeyAndResult() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.succeeded(PG_TRANSACTION_ID, "성공", callRecord(PgErrorType.NONE)));

		executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		List<PgCallLog> logs = pgCallLogPersistence.findAll();
		assertThat(logs).hasSize(1);
		PgCallLog log = logs.get(0);
		assertThat(log.getCallType()).isEqualTo(PgCallType.REFUND);
		assertThat(log.getPaymentId()).isEqualTo(payment.getId());
		assertThat(log.getRefundId()).isEqualTo(refund.getId());
		assertThat(log.getPgIdempotencyKey()).isEqualTo(reload(refund).getPgIdempotencyKey());
		assertThat(log.getRequestedAt()).isNotNull();
		assertThat(log.getRespondedAt()).isNotNull();
		assertThat(log.getResultCode()).isEqualTo("Success");
	}

	@DisplayName("응답을 못 받아도 환불 사건은 남고 결과 불명이 된다")
	@Test
	void send_whenNotAnswered_keepsRefundAndMarksUnknown() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.unanswered("응답 없음", callRecord(PgErrorType.TIMEOUT)));

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.UNKNOWN);
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
		// 응답을 못 받은 호출은 응답 시각이 비어 그 사실이 기록에 남는다.
		assertThat(pgCallLogPersistence.findAll().get(0).getRespondedAt()).isNull();
	}

	@DisplayName("다시 시도할 수 있는 실패를 받으면 사건은 진행 중에 머물고 다음 호출이 새 키로 나간다")
	@Test
	void send_whenRetryableFailure_keepsStatusAndOpensNextAttempt() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.retryableFailure(true, "앞선 취소가 처리 중", callRecord(PgErrorType.NONE)));

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.IN_PROGRESS);
		Refund stored = reload(refund);
		// 앞 건이 끝나면 그대로 성공할 건이라 실패로 종결하지 않는다.
		assertThat(stored.getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(stored.getAttemptSeq()).isEqualTo(2);
		assertThat(stored.getPgIdempotencyKey()).endsWith("-2");
	}

	@DisplayName("다시 시도할 수 없는 실패를 받으면 검토 코드와 함께 사람이 처리해야 하는 상태가 된다")
	@Test
	void send_whenTerminalFailure_flagsForReview() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.terminalFailure(
			RefundReviewCode.CANCEL_DEADLINE_EXPIRED, "취소 기한 만료", callRecord(PgErrorType.NONE)));

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.MANUAL_REVIEW);
		Refund stored = reload(refund);
		assertThat(stored.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
		assertThat(stored.getReviewCode()).isEqualTo(RefundReviewCode.CANCEL_DEADLINE_EXPIRED);
	}

	@DisplayName("부르기 전과 부른 뒤가 상태로 갈리고, 한 번도 안 부른 건은 시도 번호가 0이다")
	@Test
	void send_whenFirstDispatch_movesFromRequestedAndRaisesAttemptSeq() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
		assertThat(refund.getAttemptSeq()).isZero();
		givenRefundResult(PgRefundResult.unanswered("응답 없음", callRecord(PgErrorType.TIMEOUT)));

		executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		Refund stored = reload(refund);
		assertThat(stored.getAttemptSeq()).isEqualTo(1);
		assertThat(stored.getLastRequestedAt()).isNotNull();
		// 한 번도 안 부른 건은 이력을 읽지 않고 바로 나간다.
		then(paymentGatewayPort).should(never()).readHistory(any(), any());
	}

	@DisplayName("보낼 차례가 아닌 환불은 결제사를 부르지 않는다")
	@Test
	void send_whenAlreadyInProgress_doesNotCallGateway() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		refund.markInProgress(LocalDateTime.now());
		Refund inProgress = refundPersistence.save(refund);

		RefundStatus status = executeRefundUseCase.send(payment, inProgress, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.IN_PROGRESS);
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
		assertThat(pgCallLogPersistence.findAll()).isEmpty();
	}

	@DisplayName("이미 나간 시도가 이력에 있으면 다시 보내지 않는다")
	@Test
	void send_whenAttemptAlreadyInHistory_doesNotResend() {
		Payment payment = savePayment();
		Refund refund = revivedRefund(payment);
		given(paymentGatewayPort.readHistory(any(), eq(PgHistoryScope.REFUND_ONLY)))
			.willReturn(PgHistoryResult.succeeded(
				List.of(refundEntry(refund.attemptKey())), "성공"));

		executeRefundUseCase.send(payment, refund, PgCallSource.BATCH);

		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.REQUESTED);
	}

	@DisplayName("이력에 그 사건의 시도가 없으면 보낸다")
	@Test
	void send_whenHistoryHasNoAttemptOfThisRefund_sends() {
		Payment payment = savePayment();
		Refund refund = revivedRefund(payment);
		given(paymentGatewayPort.readHistory(any(), eq(PgHistoryScope.REFUND_ONLY)))
			.willReturn(PgHistoryResult.succeeded(List.of(refundEntry("RF-someone-else-1")), "성공"));
		givenRefundResult(PgRefundResult.succeeded(PG_TRANSACTION_ID, "성공", callRecord(PgErrorType.NONE)));

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.BATCH);

		assertThat(status).isEqualTo(RefundStatus.SUCCEEDED);
	}

	@DisplayName("이력 조회가 거절되면 보내지 않는다")
	@Test
	void send_whenHistoryReadRejected_doesNotSend() {
		Payment payment = savePayment();
		Refund refund = revivedRefund(payment);
		given(paymentGatewayPort.readHistory(any(), eq(PgHistoryScope.REFUND_ONLY)))
			.willReturn(PgHistoryResult.failed(PgOutcome.RETRYABLE_FAILURE, "인증 거절"));

		executeRefundUseCase.send(payment, refund, PgCallSource.BATCH);

		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("재시도는 새 기록 행을 만들고 앞 행은 그대로 남는다")
	@Test
	void send_whenSentTwice_writesSeparateCallLogRows() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.retryableFailure(true, "점검 중", callRecord(PgErrorType.NONE)));
		executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		// 사람이 되살린 건이 다시 보내지는 자리를 대신해, 같은 사건을 한 번 더 발송 대상으로 만든다.
		Refund revived = reload(refund);
		revived.flagForReview(RefundReviewCode.REQUEST_REJECTED, "임시");
		refundPersistence.save(revived);
		Refund readyAgain = reload(refund);
		ReflectionTestUtils.setField(readyAgain, "status", RefundStatus.REQUESTED);
		refundPersistence.save(readyAgain);
		given(paymentGatewayPort.readHistory(any(), eq(PgHistoryScope.REFUND_ONLY)))
			.willReturn(PgHistoryResult.succeeded(List.of(), "성공"));

		executeRefundUseCase.send(payment, reload(refund), PgCallSource.BATCH);

		// 한 행이 한 호출을 나타내므로 몇 번 불렀는지가 행 수로 보인다.
		assertThat(pgCallLogPersistence.findAll()).hasSize(2);
	}

	@DisplayName("호출 기록을 남기지 못하면 결제사를 부르지 않고, 환불 판정도 흔들리지 않는다")
	@Test
	void send_whenCallLogFails_doesNotCallGatewayAndKeepsRefundIntact() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		willThrow(new IllegalStateException("기록 저장 실패"))
			.given(pgCallLogService).startRefundCall(any(), any(), any(), any());

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.IN_PROGRESS);
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
		// 부르기 직전 전이는 이미 커밋됐고 금액도 그대로다. 그 건은 대사가 이력을 읽어 회수한다.
		Refund stored = reload(refund);
		assertThat(stored.getStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(stored.getAmount()).isEqualTo(AMOUNT);
		assertThat(paymentPersistence.findById(payment.getId()).orElseThrow().getTotalRefundedAmount())
			.isEqualTo(AMOUNT);
	}

	@DisplayName("호출 결과를 기록하지 못해도 환불 판정은 그대로 확정된다")
	@Test
	void send_whenResultLogFails_stillCompletesRefund() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.succeeded(PG_TRANSACTION_ID, "성공", callRecord(PgErrorType.NONE)));
		willThrow(new IllegalStateException("기록 저장 실패"))
			.given(pgCallLogService).recordResult(any(), any(), any());

		RefundStatus status = executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		assertThat(status).isEqualTo(RefundStatus.SUCCEEDED);
		assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
	}

	@DisplayName("회원 요청 흐름과 배치가 서로 다른 제한 시간으로 부른다는 사실이 호출에 실린다")
	@Test
	void send_whenCalled_passesCallSourceToGateway() {
		Payment payment = savePayment();
		Refund refund = saveRefund(payment);
		givenRefundResult(PgRefundResult.succeeded(PG_TRANSACTION_ID, "성공", callRecord(PgErrorType.NONE)));

		executeRefundUseCase.send(payment, refund, PgCallSource.MEMBER_REQUEST);

		then(paymentGatewayPort).should().refund(any(), any(), eq(PgCallSource.MEMBER_REQUEST));
	}

	// ── 헬퍼 ──

	private void givenRefundResult(PgRefundResult result) {
		given(paymentGatewayPort.refund(any(), any(), any())).willReturn(result);
	}

	private PgCallRecord callRecord(PgErrorType errorType) {
		return new PgCallRecord(errorType, errorType == PgErrorType.NONE ? "Success" : null,
			errorType == PgErrorType.NONE ? 200 : null, "{}");
	}

	private PgHistoryEntry refundEntry(String refundAttemptKey) {
		return new PgHistoryEntry(PgHistoryEntryType.REFUND, true, AMOUNT, LocalDateTime.now(),
			"PK-1", refundAttemptKey, PG_TRANSACTION_ID);
	}

	private Payment savePayment() {
		int suffix = ++uniqueSuffix;
		Payment payment = Payment.start(
			100L + suffix, 200L + suffix, PaymentPg.NAVERPAY, "PK-" + suffix, "idem-" + suffix, AMOUNT);
		payment.markInProgress("pg-payment-" + suffix, LocalDateTime.now());
		payment.succeed(AMOUNT, "pg-tx-" + suffix);
		return paymentPersistence.save(payment);
	}

	/** 환불을 만드는 관문은 결제 안에 있다. 누적 환불액이 오른 결제도 함께 저장한다 */
	private Refund saveRefund(Payment payment) {
		Refund refund = payment.openRefund(
			Optional.empty(), AMOUNT, RefundReason.ORDER_CANCELED, "IDEM-" + uniqueSuffix);
		Refund saved = refundPersistence.save(refund);
		paymentPersistence.save(payment);
		return saved;
	}

	/** 사람이 되살린 건. 부를 준비 상태이면서 시도 번호가 0이 아니라 이력을 먼저 읽어야 한다 */
	private Refund revivedRefund(Payment payment) {
		Refund refund = saveRefund(payment);
		refund.markInProgress(LocalDateTime.now());
		refund.flagForReview(RefundReviewCode.REQUEST_REJECTED, "임시");
		refundPersistence.save(refund);
		Refund revived = reload(refund);
		ReflectionTestUtils.setField(revived, "status", RefundStatus.REQUESTED);
		return refundPersistence.save(revived);
	}

	private Refund reload(Refund refund) {
		return refundPersistence.findAll().stream()
			.filter(candidate -> candidate.getId().equals(refund.getId()))
			.findFirst()
			.orElseThrow();
	}
}
