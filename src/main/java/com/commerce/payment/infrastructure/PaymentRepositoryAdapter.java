package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepository {

	private final JpaPaymentRepository jpaPaymentRepository;

	@Override
	public Payment save(Payment payment) {
		return jpaPaymentRepository.saveAndFlush(payment);
	}

	@Override
	public Optional<Payment> findApprovePayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId) {
		return jpaPaymentRepository.findByMerchantPayKeyAndProviderAndPgPaymentIdAndType(
			merchantPayKey, provider, pgPaymentId, PaymentType.APPROVE
		);
	}

	@Override
	public Optional<Payment> findCancelPayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId) {
		return jpaPaymentRepository.findByMerchantPayKeyAndProviderAndPgPaymentIdAndType(
			merchantPayKey, provider, pgPaymentId, PaymentType.CANCEL
		);
	}

	@Override
	public Optional<Payment> findApproveSucceeded(String merchantPayKey) {
		return jpaPaymentRepository.findByMerchantPayKeyAndTypeAndStatus(
			merchantPayKey, PaymentType.APPROVE, PaymentStatus.SUCCEEDED
		);
	}

	@Override
	public boolean existsApproveSucceeded(String merchantPayKey) {
		return jpaPaymentRepository.existsByMerchantPayKeyAndTypeAndStatus(
			merchantPayKey, PaymentType.APPROVE, PaymentStatus.SUCCEEDED
		);
	}

	@Override
	public boolean existsUnknownByOrderId(Long orderId) {
		return jpaPaymentRepository.existsByOrderIdAndTypeAndStatus(
			orderId, PaymentType.APPROVE, PaymentStatus.UNKNOWN
		);
	}
}
