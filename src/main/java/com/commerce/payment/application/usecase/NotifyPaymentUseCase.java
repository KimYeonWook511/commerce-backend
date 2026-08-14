package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.policy.PaymentPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오래도록 결과가 정해지지 않은 결제를 운영자에게 알린다. 주기 실행으로만 돌고 밖에서 부를 수 있는
 * 경로가 없다.
 *
 * <p>대사와 조회 조건이 달라 유스케이스가 갈린다 — 대사는 읽은 지 얼마나 됐는지를 보고, 통지는 만들어진
 * 지와 알린 지를 본다. 하나로 묶으면 임계 시각을 하나만 줄 수 있다.
 *
 * <p>알린 뒤에도 회수를 멈추지 않는다. 통지는 자동 처리와 다른 축이고, 멈추면 그 결제가 활성 슬롯을
 * 쥔 채 남아 그 주문을 영영 결제할 수 없다.
 *
 * <p>해결되면 상태가 바뀌어 대상에서 빠지므로 반복은 저절로 멈춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPaymentUseCase {

	private static final int BATCH_SIZE = 100;

	private static final String REASON = "승인 결과를 확정하지 못한 채 통지 기준 시간을 넘겼다";

	private final PaymentRepository paymentRepository;
	private final PaymentService paymentService;
	private final NotificationPort notificationPort;
	private final PaymentPostProcessPolicy policy;

	public void notifyStalled() {
		LocalDateTime now = LocalDateTime.now();
		List<Payment> targets = paymentRepository.findNotifyTargets(
			policy.createdBeforeForNotify(now), policy.notifiedBefore(now), PageRequest.of(0, BATCH_SIZE));
		if (targets.isEmpty()) {
			return;
		}

		log.info("결제 통지 시작 targets={}", targets.size());
		for (Payment target : targets) {
			try {
				notifyOne(target);
			} catch (Exception ex) {
				log.error("결제 통지 실패 paymentId={} orderId={}", target.getId(), target.getOrderId(), ex);
			}
		}
	}

	/**
	 * 보낸 뒤에 시각을 남긴다. 먼저 남기고 보내다 실패하면 통지가 한 번도 안 나갔는데 다시 대상이 되지
	 * 않는다. 대가로 동시에 도는 주기 둘이 각자 보낼 수 있지만, 돈이 얽힌 건은 알림이 안 가는 것보다
	 * 두 번 가는 것이 낫다.
	 */
	private void notifyOne(Payment target) {
		notificationPort.notifyManualReviewRequired(target.getOrderId(), target.getPaymentKey(), REASON);
		paymentService.recordNotified(target.getId(), LocalDateTime.now());
	}
}
