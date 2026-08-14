package com.commerce.payment.infrastructure.persistence;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.commerce.order.application.port.BlockingPaymentChecker;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 주문이 자기 만료 배치가 무엇에 막히는지 부르는 이름으로 물어오면, 결제의 말인 활성 슬롯으로 찾아
 * 돌려준다. 인터페이스 이름을 결제의 말로 바꾸지 않는 것은 그 이름을 정하는 쪽이 주문이기 때문이고,
 * 그 번역이 이 어댑터의 일이다.
 *
 * <p>판정 기준이 승인 성공이 아니라 활성 슬롯이다 — 결제창을 띄운 것부터 결과를 모르는 것까지 전부
 * 막힌 것으로 본다. 결제창만 띄운 건을 빼면 회원이 결제창에서 인증하는 사이 주문이 만료되고, 그 뒤
 * 승인이 나가 돈이 빠졌다가 되돌려진다.
 */
@Component
@RequiredArgsConstructor
public class BlockingPaymentCheckerAdapter implements BlockingPaymentChecker {

	private final PaymentRepository paymentRepository;

	@Override
	public Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds) {
		return paymentRepository.findOrderIdsHoldingActiveSlot(orderIds);
	}
}
