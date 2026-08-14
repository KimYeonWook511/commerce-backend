package com.commerce.payment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
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

	/**
	 * 낙관 락 충돌을 이 호출 안에서 확정한다. flush를 어댑터 프레임으로 당겨야 충돌이 여기서 터지고,
	 * 그것을 도메인 예외로 바꿔 트랜잭션 밖의 호출자가 물러날지 전파할지 정할 수 있다.
	 * 충돌 뒤 트랜잭션은 rollback-only이므로 catch 블록에서 추가 쓰기를 하지 않는다.
	 */
	@Override
	public Payment saveChecked(Payment payment) {
		try {
			return jpaPaymentRepository.saveAndFlush(payment);
		} catch (ObjectOptimisticLockingFailureException ex) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED);
		}
	}

	/**
	 * 이 저장이 뒤 저장보다 먼저 DB에 나가게 한다. Hibernate는 코드에 적힌 줄 순서로 쓰기를 내보내지
	 * 않고 삽입을 갱신보다 먼저 내보내므로, 앞 결제의 슬롯을 비우는 갱신을 먼저 적어도 새 결제의 삽입이
	 * 앞질러 도착해 아직 차 있는 슬롯에 부딪힌다.
	 */
	@Override
	public Payment saveFlushed(Payment payment) {
		return jpaPaymentRepository.saveAndFlush(payment);
	}

	@Override
	public Optional<Payment> findById(Long id) {
		return jpaPaymentRepository.findById(id);
	}

	@Override
	public Optional<Payment> findActiveByOrderId(Long orderId) {
		return jpaPaymentRepository.findByActiveOrderKey(orderId);
	}

	@Override
	public Optional<Payment> findByPaymentKeyAndMemberId(String paymentKey, Long memberId) {
		return jpaPaymentRepository.findByPaymentKeyAndMemberId(paymentKey, memberId);
	}

	@Override
	public Optional<Payment> findByPaymentKey(String paymentKey) {
		return jpaPaymentRepository.findByPaymentKey(paymentKey);
	}

	@Override
	public boolean existsUnknownByOrderId(Long orderId) {
		return jpaPaymentRepository.existsUnknownByOrderId(orderId);
	}

	@Override
	public Optional<Payment> findSucceededByMemberIdAndOrderId(Long memberId, Long orderId) {
		return jpaPaymentRepository.findSucceededByMemberIdAndOrderId(memberId, orderId);
	}

	@Override
	public Set<Long> findOrderIdsHoldingActiveSlot(Collection<Long> orderIds) {
		if (orderIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jpaPaymentRepository.findActiveOrderKeysIn(orderIds));
	}

	@Override
	public List<Payment> findInProgressReconcileTargets(
		LocalDateTime requestedBefore,
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	) {
		return jpaPaymentRepository.findInProgressReconcileTargets(
			requestedBefore, minReconcileCount, maxReconcileCount, reconciledBefore, pageable);
	}

	@Override
	public List<Payment> findUnknownReconcileTargets(
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	) {
		return jpaPaymentRepository.findUnknownReconcileTargets(
			minReconcileCount, maxReconcileCount, reconciledBefore, pageable);
	}

	@Override
	public List<Payment> findNotifyTargets(
		LocalDateTime createdBefore,
		LocalDateTime notifiedBefore,
		Pageable pageable
	) {
		return jpaPaymentRepository.findNotifyTargets(createdBefore, notifiedBefore, pageable);
	}

	@Override
	public List<Payment> findExpireTargets(LocalDateTime createdBefore, Pageable pageable) {
		return jpaPaymentRepository.findExpireTargets(createdBefore, pageable);
	}
}
