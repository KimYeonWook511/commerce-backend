package com.commerce.payment.infrastructure.persistence;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.commerce.order.application.port.BlockingPaymentChecker;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 주문이 자기 만료 배치가 무엇에 막히는지 부르는 이름으로 물어오면, 결제의 말인 승인을 부른 뒤의 결제로
 * 찾아 돌려준다. 인터페이스 이름을 결제의 말로 바꾸지 않는 것은 그 이름을 정하는 쪽이 주문이기 때문이고,
 * 그 번역이 이 어댑터의 일이다.
 *
 * <p>판정 기준은 승인을 부른 뒤의 결제다 — 승인 호출 중·결과 불명·성공이 걸린 주문을 막힌 것으로 본다.
 * 그 승인이 성공이면 돈은 받고 주문은 취소된 상태가 되어 되돌릴 수 없다. 승인을 아직 부르지 않은 결제는
 * 막지 않는다 — 주문 만료가 그 결제를 함께 종결한다.
 *
 * <p>이 집합은 만료 반영에서 결제가 종결할 수 없다고 답하는 집합과 같아야 한다. 어긋나도 반영 시점의 그
 * 답이 돈을 지키지만, 한 말이 두 집합을 가리키면 둘을 맞추는 변경이 성공한 결제가 걸린 주문을 만료로
 * 넘길 수 있다.
 */
@Component
@RequiredArgsConstructor
public class BlockingPaymentCheckerAdapter implements BlockingPaymentChecker {

	private final PaymentRepository paymentRepository;

	@Override
	public Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds) {
		return paymentRepository.findOrderIdsWithPaymentPastApprovalCall(orderIds);
	}
}
