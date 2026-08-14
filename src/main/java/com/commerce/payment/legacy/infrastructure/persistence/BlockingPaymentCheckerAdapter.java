package com.commerce.payment.legacy.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.commerce.order.application.port.BlockingPaymentChecker;

import lombok.RequiredArgsConstructor;

// 같은 port의 구현이 새 결제 모델에도 있어 빈으로 등록하지 않는다. 둘 다 등록되면 주입이 실패한다.
// legacy가 사라질 때 이 클래스도 함께 사라진다.
@RequiredArgsConstructor
public class BlockingPaymentCheckerAdapter implements BlockingPaymentChecker {

	private final JpaLegacyPaymentRepository jpaPaymentRepository;

	@Override
	public Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds) {
		if (orderIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jpaPaymentRepository.findOrderIdsWithBlockingPaymentIn(orderIds));
	}
}
