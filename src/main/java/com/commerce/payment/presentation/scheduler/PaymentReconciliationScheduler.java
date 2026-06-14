package com.commerce.payment.presentation.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.usecase.PaymentReconciliationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

	private final PaymentReconciliationService paymentReconciliationService;

	@Scheduled(cron = "${payment.reconciliation.cron:0 */1 * * * *}")
	public void run() {
		paymentReconciliationService.reconcile();
	}
}
