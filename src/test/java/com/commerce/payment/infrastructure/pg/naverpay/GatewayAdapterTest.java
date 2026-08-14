package com.commerce.payment.infrastructure.pg.naverpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.infrastructure.pg.naverpay.client.GatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("결제사 어댑터")
class GatewayAdapterTest {

	private static final String APPROVAL_URL = "https://test.pg/approve";
	private static final String HISTORY_URL = "https://test.pg/history/{paymentId}";
	private static final String PG_PAYMENT_ID = "pg-payment-1";
	private static final String RESOLVED_HISTORY_URL = "https://test.pg/history/" + PG_PAYMENT_ID;
	private static final String PAYMENT_KEY = "payment-key-1";

	private GatewayAdapter adapter;
	private MockRestServiceServer server;
	private Payment payment;

	@BeforeEach
	void setUp() {
		Properties properties = new Properties();
		ReflectionTestUtils.setField(properties, "clientId", "client-id");
		ReflectionTestUtils.setField(properties, "clientSecret", "client-secret");
		ReflectionTestUtils.setField(properties, "chainId", "chain-id");
		ReflectionTestUtils.setField(properties, "returnUrl", "https://test.pg/return");
		ReflectionTestUtils.setField(properties, "approvalUrl", APPROVAL_URL);
		ReflectionTestUtils.setField(properties, "historyUrl", HISTORY_URL);

		ClientConfig clientConfig = new ClientConfig();
		ReflectionTestUtils.setField(clientConfig, "connectTimeoutMillis", 1000);
		ReflectionTestUtils.setField(clientConfig, "readTimeoutMillis", 1000);
		RestTemplate restTemplate = clientConfig.pgNaverPayRestTemplate();

		server = MockRestServiceServer.bindTo(restTemplate).build();
		adapter = new GatewayAdapter(new GatewayClient(properties, restTemplate), new ObjectMapper());

		payment = Payment.start(1L, 2L, PaymentPg.NAVERPAY, PAYMENT_KEY, "idem-1", 10_000);
		payment.markInProgress(PG_PAYMENT_ID, LocalDateTime.now());
	}

	// ── 승인 호출 ────────────────────────────────────────────────

	@DisplayName("승인이 성립하면 승인 금액과 거래 번호를 함께 넘긴다")
	@Test
	void approve_whenSucceeded_carriesApprovedAmountAndTransactionId() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(approveSuccessResponse(), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);
		assertThat(result.approvedAmount()).isEqualTo(10_000);
		assertThat(result.pgTransactionId()).isEqualTo("hist-1");
	}

	@DisplayName("호출 결과에 응답 원본과 결과 코드와 HTTP 상태가 함께 실린다")
	@Test
	void approve_whenResponded_carriesRawResponseAndResultCodeAndHttpStatus() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveSuccessResponse(), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.callRecord().rawResponse()).isEqualTo(approveSuccessResponse());
		assertThat(result.callRecord().resultCode()).isEqualTo("Success");
		assertThat(result.callRecord().httpStatus()).isEqualTo(200);
		assertThat(result.callRecord().errorType()).isEqualTo(PgErrorType.NONE);
	}

	@DisplayName("승인 호출에 결제 키와 시도 번호로 만든 멱등키가 실린다")
	@Test
	void approve_whenCalled_sendsIdempotencyKeyDerivedFromPayment() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(header("X-NaverPay-Idempotency-Key", PAYMENT_KEY + "-1"))
			.andRespond(withSuccess(approveSuccessResponse(), MediaType.APPLICATION_JSON));

		adapter.approve(payment);

		server.verify();
	}

	@DisplayName("HTTP 성공 상태로 온 실패 코드를 실패로 다룬다")
	@Test
	void approve_whenFailureCodeArrivesWithSuccessStatus_foldsToFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveResponse("Fail"), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.TERMINAL_FAILURE);
		assertThat(result.callRecord().httpStatus()).isEqualTo(200);
	}

	@DisplayName("모르는 응답 코드는 실패가 아니라 결과 불명으로 접는다")
	@Test
	void approve_whenUnknownResultCode_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveResponse("SomethingNobodyKnows"), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	@DisplayName("이미 진행 중이라는 응답은 결과 불명으로 접는다")
	@Test
	void approve_whenAlreadyOnGoing_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveResponse("AlreadyOnGoing"), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	@DisplayName("승인 가능 시간이 지났다는 응답은 다시 시도할 수 없는 실패로 접는다")
	@Test
	void approve_whenTimeExpired_foldsToTerminalFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveResponse("TimeExpired"), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.TERMINAL_FAILURE);
	}

	@DisplayName("점검 중이라는 응답은 다시 시도할 수 있는 실패로 접는다")
	@Test
	void approve_whenMaintenanceOngoing_foldsToRetryableFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess(approveResponse("MaintenanceOngoing"), MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.RETRYABLE_FAILURE);
	}

	@DisplayName("서버 오류는 결과 불명으로 접는다")
	@Test
	void approve_whenServerError_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
		assertThat(result.callRecord().errorType()).isEqualTo(PgErrorType.HTTP);
		assertThat(result.callRecord().httpStatus()).isEqualTo(500);
	}

	@DisplayName("인증 거절은 다시 시도할 수 있는 실패로 접는다")
	@Test
	void approve_whenUnauthorized_foldsToRetryableFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.RETRYABLE_FAILURE);
	}

	@DisplayName("요청 제한은 다시 시도할 수 있는 실패로 접는다")
	@Test
	void approve_whenTooManyRequests_foldsToRetryableFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.RETRYABLE_FAILURE);
	}

	@DisplayName("그 밖의 요청 거절은 다시 시도할 수 없는 실패로 접는다")
	@Test
	void approve_whenBadRequest_foldsToTerminalFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.TERMINAL_FAILURE);
	}

	@DisplayName("중복 요청 응답은 상태 코드가 아니라 실려 온 본문으로 결과를 정한다")
	@Test
	void approve_whenDuplicateRequest_decidesByResponseBody() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body(approveSuccessResponse()));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.callRecord().httpStatus()).isEqualTo(409);
	}

	@DisplayName("중복 요청 응답에 본문이 없으면 실패가 아니라 결과 불명으로 접는다")
	@Test
	void approve_whenDuplicateRequestHasNoBody_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.CONFLICT));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
		assertThat(result.callRecord().httpStatus()).isEqualTo(409);
	}

	@DisplayName("중복 요청 응답의 본문이 비어 있어도 결과 불명으로 접는다")
	@Test
	void approve_whenDuplicateRequestBodyIsBlank_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body("   "));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	@DisplayName("중복 요청 응답에 실려 온 본문이 실패면 실패로 정한다")
	@Test
	void approve_whenDuplicateRequestBodyIsFailure_foldsToFailure() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body(approveResponse("Fail")));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.TERMINAL_FAILURE);
	}

	@DisplayName("연결에 실패하면 다시 시도할 수 있는 실패로 접고 연결 실패로 기록한다")
	@Test
	void approve_whenConnectionRefused_foldsToRetryableFailureWithConnectType() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withException(new ConnectException("connection refused")));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.RETRYABLE_FAILURE);
		assertThat(result.callRecord().errorType()).isEqualTo(PgErrorType.CONNECT);
		assertThat(result.callRecord().rawResponse()).isNull();
	}

	@DisplayName("응답을 읽지 못하고 끊기면 결과 불명으로 접고 시간 초과로 기록한다")
	@Test
	void approve_whenReadTimedOut_foldsToUnknownWithTimeoutType() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
		assertThat(result.callRecord().errorType()).isEqualTo(PgErrorType.TIMEOUT);
	}

	@DisplayName("응답 본문을 해석하지 못하면 결과 불명으로 접고 해석 실패로 기록한다")
	@Test
	void approve_whenBodyUnreadable_foldsToUnknownWithParseType() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess("not-a-json", MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
		assertThat(result.callRecord().errorType()).isEqualTo(PgErrorType.PARSE);
		assertThat(result.callRecord().rawResponse()).isEqualTo("not-a-json");
	}

	@DisplayName("성공 응답인데 본문이 비어 있으면 결과 불명으로 둔다")
	@Test
	void approve_whenSucceededWithoutDetail_foldsToUnknown() {
		server.expect(requestTo(APPROVAL_URL))
			.andRespond(withSuccess("{\"code\":\"Success\",\"message\":\"성공\"}", MediaType.APPLICATION_JSON));

		PgApproveResult result = adapter.approve(payment);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	// ── 이력 조회 ────────────────────────────────────────────────

	@DisplayName("부분취소로 기록된 항목을 환불로 읽는다")
	@Test
	void readHistory_whenPartialCancelEntry_readsAsRefund() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, partialCancelEntry("SUCCESS")), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.entries()).singleElement()
			.satisfies(entry -> {
				assertThat(entry.type()).isEqualTo(PgHistoryEntryType.REFUND);
				assertThat(entry.succeeded()).isTrue();
				assertThat(entry.amount()).isEqualTo(4_000);
			});
	}

	@DisplayName("전체취소로 기록된 항목도 환불로 읽는다")
	@Test
	void readHistory_whenFullCancelEntry_readsAsRefund() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, fullCancelEntry()), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.entries()).singleElement()
			.extracting(PgHistoryEntry::type).isEqualTo(PgHistoryEntryType.REFUND);
	}

	@DisplayName("목록 순서를 뒤집어도 같은 항목을 읽어 낸다")
	@Test
	void readHistory_whenListOrderReversed_readsSameEntries() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, approvalEntry(), partialCancelEntry("SUCCESS")),
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, partialCancelEntry("SUCCESS"), approvalEntry()),
				MediaType.APPLICATION_JSON));

		List<PgHistoryEntry> inOrder = adapter.readHistory(payment, PgHistoryScope.ALL).entries();
		List<PgHistoryEntry> reversed = adapter.readHistory(payment, PgHistoryScope.ALL).entries();

		assertThat(inOrder).containsExactlyInAnyOrderElementsOf(reversed);
		assertThat(inOrder).extracting(PgHistoryEntry::type)
			.containsExactlyInAnyOrder(PgHistoryEntryType.APPROVAL, PgHistoryEntryType.REFUND);
	}

	@DisplayName("첫 페이지를 넘는 이력도 끝까지 읽는다")
	@Test
	void readHistory_whenEntriesSpanPages_readsEveryPage() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andExpect(jsonPath("$.pageNumber").value(1))
			.andRespond(withSuccess(historyResponse(3, partialCancelEntry("FAIL")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andExpect(jsonPath("$.pageNumber").value(2))
			.andRespond(withSuccess(historyResponse(3, partialCancelEntry("FAIL")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andExpect(jsonPath("$.pageNumber").value(3))
			.andRespond(withSuccess(historyResponse(3, approvalEntry()), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		server.verify();
		assertThat(result.entries()).hasSize(3);
		assertThat(result.entries()).extracting(PgHistoryEntry::type)
			.contains(PgHistoryEntryType.APPROVAL);
	}

	@DisplayName("전체 페이지 수가 이상하게 커도 정해진 상한에서 멈춘다")
	@Test
	void readHistory_whenPageCountExceedsLimit_stopsAtLimit() {
		server.expect(ExpectedCount.times(20), requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(9_999, approvalEntry()), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		server.verify();
		assertThat(result.entries()).hasSize(20);
	}

	@DisplayName("환불 판정용 조회에는 취소 항목만 달라는 조건이 실린다")
	@Test
	void readHistory_whenRefundScope_requestsCancelEntriesOnly() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andExpect(jsonPath("$.approvalType").value("CANCEL"))
			.andRespond(withSuccess(historyResponse(1, partialCancelEntry("SUCCESS")), MediaType.APPLICATION_JSON));

		adapter.readHistory(payment, PgHistoryScope.REFUND_ONLY);

		server.verify();
	}

	@DisplayName("승인 판정용 조회는 항목을 걸러 받지 않는다")
	@Test
	void readHistory_whenAllScope_requestsEveryEntry() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andExpect(jsonPath("$.approvalType").value("ALL"))
			.andRespond(withSuccess(historyResponse(1, approvalEntry()), MediaType.APPLICATION_JSON));

		adapter.readHistory(payment, PgHistoryScope.ALL);

		server.verify();
	}

	@DisplayName("실패한 취소 항목은 성공하지 않은 것으로 읽는다")
	@Test
	void readHistory_whenCancelEntryFailed_marksEntryNotSucceeded() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, partialCancelEntry("FAIL")), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.entries()).singleElement()
			.extracting(PgHistoryEntry::succeeded).isEqualTo(false);
	}

	@DisplayName("취소 항목에 실린 우리 환불 시도 키를 그대로 넘긴다")
	@Test
	void readHistory_whenCancelEntryCarriesAttemptKey_passesItThrough() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, cancelEntryWith("refund-key-1-1")), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.entries()).singleElement()
			.extracting(PgHistoryEntry::refundAttemptKey).isEqualTo("refund-key-1-1");
	}

	@DisplayName("우리 시도 키가 실리지 않은 취소 항목도 걸러 내지 않고 넘긴다")
	@Test
	void readHistory_whenCancelEntryHasNoAttemptKey_stillPassesItThrough() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, partialCancelEntry("SUCCESS")), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.entries()).singleElement()
			.satisfies(entry -> {
				assertThat(entry.type()).isEqualTo(PgHistoryEntryType.REFUND);
				assertThat(entry.refundAttemptKey()).isNull();
			});
	}

	@DisplayName("승인 항목에 실린 결제 키와 처리 시각을 함께 넘긴다")
	@Test
	void readHistory_whenApprovalEntry_carriesPaymentKeyAndOccurredAt() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1, approvalEntry()), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.entries()).singleElement()
			.satisfies(entry -> {
				assertThat(entry.paymentKey()).isEqualTo(PAYMENT_KEY);
				assertThat(entry.occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 1, 2, 3));
				assertThat(entry.pgTransactionId()).isEqualTo("hist-1");
			});
	}

	@DisplayName("목록이 비어 있으면 성공으로 읽고 빈 목록을 넘긴다")
	@Test
	void readHistory_whenListEmpty_succeedsWithNoEntries() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess(historyResponse(1), MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.entries()).isEmpty();
	}

	@DisplayName("조회가 거절되면 빈 목록이 아니라 실패로 읽는다")
	@Test
	void readHistory_whenQueryRejected_foldsToFailureNotEmptyList() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withSuccess("{\"code\":\"InvalidMerchant\",\"message\":\"유효하지 않은 가맹점\"}",
				MediaType.APPLICATION_JSON));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.outcome()).isEqualTo(PgOutcome.RETRYABLE_FAILURE);
		assertThat(result.entries()).isEmpty();
	}

	@DisplayName("이력 조회가 서버 오류로 끝나면 결과 불명으로 읽는다")
	@Test
	void readHistory_whenServerError_foldsToUnknown() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	@DisplayName("이력 조회가 끊겨도 예외를 던지지 않고 갈래로 접는다")
	@Test
	void readHistory_whenConnectionFails_returnsOutcomeWithoutThrowing() {
		server.expect(requestTo(RESOLVED_HISTORY_URL))
			.andRespond(withException(new IOException("boom")));

		PgHistoryResult result = adapter.readHistory(payment, PgHistoryScope.ALL);

		assertThat(result.outcome()).isEqualTo(PgOutcome.UNKNOWN);
	}

	// ── 응답 조각 ────────────────────────────────────────────────

	private String approveSuccessResponse() {
		return """
			{"code":"Success","message":"성공","body":{"detail":{"paymentId":"pg-payment-1",\
			"payHistId":"hist-1","merchantPayKey":"payment-key-1","admissionState":"SUCCESS",\
			"admissionYmdt":"20260815010203","totalPayAmount":10000}}}""";
	}

	private String approveResponse(String code) {
		return "{\"code\":\"" + code + "\",\"message\":\"응답\"}";
	}

	private String historyResponse(int totalPageCount, String... entries) {
		return "{\"code\":\"Success\",\"message\":\"성공\",\"body\":{\"list\":["
			+ String.join(",", entries)
			+ "],\"totalCount\":" + entries.length
			+ ",\"responseCount\":" + entries.length
			+ ",\"totalPageCount\":" + totalPageCount
			+ ",\"currentPageNumber\":1}}";
	}

	private String approvalEntry() {
		return historyEntry("01", "SUCCESS", 10_000, PAYMENT_KEY, null);
	}

	private String fullCancelEntry() {
		return historyEntry("03", "SUCCESS", 10_000, PAYMENT_KEY, null);
	}

	private String partialCancelEntry(String admissionState) {
		return historyEntry("04", admissionState, 4_000, PAYMENT_KEY, null);
	}

	private String cancelEntryWith(String refundAttemptKey) {
		return historyEntry("04", "SUCCESS", 4_000, PAYMENT_KEY, refundAttemptKey);
	}

	private String historyEntry(
		String admissionTypeCode,
		String admissionState,
		int totalPayAmount,
		String merchantPayKey,
		String merchantPayTransactionKey
	) {
		String transactionKey = merchantPayTransactionKey == null ? "null" : "\"" + merchantPayTransactionKey + "\"";
		return "{\"paymentId\":\"" + PG_PAYMENT_ID + "\",\"payHistId\":\"hist-1\","
			+ "\"merchantPayKey\":\"" + merchantPayKey + "\","
			+ "\"merchantPayTransactionKey\":" + transactionKey + ","
			+ "\"admissionState\":\"" + admissionState + "\","
			+ "\"admissionTypeCode\":\"" + admissionTypeCode + "\","
			+ "\"admissionYmdt\":\"20260815010203\","
			+ "\"totalPayAmount\":" + totalPayAmount + "}";
	}
}
