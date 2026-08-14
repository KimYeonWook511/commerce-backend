package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.policy.PaymentPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 승인을 한 번도 부르지 않은 채 방치된 결제를 종결한다. 주기 실행으로만 돈다.
 *
 * <p>승인을 부른 뒤의 결제는 대상이 아니다. 종결시켰다가 그 승인이 성공하면 돈은 나갔는데 우리 기록은
 * 종결이다.
 *
 * <p>없어도 주문이 막히지는 않는다 — 새 결제 요청이 언제든 자리를 빼앗는다. 두는 이유는 죽은 결제창이
 * 상태로 드러나는 것과 살아 있는 슬롯을 쥔 행이 무한정 쌓이지 않는 것 둘이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirePaymentUseCase {

	private static final int BATCH_SIZE = 100;

	private final PaymentRepository paymentRepository;
	private final PaymentService paymentService;
	private final PaymentPostProcessPolicy policy;

	public void expire() {
		List<Payment> targets = paymentRepository.findExpireTargets(
			policy.createdBeforeForExpire(LocalDateTime.now()), PageRequest.of(0, BATCH_SIZE));
		if (targets.isEmpty()) {
			return;
		}

		log.info("결제 만료 종결 시작 targets={}", targets.size());
		for (Payment target : targets) {
			try {
				paymentService.expire(target.getId());
			} catch (Exception ex) {
				log.error("결제 만료 종결 실패 paymentId={} orderId={}", target.getId(), target.getOrderId(), ex);
			}
		}
	}
}
