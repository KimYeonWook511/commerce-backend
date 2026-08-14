package com.commerce.payment.legacy.application.port;

import com.commerce.payment.legacy.application.port.result.CancelOutcome;
import com.commerce.payment.legacy.domain.Payment;

@FunctionalInterface
public interface PgCanceller {

	CancelOutcome cancel(Payment cancelPayment, String cancelReason);
}
