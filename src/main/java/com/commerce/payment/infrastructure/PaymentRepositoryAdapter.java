package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepository {

	private final JpaPaymentRepository jpaPaymentRepository;

	@Override
	public Payment save(Payment payment) {
		return jpaPaymentRepository.saveAndFlush(payment);
	}

	/**
	 * APPROVE 승인 완료 전용 저장 경로.
	 * saveAndFlush의 조기 flush가 uk_payment_approved_order_key 위반을 이 메서드 호출 안에서 확정한다(load-bearing).
	 * constraint name이 uk_payment_approved_order_key인 경우에만 PaymentException(PAYMENT_DUPLICATE)로 변환하고,
	 * 그 외 무결성 위반은 원 예외를 그대로 전파한다(ADR-011 carve-out).
	 */
	@Override
	public Payment saveApproved(Payment payment) {
		try {
			return jpaPaymentRepository.saveAndFlush(payment);
		} catch (DataIntegrityViolationException ex) {
			if (isApprovedOrderKeyViolation(ex)) {
				throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
			}
			throw ex;
		}
	}

	/**
	 * cause 체인에서 uk_payment_approved_order_key 위반 여부를 확인한다.
	 * 1) Hibernate ConstraintViolationException의 getConstraintName()으로 정확히 확인 (우선)
	 * 2) Spring이 Hibernate 예외를 DuplicateKeyException으로 변환할 때 cause가 JDBC SQLException이 되므로,
	 *    JDBC 예외 메시지에서 constraint name을 보조 확인한다.
	 * 어느 쪽으로도 확정할 수 없으면 false를 반환해 원 예외를 전파한다(보수적 원칙).
	 */
	private static boolean isApprovedOrderKeyViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException hce) {
				String name = hce.getConstraintName();
				return name != null && "uk_payment_approved_order_key".equalsIgnoreCase(name);
			}
			if (cause instanceof java.sql.SQLException sqlEx) {
				String msg = sqlEx.getMessage();
				return msg != null && msg.contains("uk_payment_approved_order_key");
			}
			cause = cause.getCause();
		}
		return false;
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
