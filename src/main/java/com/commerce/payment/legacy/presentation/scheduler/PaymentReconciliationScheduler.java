package com.commerce.payment.legacy.presentation.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerce.payment.legacy.application.usecase.ReconcilePaymentUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 옛 모델의 대사를 깨우는 진입점.
 *
 * <p>결제 대사는 더 이상 여기서 깨우지 않는다. 새 결제 모델의 대사가 그 자리를 받았고, 둘 다 돌면 같은
 * 결제를 두 번 대사한다. 환불 대사는 그대로 둔다 — 옛 주문 취소가 아직 살아 있어 그것이 만든 환불을
 * 회수할 자리가 여기뿐이고, 멈추면 그 구간의 환불이 돈이 안 돌아간 채 방치된다.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

	private final ReconcilePaymentUseCase reconcilePaymentUseCase;

	@Scheduled(cron = "${payment.reconciliation.cron:0 */1 * * * *}")
	public void run() {
		reconcilePaymentUseCase.reconcileCancels();
	}
}
