package com.commerce.payment.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

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

	// uk_payment_approved_order_key 위반을 SQLException 메시지에서 식별한다.
	// \b 단어 경계로 uk_payment_approved_order_key_v2 같은 prefix 공유 이름의 오탐을 막는다.
	private static final Pattern APPROVED_ORDER_KEY_CONSTRAINT =
		Pattern.compile("\\buk_payment_approved_order_key\\b", Pattern.CASE_INSENSITIVE);

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
	 * cause 체인의 JDBC SQLException 메시지에서 uk_payment_approved_order_key 위반 여부를 확인한다.
	 * 일치하는 제약을 찾지 못하면 false를 반환해 원 예외를 그대로 전파한다(보수적 원칙).
	 *
	 * JpaConfig의 SQLErrorCodeSQLExceptionTranslator 설정에서 unique 위반은 DuplicateKeyException(cause=SQLException)으로
	 * 변환되어 Hibernate ConstraintViolationException이 cause 체인에 남지 않으므로, 제약명은 SQLException 메시지에서만
	 * 얻을 수 있다. (translator 재검토는 후속 과제) 단어 경계 매칭으로 대소문자·prefix 공유 제약명의 오탐을 함께 막는다.
	 */
	private static boolean isApprovedOrderKeyViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof java.sql.SQLException sqlEx) {
				String msg = sqlEx.getMessage();
				if (msg != null && APPROVED_ORDER_KEY_CONSTRAINT.matcher(msg).find()) {
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
