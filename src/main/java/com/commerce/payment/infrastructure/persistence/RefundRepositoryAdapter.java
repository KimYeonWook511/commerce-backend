package com.commerce.payment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RefundRepositoryAdapter implements RefundRepository {

	private final JpaRefundRepository jpaRefundRepository;

	@Override
	public Refund save(Refund refund) {
		return jpaRefundRepository.saveAndFlush(refund);
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
