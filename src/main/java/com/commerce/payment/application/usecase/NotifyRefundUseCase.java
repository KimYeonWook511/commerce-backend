package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.policy.RefundPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 돈이 아직 안 돌아간 환불을 운영자에게 알린다. 주기 실행으로만 돌고 밖에서 부를 수 있는 경로가 없다.
 *
 * <p>대사와 조회 조건이 달라 유스케이스가 갈린다 — 대사는 읽은 지 얼마나 됐는지를 보고, 통지는 만들어진
 * 지와 알린 지를 본다. 하나로 묶으면 임계 시각을 하나만 줄 수 있다.
 *
 * <p>알린 뒤에도 회수를 멈추지 않는다. 통지는 자동 처리와 다른 축이고, 멈추면 돈이 안 돌아간 채 남는다.
 *
 * <p>상태가 이어지는 동안 반복한다. 사람이 처리하면 상태가 바뀌어 저절로 멈추고, 알림이 계속 온다는 것이
 * 곧 아직 아무도 처리하지 않았다는 사실이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyRefundUseCase {

	private static final int BATCH_SIZE = 100;

	private static final String UNSETTLED_REASON = "환불 결과를 확정하지 못한 채 통지 기준 시간을 넘겼다";

	private static final String REVIEW_REASON = "환불을 자동으로 더 진행할 수 없어 사람이 돌려줘야 한다";

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final RefundService refundService;
	private final NotificationPort notificationPort;
	private final RefundPostProcessPolicy policy;

	public void notifyStalled() {
		LocalDateTime now = LocalDateTime.now();
		List<Refund> targets = refundRepository.findNotifyTargets(
			policy.createdBeforeForNotify(now), policy.notifiedBefore(now), PageRequest.of(0, BATCH_SIZE));
		if (targets.isEmpty()) {
			return;
		}

		log.info("환불 통지 시작 targets={}", targets.size());
		for (Refund target : targets) {
			try {
				notifyOne(target);
			} catch (Exception ex) {
				log.error("환불 통지 실패 refundId={} paymentId={}", target.getId(), target.getPaymentId(), ex);
			}
		}
	}

	/**
	 * 보낸 뒤에 시각을 남긴다. 먼저 남기고 보내다 실패하면 통지가 한 번도 안 나갔는데 다시 대상이 되지
	 * 않는다. 대가로 동시에 도는 주기 둘이 각자 보낼 수 있지만, 돈이 얽힌 건은 알림이 안 가는 것보다
	 * 두 번 가는 것이 낫다.
	 *
	 * <p>알림이 가리키는 주문과 결제 키는 결제 행에 있다. 읽기만 하므로 결제 버전이 오르지 않는다.
	 */
	private void notifyOne(Refund target) {
		Payment payment = paymentRepository.findById(target.getPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		notificationPort.notifyManualReviewRequired(
			payment.getOrderId(), payment.getPaymentKey(), reason(target));
		refundService.recordNotified(target.getId(), LocalDateTime.now());
	}

	/** 운영자가 할 일이 갈린다 — 결과를 기다릴 건인지, 다른 수단으로 돌려줄 건인지 */
	private String reason(Refund target) {
		return target.getStatus() == RefundStatus.MANUAL_REVIEW ? REVIEW_REASON : UNSETTLED_REASON;
	}
}
