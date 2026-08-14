package com.commerce.payment.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

	PAYMENT_CONCURRENTLY_MODIFIED(ErrorCategory.CONFLICT, "PAYMENT-409-10",
		"다른 처리가 먼저 결제 상태를 변경했습니다"),
	REFUND_CONCURRENTLY_MODIFIED(ErrorCategory.CONFLICT, "PAYMENT-409-11",
		"다른 처리가 먼저 환불 상태를 변경했습니다"),
	// 같은 멱등키가 겹쳤을 때와 같은 주문에 결제가 동시에 들어왔을 때가 이 값을 함께 쓴다. 회원이 할 일이
	// "잠시 후 다시"로 같아서 응답을 가르지 않는다. 서버 오류가 아니어야 클라이언트가 진짜 장애와 구분한다.
	PAYMENT_REQUEST_IN_PROGRESS(ErrorCategory.CONFLICT, "PAYMENT-409-12",
		"같은 요청이 이미 처리 중입니다. 잠시 후 다시 시도해 주세요."),
	// 승인을 부른 뒤 결과가 정해지지 않은 결제가 그 주문에 있다. 회원이 할 일은 다시 누르는 것이 아니라
	// 결과를 기다리는 것이라 위 응답과 가른다.
	PAYMENT_RESULT_PENDING(ErrorCategory.CONFLICT, "PAYMENT-409-13",
		"결제 결과 확인 중입니다. 잠시 후 주문 내역에서 확인해 주세요."),
	PAYMENT_DUPLICATE(ErrorCategory.CONFLICT, "PAYMENT-409-14", "이미 다른 결제가 완료된 주문입니다"),
	// 자리를 내주었거나 세션이 지났거나 이미 결과가 난 결제 시도로 승인이 돌아왔다. 그 시도로는 더
	// 진행할 것이 없으므로 결제사를 부르지 않고 회원에게 다시 시작하라고 알린다.
	PAYMENT_ATTEMPT_CLOSED(ErrorCategory.CONFLICT, "PAYMENT-409-15",
		"종결된 결제 시도입니다. 결제를 다시 시작해 주세요."),
	PAYMENT_APPROVAL_FAILED(ErrorCategory.CONFLICT, "PAYMENT-409-16", "결제 승인에 실패했습니다"),
	PAYMENT_AMOUNT_MISMATCH(ErrorCategory.CONFLICT, "PAYMENT-409-17", "승인 금액이 주문 금액과 다릅니다"),
	PAYMENT_ALREADY_CANCELED(ErrorCategory.CONFLICT, "PAYMENT-409-18", "이미 취소된 결제입니다"),
	// 승인 응답에 실려 온 결제 키가 이 결제의 것이 아니다. 어느 결제가 승인됐는지를 회원에게 알리지
	// 않는다 — 그 값은 남의 결제를 가리킨다.
	PAYMENT_KEY_MISMATCH(ErrorCategory.CONFLICT, "PAYMENT-409-19",
		"결제 정보가 일치하지 않아 결제를 완료하지 못했습니다"),

	PAYMENT_NOT_FOUND(ErrorCategory.NOT_FOUND, "PAYMENT-404", "결제를 찾을 수 없습니다"),

	PAYMENT_PG_NOT_SUPPORTED(ErrorCategory.INVALID, "PAYMENT-400-19", "지원하지 않는 결제 수단입니다"),
	REFUND_AMOUNT_INVALID(ErrorCategory.INVALID, "PAYMENT-400-20", "환불 금액은 0보다 커야 합니다"),
	REFUND_IDEMPOTENCY_KEY_REQUIRED(ErrorCategory.INVALID, "PAYMENT-400-21", "환불 요청 멱등키가 필요합니다"),
	// 앞서 만든 결제를 그대로 돌려주면 다른 주문을 결제하려던 요청에 앞 주문의 결제창 값이 나가고,
	// 회원이 그 창에서 인증해 엉뚱한 주문이 결제된다. 다시 보내도 같으므로 새 키를 쓰라는 뜻이다.
	PAYMENT_IDEMPOTENCY_KEY_CONFLICT(ErrorCategory.INVALID, "PAYMENT-400-22",
		"같은 멱등키로 내용이 다른 요청이 왔습니다"),

	PAYMENT_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-10",
		"결제 상태 전이가 허용되지 않습니다"),
	PAYMENT_CLOSE_CODE_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-11",
		"그 종결 상태의 종결 코드가 아닙니다"),
	// 승인 금액이 0이면 한도가 처음부터 0이라 되돌릴 환불을 만들 수 없다. 정상 경로에서 나올 수 없는 값이므로
	// 확정을 막고, 부르는 쪽이 그 결제를 결과 불명으로 남긴다.
	PAYMENT_APPROVED_AMOUNT_INVALID(ErrorCategory.INTERNAL, "PAYMENT-500-12",
		"승인 금액이 0보다 크지 않습니다"),
	REFUND_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-13",
		"환불 상태 전이가 허용되지 않습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	PaymentErrorCode(ErrorCategory category, String code, String message) {
		this.category = category;
		this.code = code;
		this.message = message;
	}

	@Override
	public ErrorCategory getCategory() {
		return category;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
