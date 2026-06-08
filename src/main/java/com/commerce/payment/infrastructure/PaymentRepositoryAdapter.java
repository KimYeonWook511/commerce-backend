package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
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

	// JpaConfig에서 SQLErrorCodeSQLExceptionTranslator 빈이 제거된 후 unique 위반은
	// DataIntegrityViolationException(cause=Hibernate ConstraintViolationException(cause=SQLException))으로 올라온다.
	// getConstraintName()이 테이블 prefix를 포함한 형태(tbl_payment.uk_...)를 반환하므로 마지막 dot-세그먼트로 비교한다.
	private static final String APPROVED_ORDER_KEY_CONSTRAINT = "uk_payment_approved_order_key";

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
	 * cause 체인의 Hibernate ConstraintViolationException에서 uk_payment_approved_order_key 위반 여부를 확인한다.
	 * 일치하는 제약을 찾지 못하면 false를 반환해 원 예외를 그대로 전파한다(보수적 원칙).
	 *
	 * SQLErrorCodeSQLExceptionTranslator 빈 제거 후 unique 위반은 DataIntegrityViolationException
	 * (cause=Hibernate ConstraintViolationException(cause=SQLException))으로 올라온다.
	 * getConstraintName() 값이 테이블 prefix를 포함할 수 있으므로(tbl_payment.uk_...) 마지막 dot-세그먼트를 추출해
	 * prefix 있는 형태와 bare 형태 양쪽을 흡수한다.
	 */
	private static boolean isApprovedOrderKeyViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintEx) {
				String constraintName = constraintEx.getConstraintName();
				if (constraintName == null) {
					return false;
				}
				int dotIndex = constraintName.lastIndexOf('.');
				String simpleName = dotIndex >= 0 ? constraintName.substring(dotIndex + 1) : constraintName;
				return APPROVED_ORDER_KEY_CONSTRAINT.equals(simpleName);
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
