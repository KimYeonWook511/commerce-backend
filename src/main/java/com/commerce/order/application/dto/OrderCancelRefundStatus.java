package com.commerce.order.application.dto;

import com.commerce.payment.domain.RefundStatus;

/**
 * 주문 취소 응답이 말하는 환불 진행 상태. 값이 셋뿐이고 환불 상태 다섯이 그중 둘로 접힌다.
 *
 * <p>접는 자리를 두지 않으면 프론트가 모르는 값을 받는다. 주문 취소 요청 안에서 결제사가 다시 시도할 수
 * 없는 실패로 답하면 그 자리에서 검토 상태가 되므로, 실제로 나갈 수 있는 값이다.
 */
public enum OrderCancelRefundStatus {

	/** 환불 없음 (결제 전 주문 취소) */
	NONE,
	/** 돈이 돌아갔다 */
	COMPLETED,
	/** 돌아가는 중이다 */
	IN_PROGRESS;

	/**
	 * 환불 상태를 응답 값으로 접는다.
	 *
	 * <p>사람이 이어받아야 하는 환불도 "처리 중"으로 내보낸다. 회원에게 알려도 할 수 있는 일이 없고
	 * 문의만 늘며, 돈은 결국 돌아간다.
	 */
	public static OrderCancelRefundStatus from(RefundStatus status) {
		return switch (status) {
			case SUCCEEDED -> COMPLETED;
			case REQUESTED, IN_PROGRESS, UNKNOWN, MANUAL_REVIEW -> OrderCancelRefundStatus.IN_PROGRESS;
		};
	}
}
