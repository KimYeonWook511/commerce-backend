package com.commerce.payment.application.dto;

/**
 * 승인 요청에 대한 응답. 본문이 나가는 것은 승인이 확정됐을 때뿐이고, 확정하지 못한 경우는 전부
 * 실패 응답으로 나간다 — 결과를 모른 채 성공도 실패도 아닌 값을 본문에 담으면 회원이 그것을 결제
 * 완료로 읽는다.
 */
public record ApprovalResult(String pgPaymentId, ApprovalStatus status) {

	public static ApprovalResult succeeded(String pgPaymentId) {
		return new ApprovalResult(pgPaymentId, ApprovalStatus.SUCCESS);
	}
}
