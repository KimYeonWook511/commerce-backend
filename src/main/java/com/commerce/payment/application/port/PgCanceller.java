package com.commerce.payment.application.port;

import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;

@FunctionalInterface
public interface PgCanceller {

	CancelOutcome cancel(Payment cancelPayment, String cancelReason);
}
