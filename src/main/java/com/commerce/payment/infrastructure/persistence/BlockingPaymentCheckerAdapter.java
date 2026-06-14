package com.commerce.payment.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.commerce.order.application.port.BlockingPaymentChecker;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlockingPaymentCheckerAdapter implements BlockingPaymentChecker {

	private final JpaPaymentRepository jpaPaymentRepository;

	@Override
	public Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds) {
		if (orderIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jpaPaymentRepository.findOrderIdsWithBlockingPaymentIn(orderIds));
	}
}
