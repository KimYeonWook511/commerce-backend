package com.commerce.payment.infrastructure.persistence.support;

import java.util.List;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.infrastructure.persistence.JpaRefundRepository;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class RefundPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaRefundRepository jpaRefundRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.REFUND;
	}

	@Override
	public void deleteAllInBatch() {
		jpaRefundRepository.deleteAllInBatch();
	}

	public Refund save(Refund refund) {
		return jpaRefundRepository.saveAndFlush(refund);
	}

	public List<Refund> findAll() {
		return jpaRefundRepository.findAll();
	}
}
