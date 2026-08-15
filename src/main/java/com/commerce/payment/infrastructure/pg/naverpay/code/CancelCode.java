package com.commerce.payment.infrastructure.pg.naverpay.code;

import java.util.Optional;

import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.domain.RefundReviewCode;

import lombok.Getter;

/**
 * 취소 응답 코드를 네 갈래로 접는 표. 다시 시도할 수 없는 실패에는 검토 코드를 함께 붙인다 —
 * 관리자가 알아야 하는 것은 결국 돈이 나갔는가이고, 그 값이 상태보다 정확히 알려준다.
 *
 * <p>목록에 없는 코드는 여기서 값을 찾지 못하고, 부르는 쪽이 그것을 결과 불명으로 접는다 — 실패로
 * 접으면 실제로 나간 돈을 안 나간 것으로 다루게 된다.
 */
@Getter
public enum CancelCode {

	SUCCESS("Success", PgOutcome.SUCCEEDED, "성공"),
	/**
	 * 취소가 아직 끝나지 않았지만 결제사가 곧 자동으로 다시 처리한다. 명세가 재시도하지 말라고 지시하므로
	 * 성공으로 접는다.
	 */
	CANCEL_NOT_COMPLETE("CancelNotComplete", PgOutcome.SUCCEEDED, "취소 자동 재처리 예정"),

	/** 같은 결제 번호로 요청이 이미 진행 중이다. 우리 요청이 처리되는 중일 수 있어 실패로 볼 수 없다 */
	ALREADY_ON_GOING("AlreadyOnGoing", PgOutcome.UNKNOWN, "이미 진행 중인 요청"),

	/**
	 * 앞선 취소가 아직 처리 중이라 다음 취소를 받지 않는다. 앞 건이 끝나면 그대로 성공할 건이므로 상태를
	 * 그대로 두고 다음 주기에 다시 보낸다 — 순서를 세우는 것은 결제사이고 우리는 그 거절을 접을 뿐이다.
	 */
	PRE_CANCEL_NOT_COMPLETE("PreCancelNotComplete", PgOutcome.RETRYABLE_FAILURE, "앞선 취소가 처리 중"),
	/** 가맹점 설정이 틀린 것이라 개별 건을 사람에게 넘길 일이 아니다. 고치면 밀린 건이 함께 풀린다 */
	INVALID_MERCHANT("InvalidMerchant", PgOutcome.RETRYABLE_FAILURE, "유효하지 않은 가맹점"),
	MAINTENANCE_ONGOING("MaintenanceOngoing", PgOutcome.RETRYABLE_FAILURE, "서비스 점검 중"),
	FAULT_CHECK_ONGOING("FaultCheckOngoing", PgOutcome.RETRYABLE_FAILURE, "원천사 시스템 점검 중"),

	/**
	 * 이미 전액 취소된 결제다. 성공으로 접지 않는다 — 우리가 그 요청을 보냈다는 것은 우리 기록과 결제사
	 * 기록이 갈렸다는 뜻이라, 성공으로 접으면 우리가 모르는 환불이 있는 경우를 덮는다.
	 */
	ALREADY_CANCELED("AlreadyCanceled", PgOutcome.TERMINAL_FAILURE, "이미 전체 취소된 결제",
		RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED),
	OVER_REMAIN_AMOUNT("OverRemainAmount", PgOutcome.TERMINAL_FAILURE, "취소 요청 금액이 잔여 금액을 넘음",
		RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED),
	TAX_SCOPE_AMT_GREATER_THAN_REMAIN_ERROR("TaxScopeAmtGreaterThanRemainError", PgOutcome.TERMINAL_FAILURE,
		"취소 가능한 과면세 금액보다 큰 금액 요청", RefundReviewCode.REFUNDABLE_AMOUNT_EXCEEDED),
	CANCEL_DEADLINE_EXPIRED("CancelDeadlineExpired", PgOutcome.TERMINAL_FAILURE, "취소 기한 만료",
		RefundReviewCode.CANCEL_DEADLINE_EXPIRED),
	INVALID_DISCOUNT_CANCEL_CONDITION("InvalidDiscountCancelCondition", PgOutcome.TERMINAL_FAILURE,
		"즉시 할인 정책으로 취소 불가", RefundReviewCode.CANCEL_NOT_ALLOWED),
	INVALID_PAYMENT_ID("InvalidPaymentId", PgOutcome.TERMINAL_FAILURE, "유효하지 않은 결제 번호",
		RefundReviewCode.REQUEST_REJECTED),
	TAX_SCOPE_AMOUNT_ERROR("TaxScopeAmountError", PgOutcome.TERMINAL_FAILURE,
		"과면세 금액의 합이 취소 요청 금액과 다름", RefundReviewCode.REQUEST_REJECTED),
	FAIL("Fail", PgOutcome.TERMINAL_FAILURE, "기타 실패", RefundReviewCode.REQUEST_REJECTED);

	private final String code;
	private final PgOutcome outcome;
	private final String description;
	private final RefundReviewCode reviewCode;

	CancelCode(String code, PgOutcome outcome, String description) {
		this(code, outcome, description, null);
	}

	CancelCode(String code, PgOutcome outcome, String description, RefundReviewCode reviewCode) {
		this.code = code;
		this.outcome = outcome;
		this.description = description;
		this.reviewCode = reviewCode;
	}

	public static Optional<CancelCode> from(String code) {
		for (CancelCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
