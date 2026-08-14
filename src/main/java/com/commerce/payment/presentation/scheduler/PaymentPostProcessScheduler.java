package com.commerce.payment.presentation.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.usecase.ExpirePaymentUseCase;
import com.commerce.payment.application.usecase.NotifyPaymentUseCase;
import com.commerce.payment.application.usecase.ReconcilePaymentUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 결제 후처리를 깨우는 진입점. 조회 조건이 달라 유스케이스는 갈리지만 진입점은 하나에 메서드를 나눠
 * 붙인다 — 주기를 따로 정해야 하면 메서드마다 붙이면 되고, 한 클래스에 모여 있어야 무엇이 언제 도는지
 * 한눈에 보인다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentPostProcessScheduler {

	private final ReconcilePaymentUseCase reconcilePaymentUseCase;
	private final NotifyPaymentUseCase notifyPaymentUseCase;
	private final ExpirePaymentUseCase expirePaymentUseCase;

	@Scheduled(cron = "${payment.postprocess.reconcile.cron:0 */1 * * * *}")
	public void reconcile() {
		reconcilePaymentUseCase.reconcile();
	}

	@Scheduled(cron = "${payment.postprocess.notify.cron:0 */10 * * * *}")
	public void notifyStalled() {
		notifyPaymentUseCase.notifyStalled();
	}

	@Scheduled(cron = "${payment.postprocess.expire.cron:0 */10 * * * *}")
	public void expire() {
		expirePaymentUseCase.expire();
	}
}
