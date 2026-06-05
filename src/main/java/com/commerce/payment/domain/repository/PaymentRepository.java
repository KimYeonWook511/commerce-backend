package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;

public interface PaymentRepository {

	Payment save(Payment payment);

	Optional<Payment> findApprovePayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findCancelPayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findApproveSucceeded(String merchantPayKey);

	boolean existsApproveSucceeded(String merchantPayKey);

	boolean existsUnknownByOrderId(Long orderId);
}
