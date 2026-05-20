package com.commerce.payment.application.port;

import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.PaymentAttempt;

@FunctionalInterface
public interface PgCanceller {

	CancelOutcome cancel(PaymentAttempt cancelAttempt, String cancelReason);
}
