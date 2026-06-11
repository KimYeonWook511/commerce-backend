package com.commerce.payment.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
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
	 * cause 체인의 Hibernate ConstraintViolationException에서 uk_payment_approved_order_key 위반 여부를 확인한다.
	 * 일치하는 제약을 찾지 못하면 false를 반환해 원 예외를 그대로 전파한다(보수적 원칙).
	 *
	 * SQLErrorCodeSQLExceptionTranslator 빈 제거 후 unique 위반은 DataIntegrityViolationException
	 * (cause=Hibernate ConstraintViolationException(cause=SQLException))으로 올라온다.
	 * getConstraintName()이 MySQL에서 테이블 prefix를 포함한 형태(tbl_payment.uk_...)를 반환하므로 전체 이름으로 비교한다.
	 * 대소문자는 환경에 따라 달라질 수 있어 무시하고, cause 체인을 끝까지 순회한다.
	 */
	private static boolean isApprovedOrderKeyViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintEx) {
				if ("tbl_payment.uk_payment_approved_order_key".equalsIgnoreCase(constraintEx.getConstraintName())) {
					return true;
				}
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
	public boolean existsUnknownByOrderId(Long orderId) {
		return jpaPaymentRepository.existsByOrderIdAndTypeAndStatus(
			orderId, PaymentType.APPROVE, PaymentStatus.UNKNOWN
		);
	}

	@Override
	public boolean existsApprovedByOrderId(Long orderId) {
		return jpaPaymentRepository.existsByOrderIdAndTypeAndStatus(
			orderId, PaymentType.APPROVE, PaymentStatus.SUCCEEDED
		);
	}

	@Override
	public List<Payment> findStaleApprovePaymentsForReconciliation(LocalDateTime staleCutoff, LocalDateTime requestedStaleCutoff, LocalDateTime escalationCutoff, Pageable pageable) {
		return jpaPaymentRepository.findStaleApprovePaymentsForReconciliation(staleCutoff, requestedStaleCutoff, escalationCutoff, pageable);
	}

	@Override
	public List<Payment> findEscalationCandidates(LocalDateTime escalationCutoff, Pageable pageable) {
		return jpaPaymentRepository.findEscalationCandidates(escalationCutoff, pageable);
	}
}
