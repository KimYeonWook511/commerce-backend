package com.commerce.payment.infrastructure.pg.naverpay;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.infrastructure.pg.naverpay.client.GatewayClient;
import com.commerce.payment.infrastructure.pg.naverpay.client.GatewayExchange;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.ApprovalType;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.CancelRequester;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.GatewayResponse;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.body.ApproveBody;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.body.CancelBody;
import com.commerce.payment.infrastructure.pg.naverpay.client.response.body.HistoryBody;
import com.commerce.payment.infrastructure.pg.naverpay.code.AdmissionTypeCode;
import com.commerce.payment.infrastructure.pg.naverpay.code.ApproveCode;
import com.commerce.payment.infrastructure.pg.naverpay.code.CancelCode;
import com.commerce.payment.infrastructure.pg.naverpay.code.HistoryCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제사에 보내는 요청과 그 응답의 해석이 사는 유일한 자리. 결제사 전용 타입에 대한 의존이 여기서
 * 끝나고, 밖으로는 접은 갈래와 우리 값으로 옮긴 항목만 나간다.
 *
 * <p>예외를 던지지 않는다. 응답을 못 받은 경우까지 전부 네 갈래 중 하나로 접어 돌려준다.
 *
 * <p>접는 순서가 정해져 있다 — <b>전송 계층 판정이 먼저이고 응답 코드 표는 응답을 받았을 때만 쓴다.</b>
 * 못 받은 경우가 바로 돈이 걸리는 경우라, 그쪽을 코드 표에 맡기면 판정이 비어 버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAdapter implements PaymentGatewayPort {

	/**
	 * 이력을 이어 받는 횟수의 상한. 응답이 알려주는 전체 페이지 수를 믿고 도는 반복이라, 그 값이
	 * 이상하면 끝나지 않는 경로가 생긴다.
	 */
	private static final int MAX_HISTORY_PAGES = 20;

	/** 이력 항목의 상태 값은 성공·실패 둘뿐이다. 처리 중을 뜻하는 값이 없다 */
	private static final String SUCCEEDED_ADMISSION_STATE = "SUCCESS";

	private static final DateTimeFormatter ADMISSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private static final int HTTP_UNAUTHORIZED = 401;
	private static final int HTTP_FORBIDDEN = 403;
	private static final int HTTP_CONFLICT = 409;
	private static final int HTTP_TOO_MANY_REQUESTS = 429;

	private final GatewayClient client;
	private final ObjectMapper objectMapper;

	@Override
	public PgApproveResult approve(Payment payment) {
		GatewayExchange exchange =
			client.approve(payment.getPgPaymentId(), payment.pgIdempotencyKey());

		PgOutcome transportOutcome = transportOutcome(exchange);
		if (transportOutcome != null) {
			logFailure("승인", payment.getPgPaymentId(), transportOutcome, exchange.errorType().name(), exchange.httpStatus());
			return approveFailure(transportOutcome, false, transportMessage(exchange), callRecord(exchange, null));
		}

		GatewayResponse<ApproveBody> response = readBody(exchange, new TypeReference<>() {});
		if (response == null || response.getCode() == null) {
			return PgApproveResult.unanswered("승인 응답을 읽지 못했다", parseFailureRecord(exchange));
		}

		Optional<ApproveCode> code = ApproveCode.from(response.getCode());
		PgOutcome outcome = code.map(ApproveCode::getOutcome).orElse(PgOutcome.UNKNOWN);
		String message = message(response, code.map(ApproveCode::getDescription).orElse("모르는 승인 응답 코드"));
		PgCallRecord callRecord = callRecord(exchange, response.getCode());

		if (outcome != PgOutcome.SUCCEEDED) {
			logFailure("승인", payment.getPgPaymentId(), outcome, response.getCode(), exchange.httpStatus());
			if (code.filter(ApproveCode::isApprovalWindowClosed).isPresent()) {
				return PgApproveResult.approvalWindowClosed(message, callRecord);
			}
			return approveFailure(outcome, true, message, callRecord);
		}

		ApproveBody.Detail detail = response.getBody() == null ? null : response.getBody().getDetail();
		if (detail == null || detail.getMerchantPayKey() == null) {
			// 결제사는 승인이 됐다고 답했는데 그 내용이 비었다. 실패로 접으면 나간 돈을 안 나간 것으로
			// 다루게 되므로 결과 불명으로 두고 이력으로 확인하게 한다.
			return PgApproveResult.unsettled("승인 응답 본문이 비어 결과를 확인해야 한다", callRecord);
		}
		return PgApproveResult.succeeded(
			detail.getMerchantPayKey(), detail.getMerchantUserKey(), detail.getTotalPayAmount(),
			detail.getPayHistId(), message, callRecord);
	}

	@Override
	public PgRefundResult refund(Payment payment, Refund refund, PgCallSource source) {
		GatewayExchange exchange = client.refund(
			payment.getPgPaymentId(),
			refund.attemptKey(),
			refund.getPgIdempotencyKey(),
			refund.getAmount(),
			cancelReason(refund.getReason()),
			CancelRequester.from(refund.getRequester()),
			source
		);

		PgOutcome transportOutcome = transportOutcome(exchange);
		if (transportOutcome != null) {
			logFailure("취소", payment.getPgPaymentId(), transportOutcome, exchange.errorType().name(), exchange.httpStatus());
			return refundFailure(transportOutcome, false, null, transportMessage(exchange), callRecord(exchange, null));
		}

		GatewayResponse<CancelBody> response = readBody(exchange, new TypeReference<>() {});
		if (response == null || response.getCode() == null) {
			return PgRefundResult.unanswered("취소 응답을 읽지 못했다", parseFailureRecord(exchange));
		}

		// 중복 요청을 뜻하는 상태 코드와 함께 이전 응답이 실려 오는 경우가 여기로 온다. 상태 코드가
		// 아니라 그 본문이 결과를 정한다 — 상태 코드로 실패를 확정하면 이미 성공한 취소를 뒤집는다.
		Optional<CancelCode> code = CancelCode.from(response.getCode());
		PgOutcome outcome = code.map(CancelCode::getOutcome).orElse(PgOutcome.UNKNOWN);
		String message = message(response, code.map(CancelCode::getDescription).orElse("모르는 취소 응답 코드"));
		PgCallRecord callRecord = callRecord(exchange, response.getCode());

		if (outcome != PgOutcome.SUCCEEDED) {
			logFailure("취소", payment.getPgPaymentId(), outcome, response.getCode(), exchange.httpStatus());
			return refundFailure(outcome, true, code.map(CancelCode::getReviewCode).orElse(null), message, callRecord);
		}
		return PgRefundResult.succeeded(payHistId(response), message, callRecord);
	}

	@Override
	public PgHistoryResult readHistory(Payment payment, PgHistoryScope scope) {
		ApprovalType approvalType =
			scope == PgHistoryScope.REFUND_ONLY ? ApprovalType.CANCEL : ApprovalType.ALL;

		List<PgHistoryEntry> entries = new ArrayList<>();
		String message = null;
		int totalPageCount = 1;

		for (int page = 1; page <= totalPageCount && page <= MAX_HISTORY_PAGES; page++) {
			GatewayExchange exchange = client.readHistory(payment.getPgPaymentId(), approvalType, page);

			PgOutcome transportOutcome = transportOutcome(exchange);
			if (transportOutcome != null) {
				logFailure("이력 조회", payment.getPgPaymentId(), transportOutcome, exchange.errorType().name(), exchange.httpStatus());
				return PgHistoryResult.failed(transportOutcome, transportMessage(exchange));
			}

			GatewayResponse<HistoryBody> response = readBody(exchange, new TypeReference<>() {});
			if (response == null || response.getCode() == null) {
				return PgHistoryResult.failed(PgOutcome.UNKNOWN, "이력 응답을 읽지 못했다");
			}

			Optional<HistoryCode> code = HistoryCode.from(response.getCode());
			PgOutcome outcome = code.map(HistoryCode::getOutcome).orElse(PgOutcome.UNKNOWN);
			message = message(response, code.map(HistoryCode::getDescription).orElse("모르는 이력 응답 코드"));
			if (outcome != PgOutcome.SUCCEEDED) {
				logFailure("이력 조회", payment.getPgPaymentId(), outcome, response.getCode(), exchange.httpStatus());
				return PgHistoryResult.failed(outcome, message);
			}

			HistoryBody body = response.getBody();
			if (body == null || body.getList() == null) {
				// 목록 자체가 없는 것을 빈 목록으로 읽으면 "이력에 아무것도 없다"와 구별되지 않고,
				// 그러면 돈이 나간 건을 없던 것으로 확정한다.
				return PgHistoryResult.failed(PgOutcome.UNKNOWN, "이력 목록을 읽지 못했다");
			}
			body.getList().stream()
				.filter(Objects::nonNull)
				.map(this::toEntry)
				.filter(Objects::nonNull)
				.forEach(entries::add);

			totalPageCount = Math.max(body.getTotalPageCount(), 1);
			if (totalPageCount > MAX_HISTORY_PAGES) {
				log.warn("결제사 이력 페이지가 상한을 넘었다 pgPaymentId={} totalPageCount={} limit={}",
					payment.getPgPaymentId(), totalPageCount, MAX_HISTORY_PAGES);
			}
		}
		return PgHistoryResult.succeeded(entries, message);
	}

	/**
	 * 전송 단계에서 갈리는 것을 먼저 판정한다. 응답 본문을 읽어야 결과가 정해지는 경우에만 {@code null}을
	 * 돌려준다.
	 */
	private PgOutcome transportOutcome(GatewayExchange exchange) {
		return switch (exchange.errorType()) {
			// 연결이 안 됐다. 요청이 나가지 않았지만 중간 계층이 끼면 장담할 수 없어 별도 갈래로 두지
			// 않고, 그 사실은 호출 기록의 실패 유형으로만 남긴다.
			case CONNECT -> PgOutcome.RETRYABLE_FAILURE;
			// 보냈는데 응답을 못 읽었다. 결제사가 처리했을 수 있다.
			case TIMEOUT, PARSE -> PgOutcome.UNKNOWN;
			case HTTP -> httpOutcome(exchange);
			case NONE -> null;
		};
	}

	private PgOutcome httpOutcome(GatewayExchange exchange) {
		int status = exchange.httpStatus();
		if (status == HTTP_CONFLICT) {
			// 같은 멱등키의 요청이 이미 받아들여졌다는 뜻이라 돈이 이미 나갔을 수 있다. 이전 응답이 그대로
			// 실려 오면 그 본문이 결과를 정하고, 본문이 없으면 다시 시도할 수 없는 실패로 확정하지 않고
			// 결과 불명으로 두어 이력으로 확인하게 한다.
			return exchange.hasBody() ? null : PgOutcome.UNKNOWN;
		}
		if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN || status == HTTP_TOO_MANY_REQUESTS) {
			// 인증 거절과 요청 제한은 요청이 처리되지 않았음이 분명하다. 인증은 설정을 고치면 밀린 건이
			// 함께 풀리므로 개별 건을 사람에게 넘기지 않는다.
			return PgOutcome.RETRYABLE_FAILURE;
		}
		if (status >= 500) {
			// 결제사가 요청을 받아 처리하다 내부 오류를 낼 수 있다. 미처리로 단정해 바로 다시 보내면
			// 돈이 두 번 나간다.
			return PgOutcome.UNKNOWN;
		}
		if (status >= 400) {
			return PgOutcome.TERMINAL_FAILURE;
		}
		return PgOutcome.UNKNOWN;
	}

	private <T> GatewayResponse<T> readBody(GatewayExchange exchange, TypeReference<GatewayResponse<T>> type) {
		if (!exchange.hasBody()) {
			return null;
		}
		try {
			return objectMapper.readValue(exchange.rawBody(), type);
		} catch (Exception ex) {
			return null;
		}
	}

	private PgHistoryEntry toEntry(HistoryBody.History history) {
		Optional<AdmissionTypeCode> typeCode = AdmissionTypeCode.from(history.getAdmissionTypeCode());
		if (typeCode.isEmpty()) {
			// 원결제로도 환불로도 옮길 수 없는 항목이다. 판정에 넣을 자리가 없어 넘기지 못하므로,
			// 그런 값이 온다는 사실만이라도 남긴다.
			log.warn("결제사 이력에 모르는 종류 코드 pgPaymentId={} admissionTypeCode={}",
				history.getPaymentId(), history.getAdmissionTypeCode());
			return null;
		}
		return new PgHistoryEntry(
			typeCode.get().getEntryType(),
			SUCCEEDED_ADMISSION_STATE.equalsIgnoreCase(history.getAdmissionState()),
			history.getTotalPayAmount(),
			toOccurredAt(history.getAdmissionYmdt()),
			history.getMerchantPayKey(),
			history.getMerchantPayTransactionKey(),
			history.getPayHistId()
		);
	}

	private LocalDateTime toOccurredAt(String admissionYmdt) {
		if (admissionYmdt == null || admissionYmdt.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.parse(admissionYmdt, ADMISSION_TIME_FORMAT);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private PgApproveResult approveFailure(
		PgOutcome outcome,
		boolean answered,
		String message,
		PgCallRecord callRecord
	) {
		return switch (outcome) {
			case UNKNOWN -> answered
				? PgApproveResult.unsettled(message, callRecord)
				: PgApproveResult.unanswered(message, callRecord);
			case RETRYABLE_FAILURE -> PgApproveResult.retryableFailure(answered, message, callRecord);
			case TERMINAL_FAILURE -> PgApproveResult.terminalFailure(answered, message, callRecord);
			case SUCCEEDED -> throw new IllegalArgumentException("성공은 실패 갈래로 만들지 않는다");
		};
	}

	private PgRefundResult refundFailure(
		PgOutcome outcome,
		boolean answered,
		RefundReviewCode reviewCode,
		String message,
		PgCallRecord callRecord
	) {
		return switch (outcome) {
			case UNKNOWN -> answered
				? PgRefundResult.unsettled(message, callRecord)
				: PgRefundResult.unanswered(message, callRecord);
			case RETRYABLE_FAILURE -> PgRefundResult.retryableFailure(answered, message, callRecord);
			// 검토 코드가 없는 채로 사람에게 넘기면 돈이 나갔는지를 알 수 없다. 표에 없는 코드는 여기까지
			// 오지 않지만, 표를 늘릴 때 빠뜨려도 조사할 근거는 남게 둔다.
			case TERMINAL_FAILURE -> PgRefundResult.terminalFailure(
				reviewCode == null ? RefundReviewCode.REQUEST_REJECTED : reviewCode, message, callRecord);
			case SUCCEEDED -> throw new IllegalArgumentException("성공은 실패 갈래로 만들지 않는다");
		};
	}

	/** 결제사가 사람이 읽는 문구로 요구하는 필수 필드다. 도메인 enum 값을 그대로 보내지 않는다 */
	private String cancelReason(RefundReason reason) {
		return switch (reason) {
			case ORDER_CANCELED -> "구매자 주문 취소";
			case ORDER_NOT_PAYABLE -> "주문 상태로 결제를 받을 수 없어 취소";
			case AMOUNT_MISMATCH -> "승인 금액 불일치로 취소";
		};
	}

	private String payHistId(GatewayResponse<CancelBody> response) {
		return response.getBody() == null ? null : response.getBody().getPayHistId();
	}

	private PgCallRecord callRecord(GatewayExchange exchange, String resultCode) {
		return new PgCallRecord(exchange.errorType(), resultCode, exchange.httpStatus(), exchange.rawBody());
	}

	private PgCallRecord parseFailureRecord(GatewayExchange exchange) {
		return new PgCallRecord(PgErrorType.PARSE, null, exchange.httpStatus(), exchange.rawBody());
	}

	private String transportMessage(GatewayExchange exchange) {
		return switch (exchange.errorType()) {
			case CONNECT -> "결제사에 연결하지 못했다";
			case TIMEOUT -> "결제사 응답을 받지 못했다";
			case HTTP -> "결제사가 오류 상태로 답했다: " + exchange.httpStatus();
			default -> "결제사 응답을 해석하지 못했다";
		};
	}

	private String message(GatewayResponse<?> response, String fallback) {
		String message = response.getMessage();
		return (message == null || message.isBlank()) ? fallback : message;
	}

	/**
	 * 결제사가 거절한 것과 다시 시도할 수 있는 실패를 남긴다. 응답 원본은 담지 않는다 — 마스킹된
	 * 카드번호·계좌번호가 그 안에 있어 호출 기록 테이블에만 둔다.
	 */
	private void logFailure(String call, String pgPaymentId, PgOutcome outcome, String resultCode, Integer httpStatus) {
		if (outcome != PgOutcome.RETRYABLE_FAILURE && outcome != PgOutcome.TERMINAL_FAILURE) {
			return;
		}
		log.warn("결제사 {} 실패 pgPaymentId={} outcome={} resultCode={} httpStatus={}",
			call, pgPaymentId, outcome, resultCode, httpStatus);
	}
}
