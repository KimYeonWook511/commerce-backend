package com.commerce.payment.application.usecase;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.dto.ApprovalOutcome;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryEntryType;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.service.PaymentApprovalService;
import com.commerce.payment.domain.Payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 성립한 승인을 받아들일지 정하고, 받아들이면 결제 성공과 주문 완료를 한 트랜잭션으로 커밋한다.
 *
 * <p>밖에서 불리지 않는다. 회원의 결제창 복귀와 대사가 이 자리를 공유하며, 각자 갖고 있으면 거부
 * 갈래가 두 벌이 되어 한쪽만 고쳤을 때 돈이 나가고 안 나가고가 진입점에 따라 갈린다.
 *
 * <p>트랜잭션을 열지 않는다 — 결제사 호출과 운영자 통지가 단위작업 사이에 끼기 때문이다.
 *
 * <p>빈 이름을 옛 연동과 겹치지 않게 둔다. 둘이 나란히 사는 동안 같은 이름을 쓰면 컨테이너가 뜨지
 * 않는다.
 */
@Slf4j
@Component("paymentConfirmApprovalUseCase")
@RequiredArgsConstructor
public class ConfirmApprovalUseCase {

	private final PaymentApprovalService paymentApprovalService;
	private final ClosePaymentUseCase closePaymentUseCase;

	/**
	 * 승인 금액과 거래 번호를 받아 확정을 시도한다. 금액이 어긋나거나 주문이 그 결제를 받을 수 없으면
	 * 종결 흐름으로 넘긴다.
	 */
	public ApprovalOutcome confirm(Payment payment, int approvedAmount, String pgTransactionId) {
		if (approvedAmount <= 0) {
			// 정상 경로에서 나올 수 없는 값이라 해석할 수 없는 응답으로 다룬다. 담아 두면 한도가
			// 처음부터 0이라 되돌릴 환불을 만들 수 없는 행이 남는다.
			log.warn("승인 금액이 0보다 크지 않아 확정하지 않는다 paymentId={} approvedAmount={}",
				payment.getId(), approvedAmount);
			return ApprovalOutcome.unresolved();
		}
		if (approvedAmount != payment.getAmount()) {
			return closePaymentUseCase.rejectAmountMismatch(payment, approvedAmount, pgTransactionId);
		}
		try {
			paymentApprovalService.complete(payment.getId(), payment.getOrderId(), approvedAmount, pgTransactionId);
			return ApprovalOutcome.succeeded();
		} catch (OrderException ex) {
			return closePaymentUseCase.rejectOrderNotPayable(
				payment, ex.getErrorCode(), approvedAmount, pgTransactionId);
		}
	}

	/**
	 * 이력을 읽어 확정한다. 결제사가 "이미 처리된 건"이라 답했을 때와 대사가 결과를 회수할 때가 이
	 * 자리를 함께 쓴다.
	 *
	 * <p>목록의 특정 위치를 보지 않고, 우리 결제 키가 실린 성공한 원결제 항목을 찾는다. 이력이 비었다는
	 * 것만으로 실패를 확정하지 않는다 — 돈이 안 나간 것과 결제사가 아직 반영하지 않은 것을 구분하지
	 * 못한다.
	 */
	public ApprovalOutcome confirmFromHistory(Payment payment, PgHistoryResult history) {
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			return ApprovalOutcome.unresolved();
		}

		Optional<PgHistoryEntry> approval = succeededEntries(history, PgHistoryEntryType.APPROVAL).stream()
			.filter(entry -> payment.getPaymentKey().equals(entry.paymentKey()))
			.findFirst();
		if (approval.isEmpty()) {
			return ApprovalOutcome.unresolved();
		}

		// 성공한 취소 항목만 본다. 실패한 취소도 한 줄로 남으므로 그것을 근거로 닫으면 돈이 나가 있는
		// 결제를 닫고 슬롯을 반납해 회원이 다시 결제할 때 돈이 두 번 나간다.
		List<PgHistoryEntry> refunds = succeededEntries(history, PgHistoryEntryType.REFUND);
		Optional<PgHistoryEntry> foreign = refunds.stream()
			.filter(entry -> !StringUtils.hasText(entry.refundAttemptKey()))
			.findFirst();
		if (foreign.isPresent()) {
			return closePaymentUseCase.closeExternallyCanceled(payment, approval.get(), foreign.get());
		}
		if (!refunds.isEmpty()) {
			// 우리 환불 시도 키가 실려 있다. 그 결제는 자기 경로로 풀리므로 여기서 확정하지 않는다.
			log.info("이력의 취소가 우리 환불이라 확정하지 않는다 paymentId={}", payment.getId());
			return ApprovalOutcome.unresolved();
		}

		return confirm(payment, approval.get().amount(), approval.get().pgTransactionId());
	}

	private List<PgHistoryEntry> succeededEntries(PgHistoryResult history, PgHistoryEntryType type) {
		return history.entries().stream()
			.filter(entry -> entry.type() == type)
			.filter(PgHistoryEntry::succeeded)
			.toList();
	}
}
