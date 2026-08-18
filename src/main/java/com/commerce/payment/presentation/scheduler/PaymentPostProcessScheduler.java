package com.commerce.payment.presentation.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.usecase.DispatchRefundUseCase;
import com.commerce.payment.application.usecase.ExpirePaymentUseCase;
import com.commerce.payment.application.usecase.NotifyPaymentUseCase;
import com.commerce.payment.application.usecase.NotifyRefundUseCase;
import com.commerce.payment.application.usecase.ReconcilePaymentUseCase;
import com.commerce.payment.application.usecase.ReconcileRefundUseCase;

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
	private final DispatchRefundUseCase dispatchRefundUseCase;
	private final ReconcileRefundUseCase reconcileRefundUseCase;
	private final NotifyRefundUseCase notifyRefundUseCase;

	/**
	 * 아직 안 나간 환불을 훑어 보낸다. 주문 취소와 승인 반려는 커밋 뒤에 한 번 부르고 마는데, 그 호출이
	 * 실패하면 환불이 접수 상태로 남아 이 자리가 없으면 영영 안 나간다.
	 */
	@Scheduled(cron = "${payment.postprocess.refund.dispatch.cron}")
	public void dispatchRefunds() {
		dispatchRefundUseCase.dispatch();
	}

	@Scheduled(cron = "${payment.postprocess.reconcile.cron}")
	public void reconcile() {
		reconcilePaymentUseCase.reconcile();
	}

	@Scheduled(cron = "${payment.postprocess.notify.cron}")
	public void notifyStalled() {
		notifyPaymentUseCase.notifyStalled();
	}

	@Scheduled(cron = "${payment.postprocess.expire.cron}")
	public void expire() {
		expirePaymentUseCase.expire();
	}

	/**
	 * 결과를 모르는 환불을 이력으로 확정하고, 이력에 그 시도가 없으면 그 자리에서 다시 보낸다.
	 * 결제 대사와 조회 조건이 달라 주기를 따로 붙인다.
	 */
	@Scheduled(cron = "${payment.postprocess.refund.reconcile.cron}")
	public void reconcileRefunds() {
		reconcileRefundUseCase.reconcile();
	}

	@Scheduled(cron = "${payment.postprocess.refund.notify.cron}")
	public void notifyStalledRefunds() {
		notifyRefundUseCase.notifyStalled();
	}
}
