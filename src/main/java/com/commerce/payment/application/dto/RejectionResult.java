package com.commerce.payment.application.dto;

import java.util.Optional;

import com.commerce.payment.domain.Refund;

/**
 * 승인 반려 트랜잭션이 커밋한 것. 만들어진 환불과 그때 드러난 정합성 이상을 함께 돌려준다.
 *
 * <p>환불을 함께 돌려주는 것은 커밋 뒤에 그것을 결제사로 보내야 하기 때문이다. 돌려주지 않으면 반려가
 * 만든 환불이 발송 배치가 돌 때까지 접수 상태로 남는다.
 *
 * <p>정합성 이상을 예외가 아니라 값으로 돌려주는 것은 통지를 커밋 뒤로 미루기 위해서다. 트랜잭션 안에서
 * 던지면 되돌릴 근거가 통째로 롤백되는데, 그것이 승인 반려가 막으려는 바로 그 상태다.
 *
 * @param anomaly 드러난 정합성 이상. 없으면 {@link RejectionAnomaly#NONE}
 * @param refund  만들어졌거나 이미 있던 환불. 남은 한도가 0이면 비어 있다
 */
public record RejectionResult(RejectionAnomaly anomaly, Refund refund) {

	public static RejectionResult of(RejectionAnomaly anomaly, Refund refund) {
		return new RejectionResult(anomaly, refund);
	}

	public static RejectionResult withoutRefund(RejectionAnomaly anomaly) {
		return new RejectionResult(anomaly, null);
	}

	public Optional<Refund> refundToSend() {
		return Optional.ofNullable(refund);
	}
}
