package com.commerce.payment.application.usecase;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아직 안 나간 환불을 훑어 보낸다. 주기 실행으로만 돌고 밖에서 부를 수 있는 경로가 없다.
 *
 * <p>이 자리가 없으면 발송이 실패한 환불이 영영 안 나간다. 주문 취소와 승인 반려는 커밋 뒤에 한 번
 * 부르고 마는데, 그 호출이 실패하면 환불이 접수 상태로 남기 때문이다.
 *
 * <p>조회에 시간 조건이 없다. 이 상태로 오는 것은 시도 번호가 0인 건과 사람이 되살린 건뿐이라 되풀이
 * 나갈 자리가 없다 — 재전송은 상태를 되돌리지 않아 이 조회를 지나가지 않는다.
 *
 * <p>결과를 모르는 건을 다시 보내는 것은 이 배치가 하지 않는다. 그것은 이력을 읽은 그 자리에서 해야
 * 확인이 낡지 않는다.
 *
 * <p>트랜잭션을 열지 않는다. 건마다 단위작업이 따로 커밋되어 한 건이 실패해도 나머지가 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchRefundUseCase {

	/** 한 주기에 보내는 상한. 다음 주기가 나머지를 이어 보낸다 */
	private static final int BATCH_SIZE = 100;

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final ExecuteRefundUseCase executeRefundUseCase;

	public void dispatch() {
		List<Refund> targets = refundRepository.findDispatchTargets(PageRequest.of(0, BATCH_SIZE));
		if (targets.isEmpty()) {
			return;
		}

		log.info("환불 발송 시작 targets={}", targets.size());
		for (Refund target : targets) {
			try {
				send(target);
			} catch (Exception ex) {
				log.error("환불 발송 실패 refundId={} paymentId={}", target.getId(), target.getPaymentId(), ex);
			}
		}
	}

	/**
	 * 환불이 결제를 식별자로 참조하므로 보낼 때 그 결제를 함께 읽는다. 결제사를 부르는 데 필요한 값이
	 * 결제 행에 있기 때문이며, 이 조회로 한도가 바뀌지 않아 결제 버전도 오르지 않는다.
	 */
	private void send(Refund refund) {
		Payment payment = paymentRepository.findById(refund.getPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
		executeRefundUseCase.send(payment, refund, PgCallSource.BATCH);
	}
}
