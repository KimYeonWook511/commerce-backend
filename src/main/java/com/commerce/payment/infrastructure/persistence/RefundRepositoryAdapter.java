package com.commerce.payment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.exception.DuplicateRefundRequestException;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RefundRepositoryAdapter implements RefundRepository {

	/** 결제·요청자·요청 키 셋으로 걸린 유일 제약. 같은 요청이 두 번 들어온 것을 이 이름으로 가른다. */
	private static final String UK_REFUND_PAYMENT_IDEMPOTENCY = "uk_refund_payment_idempotency";

	private final JpaRefundRepository jpaRefundRepository;

	/**
	 * 같은 요청 키의 환불이 이미 있으면 그것만 도메인 예외로 옮긴다. 나머지 무결성 위반(필수값 누락·
	 * 외래 키 등)은 그대로 올려 보내 안전망이 받게 한다 — 그것들은 회원이 할 수 있는 일이 없는 결함이고,
	 * 같은 응답으로 접으면 조사할 근거가 사라진다.
	 *
	 * <p>제약 이름을 볼 수 있는 것이 이 자리뿐이라 여기서 가른다. 위쪽은 무결성 위반이 하나의 기술
	 * 예외로 뭉쳐 도착해 무엇에 부딪혔는지 알 수 없다.
	 */
	@Override
	public Refund save(Refund refund) {
		try {
			return jpaRefundRepository.saveAndFlush(refund);
		} catch (DataIntegrityViolationException ex) {
			if (violates(ex, UK_REFUND_PAYMENT_IDEMPOTENCY)) {
				throw new DuplicateRefundRequestException();
			}
			throw ex;
		}
	}

	private boolean violates(DataIntegrityViolationException ex, String constraintName) {
		return ex.getCause() instanceof ConstraintViolationException cause
			&& cause.getConstraintName() != null
			&& cause.getConstraintName().toLowerCase().contains(constraintName);
	}

	/**
	 * 낙관 락 충돌을 이 호출 안에서 확정한다. 환불은 자기 락을 가지므로 같은 환불에 대한 동시 변경이
	 * 여기서 걸리고, 물러날지 전파할지는 트랜잭션 밖의 호출자가 정한다.
	 */
	@Override
	public Refund saveChecked(Refund refund) {
		try {
			return jpaRefundRepository.saveAndFlush(refund);
		} catch (ObjectOptimisticLockingFailureException ex) {
			throw new PaymentException(PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED);
		}
	}

	@Override
	public Optional<Refund> findById(Long id) {
		return jpaRefundRepository.findById(id);
	}

	@Override
	public Optional<Refund> findByPaymentIdAndRequesterAndIdempotencyKey(
		Long paymentId,
		RefundRequester requester,
		String idempotencyKey
	) {
		return jpaRefundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			paymentId, requester, idempotencyKey);
	}

	@Override
	public Optional<Refund> findSystemRefundByPaymentId(Long paymentId) {
		return jpaRefundRepository.findByPaymentIdAndRequester(paymentId, RefundRequester.SYSTEM);
	}

	@Override
	public List<Refund> findUnsettledByPaymentId(Long paymentId) {
		return jpaRefundRepository.findUnsettledByPaymentId(paymentId);
	}

	@Override
	public List<Refund> findDispatchTargets(Pageable pageable) {
		return jpaRefundRepository.findDispatchTargets(pageable);
	}

	@Override
	public List<Refund> findInProgressReconcileTargets(
		LocalDateTime requestedBefore,
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	) {
		return jpaRefundRepository.findInProgressReconcileTargets(
			requestedBefore, minReconcileCount, maxReconcileCount, reconciledBefore, pageable);
	}

	@Override
	public List<Refund> findUnknownReconcileTargets(
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	) {
		return jpaRefundRepository.findUnknownReconcileTargets(
			minReconcileCount, maxReconcileCount, reconciledBefore, pageable);
	}

	@Override
	public List<Refund> findNotifyTargets(
		LocalDateTime createdBefore,
		LocalDateTime notifiedBefore,
		Pageable pageable
	) {
		return jpaRefundRepository.findNotifyTargets(createdBefore, notifiedBefore, pageable);
	}
}
