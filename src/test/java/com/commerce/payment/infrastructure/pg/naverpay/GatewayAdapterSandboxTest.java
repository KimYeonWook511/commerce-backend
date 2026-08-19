package com.commerce.payment.infrastructure.pg.naverpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgCallSource;
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
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.infrastructure.pg.naverpay.client.GatewayClient;
import com.commerce.payment.infrastructure.pg.naverpay.client.GatewayExchange;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.ApprovalType;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.GatewayResponse;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.body.HistoryBody;
import com.commerce.payment.infrastructure.pg.naverpay.code.AdmissionTypeCode;
import com.commerce.payment.infrastructure.pg.naverpay.code.ApproveCode;
import com.commerce.payment.infrastructure.pg.naverpay.code.CancelCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 결제사 응답 코드를 우리 어휘로 옮기는 표가 실제 응답과 맞는지 확인한다. 다른 테스트는 결제사를
 * 흉내 낸 응답을 쓰므로, 명세를 잘못 읽었다면 그 흉내도 똑같이 틀린 채로 통과한다.
 *
 * <p>돌리는 때는 결제사 어댑터나 응답 코드 표를 고쳤을 때다. 실제 sandbox를 부르므로 CI에 넣지 않고
 * {@code ./gradlew sandboxTest}로 손으로 돌린다.
 *
 * <p>결제사가 결과를 확정해 주는 것(승인 성공·취소 성공)은 여기 없다. 결제창 인증을 갓 마친 결제번호가
 * 매번 있어야 하고 한 번 쓰면 그 번호를 다시 못 쓴다.
 *
 * <p>넷 중 둘은 결제번호가 필요 없어 언제든 돈다. 이력을 읽는 나머지 둘은 샌드박스 결제 데이터가 7일 뒤
 * 사라지므로, 그때마다 결제를 새로 만들어 번호를 채워야 한다.
 */
@Tag("sandbox")
@DisplayName("결제사 sandbox 응답")
class GatewayAdapterSandboxTest {

	/** 결제사 결제번호 형식과 달라 실재할 수 없는 값. 취소를 걸어도 되돌릴 돈이 없다 */
	private static final String UNKNOWN_PG_PAYMENT_ID = "sandbox-no-such-payment-id";


	/** 결제창에서 만든 결제의 금액. 부분취소 뒤 잔액이 남게 나눈다 */
	private static final int TOTAL_AMOUNT = 10_000;
	private static final int PARTIAL_REFUND_AMOUNT = 3_000;
	private static final int REST_REFUND_AMOUNT = TOTAL_AMOUNT - PARTIAL_REFUND_AMOUNT;

	private static final DateTimeFormatter ADMISSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final Set<String> KNOWN_ADMISSION_STATES = Set.of("SUCCESS", "FAIL");

	private final ObjectMapper objectMapper = new ObjectMapper();

	private GatewayClient client;
	private GatewayAdapter adapter;

	@BeforeEach
	void setUp() {
		client = client(requiredEnv("LOCAL_NAVERPAY_CLIENT_SECRET"));
		adapter = new GatewayAdapter(client, objectMapper);
	}

	@DisplayName("이력 항목의 종류·상태·시각이 전부 우리가 읽을 수 있는 값으로 온다")
	@Test
	void readHistory_whenEntriesReturned_carriesValuesWeCanRead() throws Exception {
		String historyUrl = requiredEnv("LOCAL_NAVERPAY_PAYMENT_HISTORY_URL");
		String pgPaymentId = shortLivedEnv("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID");

		GatewayExchange exchange = client.readHistory(pgPaymentId, ApprovalType.ALL, 1, PgCallSource.BATCH);

		assertThat(exchange.errorType())
			.as("이력 조회가 응답을 받지 못했다 url=%s", historyUrl)
			.isEqualTo(PgErrorType.NONE);

		// 어댑터는 모르는 종류 코드를 경고만 남기고 버리므로, 그 값이 온다는 사실은 원본을 봐야 드러난다.
		List<HistoryBody.History> entries = parseHistory(exchange).getBody().getList();
		assertThat(entries)
			.as("이력이 비었다. 결제번호가 7일이 지나 샌드박스에서 사라졌을 수 있다 pgPaymentId=%s", pgPaymentId)
			.isNotEmpty();
		assertThat(entries).allSatisfy(entry -> {
			assertThat(AdmissionTypeCode.from(entry.getAdmissionTypeCode()))
				.as("이력 항목 종류 코드 %s 를 우리 표가 모른다", entry.getAdmissionTypeCode())
				.isPresent();
			assertThat(entry.getAdmissionState())
				.as("이력 항목 상태 값 %s 를 우리가 성공·실패로 가르지 못한다", entry.getAdmissionState())
				.isIn(KNOWN_ADMISSION_STATES);
			assertThatCode(() -> LocalDateTime.parse(entry.getAdmissionYmdt(), ADMISSION_TIME_FORMAT))
				.as("이력 항목 시각 %s 를 우리 형식으로 읽지 못한다", entry.getAdmissionYmdt())
				.doesNotThrowAnyException();
		});
	}

	@DisplayName("어댑터가 이력 항목을 하나도 빠뜨리지 않고 옮긴다")
	@Test
	void readHistory_whenSucceeded_carriesEveryEntry() throws Exception {
		requiredEnv("LOCAL_NAVERPAY_PAYMENT_HISTORY_URL");
		String pgPaymentId = shortLivedEnv("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID");

		int totalCount = parseHistory(client.readHistory(pgPaymentId, ApprovalType.ALL, 1, PgCallSource.BATCH))
			.getBody().getTotalCount();

		PgHistoryResult result = adapter.readHistory(payment(pgPaymentId), PgHistoryScope.ALL, PgCallSource.BATCH);

		assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.entries())
			.as("결제사가 준 항목 수와 어댑터가 옮긴 항목 수가 다르다. 버려진 항목이 있으면 그만큼 돈을 못 본다")
			.hasSize(totalCount);
	}

	@DisplayName("인증이 거절되면 이력이 비었다는 답과 갈린다")
	@Test
	void readHistory_whenSecretRejected_doesNotLookLikeEmptyHistory() {
		requiredEnv("LOCAL_NAVERPAY_PAYMENT_HISTORY_URL");
		GatewayAdapter rejected = new GatewayAdapter(client("wrong-client-secret"), objectMapper);
		// 결제번호가 있으면 그것으로 묻는다. 인증이 먼저 걸리는지를 보는 것이라 없어도 확인은 성립한다.
		String pgPaymentId = env("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID").isBlank()
			? UNKNOWN_PG_PAYMENT_ID
			: env("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID");

		PgHistoryResult result = rejected.readHistory(payment(pgPaymentId), PgHistoryScope.ALL, PgCallSource.BATCH);

		assertThat(result.outcome())
			.as("거절이 성공으로 접혔다. 빈 목록과 갈리지 않으면 인증 설정이 틀린 순간 멀쩡한 결제가 전부 실패로 확정된다")
			.isNotEqualTo(PgOutcome.SUCCEEDED);
		assertThat(result.entries()).isEmpty();
	}

	@DisplayName("승인이 이미 끝난 결제는 결과 불명으로 접혀 이력으로 풀 길을 남긴다")
	@Test
	void approve_whenAlreadyComplete_leavesRoomToResolveFromHistory() {
		requiredEnv("LOCAL_NAVERPAY_APPROVAL_URL");
		String pgPaymentId = shortLivedEnv("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID");

		PgApproveResult result = adapter.approve(payment(pgPaymentId));

		assertThat(ApproveCode.from(result.callRecord().resultCode()))
			.as("승인 응답 코드 %s 를 우리 표가 모른다", result.callRecord().resultCode())
			.contains(ApproveCode.ALREADY_COMPLETE);
		assertThat(result.outcome())
			.as("승인이 끝난 결제를 실패로 접으면 나간 돈을 안 나간 것으로 다룬다 message=%s", result.message())
			.isEqualTo(PgOutcome.UNKNOWN);
		assertThat(result.answered())
			.as("답을 받았다는 사실이 없으면 대사가 이력을 읽지 않고 다음 주기로 미룬다")
			.isTrue();
	}

	@DisplayName("없는 결제번호로 취소를 걸면 사람이 조사할 근거를 붙여 종착 실패로 접는다")
	@Test
	void refund_whenPaymentIdUnknown_foldsToTerminalFailureWithReviewCode() {
		requiredEnv("LOCAL_NAVERPAY_CANCEL_URL");

		PgRefundResult result = adapter.refund(payment(UNKNOWN_PG_PAYMENT_ID), inProgressRefund(TOTAL_AMOUNT), PgCallSource.BATCH);

		assertThat(CancelCode.from(result.callRecord().resultCode()))
			.as("취소 응답 코드 %s 를 우리 표가 모른다", result.callRecord().resultCode())
			.isPresent();
		assertThat(result.outcome()).isEqualTo(PgOutcome.TERMINAL_FAILURE);
		assertThat(result.reviewCode()).isNotNull();
	}

	/**
	 * 결제창 인증을 갓 마친 결제 하나를 승인부터 잔액 환불까지 태우고 그 이력을 읽는다. 여기가 아니면
	 * 확인할 수 없는 것이 둘 있다 — 우리가 실어 보낸 환불 시도 키가 이력에 돌아오는가, 그리고 잔액을
	 * 남김없이 환불한 건이 이력에 어떤 종류로 남는가.
	 *
	 * <p>단계를 나눠 테스트를 여러 개로 쪼개지 않는다. 뒤 단계가 앞 단계의 결과 위에서만 성립하고 그
	 * 상태가 결제사 쪽에 있어, 순서가 보장되지 않으면 아무 의미가 없다.
	 *
	 * <p>한 번 돌리면 그 결제번호는 다 쓴 것이 된다. 대신 이력이 원결제·부분취소·잔액취소를 모두 갖게
	 * 되므로, 그 번호를 이력 조회용으로 옮겨 적으면 남은 기간 동안 종류 코드 확인에 쓸 수 있다.
	 */
	@DisplayName("승인부터 잔액 환불까지 태우면 우리 환불 시도 키가 이력에 그대로 돌아온다")
	@Test
	void paymentLifecycle_whenRefundedInParts_carriesOurAttemptKeysBackInHistory() {
		requiredEnv("LOCAL_NAVERPAY_APPROVAL_URL");
		requiredEnv("LOCAL_NAVERPAY_CANCEL_URL");
		requiredEnv("LOCAL_NAVERPAY_PAYMENT_HISTORY_URL");
		assumeTrue("true".equalsIgnoreCase(env("LOCAL_NAVERPAY_ENABLE_LIFECYCLE")),
			"LOCAL_NAVERPAY_ENABLE_LIFECYCLE 가 true 가 아니라 이 확인은 건너뛴다");
		String pgPaymentId = shortLivedEnv("LOCAL_NAVERPAY_LIFECYCLE_PAYMENT_ID");
		Payment payment = payment(pgPaymentId);

		PgApproveResult approved = adapter.approve(payment);

		assertThat(approved.outcome())
			.as("승인이 성립하지 않았다. 이미 승인했거나 승인 가능 시간이 지난 결제번호일 수 있다 message=%s",
				approved.message())
			.isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(approved.approvedAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(approved.pgTransactionId()).isNotBlank();

		Refund partial = inProgressRefund(PARTIAL_REFUND_AMOUNT);
		PgRefundResult partialResult = adapter.refund(payment, partial, PgCallSource.BATCH);

		assertThat(partialResult.outcome())
			.as("부분 환불이 성립하지 않았다 message=%s", partialResult.message())
			.isEqualTo(PgOutcome.SUCCEEDED);

		// 이력에서 그 시도를 못 찾은 대사가 하는 일과 같다 — 시도 번호를 올리지 않아 같은 키가 나간다.
		PgRefundResult resentResult = adapter.refund(payment, partial, PgCallSource.BATCH);

		assertThat(resentResult.outcome())
			.as("같은 키로 다시 보낸 환불이 성공으로 접히지 않았다. 이미 나간 환불을 못 알아본 것이다 message=%s",
				resentResult.message())
			.isEqualTo(PgOutcome.SUCCEEDED);

		// 앞선 취소가 이력에 반영되기 전에 다음 취소를 보내면 결제사가 앞 건이 처리 중이라며 거절한다.
		// 그 답도 옳은 것이라 여기서 실패로 다루면 매핑이 아니라 결제사 처리 속도를 재는 테스트가 된다.
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(2))
			.until(() -> adapter.readHistory(payment, PgHistoryScope.REFUND_ONLY, PgCallSource.BATCH)
				.settledRefundOf(partial).isPresent());

		Refund rest = inProgressRefund(REST_REFUND_AMOUNT);
		PgRefundResult restResult = adapter.refund(payment, rest, PgCallSource.BATCH);

		assertThat(restResult.outcome())
			.as("잔액 환불이 성립하지 않았다. 앞선 취소를 결제사가 아직 처리 중일 수 있다 message=%s",
				restResult.message())
			.isEqualTo(PgOutcome.SUCCEEDED);

		PgHistoryResult history = adapter.readHistory(payment, PgHistoryScope.ALL, PgCallSource.BATCH);

		assertThat(history.outcome()).isEqualTo(PgOutcome.SUCCEEDED);
		assertThat(history.settledRefundOf(partial))
			.as("부분 환불의 시도 키를 이력에서 찾지 못했다. 못 찾으면 환불이 확정되지 않고 이중환불도 못 막는다")
			.isPresent();
		assertThat(history.settledRefundOf(rest))
			.as("잔액 환불의 시도 키를 이력에서 찾지 못했다")
			.isPresent();
		assertThat(history.entries().stream().filter(entry -> entry.type() == PgHistoryEntryType.REFUND).toList())
			.as("같은 키로 다시 보낸 환불이 이력에 한 건 더 남았다. 그러면 돈이 두 번 나간 것이다")
			.hasSize(2);
	}

	private GatewayResponse<HistoryBody> parseHistory(GatewayExchange exchange) throws Exception {
		return objectMapper.readValue(exchange.rawBody(), new TypeReference<>() {});
	}

	private GatewayClient client(String clientSecret) {
		Properties properties = new Properties();
		ReflectionTestUtils.setField(properties, "clientId", requiredEnv("LOCAL_NAVERPAY_CLIENT_ID"));
		ReflectionTestUtils.setField(properties, "clientSecret", clientSecret);
		ReflectionTestUtils.setField(properties, "chainId", requiredEnv("LOCAL_NAVERPAY_CHAIN_ID"));
		ReflectionTestUtils.setField(properties, "returnUrl", env("LOCAL_NAVERPAY_RETURN_URL"));
		ReflectionTestUtils.setField(properties, "approvalUrl", env("LOCAL_NAVERPAY_APPROVAL_URL"));
		ReflectionTestUtils.setField(properties, "cancelUrl", env("LOCAL_NAVERPAY_CANCEL_URL"));
		ReflectionTestUtils.setField(properties, "historyUrl", env("LOCAL_NAVERPAY_PAYMENT_HISTORY_URL"));

		// 실제 통신이라 운영 기본값보다 길게 둔다. 끊겨서 나는 실패는 매핑에 대해 아무것도 알려주지 않는다.
		ClientConfig clientConfig = new ClientConfig();
		ReflectionTestUtils.setField(clientConfig, "connectTimeoutMillis", 5_000);
		ReflectionTestUtils.setField(clientConfig, "readTimeoutMillis", 20_000);
		ReflectionTestUtils.setField(clientConfig, "batchReadTimeoutMillis", 20_000);
		RestTemplate restTemplate = clientConfig.naverPayRestTemplate();
		RestTemplate batchRestTemplate = clientConfig.naverPayBatchRestTemplate();

		return new GatewayClient(properties, restTemplate, batchRestTemplate);
	}

	private Payment payment(String pgPaymentId) {
		// 결제 키는 실제로도 시도마다 새로 발급된다. 고정하면 호출 멱등키가 같아져 지난 실행의 응답이 재생된다.
		Payment payment = Payment.start(1L, 2L, PaymentPg.NAVERPAY,
			"sandbox-" + UUID.randomUUID(), "sandbox-idem-" + UUID.randomUUID(), TOTAL_AMOUNT);
		payment.markInProgress(pgPaymentId, LocalDateTime.now());
		return payment;
	}

	/**
	 * 사건 키를 매번 새로 뽑는다. 결제사가 이 값을 거래 고유 번호로 받으므로 지난 실행의 값을 다시 쓰면
	 * 그 자체로 거절될 수 있고, 이력에서 이번 시도를 집어낼 수도 없다.
	 */
	private Refund inProgressRefund(int amount) {
		Refund refund = Refund.open(7L, "RF-" + UUID.randomUUID().toString().replace("-", ""),
			RefundRequester.MEMBER, "sandbox-refund-idem-" + amount, amount, RefundReason.ORDER_CANCELED);
		refund.markInProgress(LocalDateTime.now());
		return refund;
	}

	/**
	 * 없으면 그 자리에서 실패한다. sandbox 테스트를 돌린 것은 확인하겠다는 뜻인데, 설정이 없다고 전부
	 * 건너뛰면 아무것도 안 부르고 빌드가 성공한다 — 확인이 안 됐다는 사실을 모르는 것이 가장 나쁘다.
	 */
	private String requiredEnv(String name) {
		String value = env(name);
		assertThat(value)
			.as("%s 가 없어 sandbox를 부르지 못했다. .env.sandbox 를 채우고 다시 돌린다", name)
			.isNotBlank();
		return value;
	}

	/** 없으면 그 확인만 건너뛴다. 샌드박스 결제 데이터는 7일 뒤 사라져 비어 있는 것이 정상일 수 있다 */
	private String shortLivedEnv(String name) {
		String value = env(name);
		assumeTrue(!value.isBlank(), name + " 가 없어 이 확인은 건너뛴다");
		return value;
	}

	private String env(String name) {
		String value = System.getenv(name);
		return value == null ? "" : value;
	}
}
