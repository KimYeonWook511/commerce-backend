package com.commerce.payment.application.usecase;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.dto.ApprovalOutcome;
import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.dto.PgApproveResult;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmApprovalUseCase {

	private final PaymentApprovalService paymentApprovalService;
	private final ClosePaymentUseCase closePaymentUseCase;
	private final NotificationPort notificationPort;

	/**
	 * 승인이 성립했다는 응답을 받아 확정을 시도한다. 응답에 실려 온 결제 키와 회원이 이 결제의 것인지
	 * 먼저 대조한다 — 대조 없이 확정하면 남의 돈으로 이 주문이 결제된다.
	 *
	 * <p>회원의 결제창 복귀와 대사의 승인 재요청이 이 자리를 함께 쓴다. 대사에만 따로 두면 대조가 두
	 * 벌이 되어 한쪽만 고쳐졌을 때 어느 진입점으로 왔는지에 따라 방어가 갈린다.
	 */
	public ApprovalOutcome confirmApproved(Payment payment, PgApproveResult result) {
		if (!payment.getPaymentKey().equals(result.paymentKey())) {
			// 그 응답에는 상대의 결제 키와 결제사 번호가 들어 있고, 상대의 결제가 방금 승인됐다는 뜻이다.
			return closePaymentUseCase.closeKeyMismatch(payment, result.paymentKey(), payment.getPgPaymentId());
		}
		if (!ownsMemberKey(payment, result.memberKey())) {
			// 결제 키는 맞는데 회원만 어긋났다. 두 기록 중 하나가 틀린 상태라 자동으로 정할 근거가 없다 —
			// 종결하면 슬롯이 풀려 돈이 두 번 나갈 수 있고, 되돌리면 그 돈의 주인을 단정하는 것이 된다.
			notificationPort.notifyManualReviewRequired(
				payment.getOrderId(), payment.getPaymentKey(), "승인 응답의 회원이 결제 행의 회원과 다르다");
			return ApprovalOutcome.manualReview();
		}
		return confirm(payment, result.approvedAmount(), result.pgTransactionId());
	}

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
	 *
	 * <p>이력 항목에는 회원 값이 실려 오지 않아 대조가 결제 키 하나로 끝난다. 회원까지 대조하는 것은
	 * 승인 응답을 받는 자리의 몫이다.
	 */
	public ApprovalOutcome confirmFromHistory(Payment payment, PgHistoryResult history) {
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			return ApprovalOutcome.unresolved();
		}

		List<PgHistoryEntry> approvals = succeededEntries(history, PgHistoryEntryType.APPROVAL);
		Optional<PgHistoryEntry> approval = approvals.stream()
			.filter(entry -> payment.getPaymentKey().equals(entry.paymentKey()))
			.findFirst();
		if (approval.isEmpty()) {
			return approvals.stream()
				.filter(entry -> StringUtils.hasText(entry.paymentKey()))
				.findFirst()
				// 우리가 든 결제사 번호로 남의 승인이 성립해 있다. 승인 요청에 남의 번호가 실렸던
				// 경우이며, 응답으로 같은 것을 발견했을 때와 같은 자리에서 처리한다.
				.map(foreign -> closePaymentUseCase.closeKeyMismatch(
					payment, foreign.paymentKey(), payment.getPgPaymentId()))
				.orElseGet(ApprovalOutcome::unresolved);
		}

		// 성공한 취소 항목만 본다. 실패한 취소도 한 줄로 남으므로 그것까지 세면 나가지도 않은 취소를
		// 근거로 확정을 미루게 된다.
		List<PgHistoryEntry> refunds = succeededEntries(history, PgHistoryEntryType.REFUND);
		if (refunds.stream().anyMatch(entry -> StringUtils.hasText(entry.refundAttemptKey()))) {
			// 우리 환불 시도 키가 실려 있다. 그 결제는 자기 경로로 풀리므로 여기서 확정하지 않는다.
			log.info("이력의 취소가 우리 환불이라 확정하지 않는다 paymentId={}", payment.getId());
			return ApprovalOutcome.unresolved();
		}

		// 우리 것이 아닌 취소는 알리기만 하고 확정을 막지 않는다. 얼마가 돌아갔는지는 결제사만 알고
		// 우리에게 알려 주는 채널이 없어, 이 항목 하나를 전액 취소로 읽고 종결하면 일부만 돌아간 결제까지
		// 닫혀 슬롯이 열린다. 잔액이 모자란 것은 나중에 환불을 보낼 때 결제사가 거절하며 드러난다.
		Optional<PgHistoryEntry> foreign = refunds.stream()
			.filter(entry -> !StringUtils.hasText(entry.refundAttemptKey()))
			.findFirst();

		ApprovalOutcome outcome = confirm(payment, approval.get().amount(), approval.get().pgTransactionId());
		// 확정이 커밋된 뒤에 알린다. 먼저 알리면 그 실패가 확정을 막아, 알리기만 한다는 이 자리의 뜻과
		// 반대로 결제가 결과 불명에 남는다.
		foreign.ifPresent(entry -> notificationPort.notifyManualReviewRequired(
			payment.getOrderId(), payment.getPaymentKey(),
			"우리가 모르는 경로로 취소된 승인 시각=" + entry.occurredAt() + " 금액=" + entry.amount()));
		return outcome;
	}

	/**
	 * 결제 예약 때 보낸 회원 식별자가 응답에 실려 온다. 값이 없으면 대조할 것이 없어 통과시킨다 —
	 * 결제창을 여는 쪽이 그 값을 싣지 않으면 응답에도 담기지 않는다.
	 */
	private boolean ownsMemberKey(Payment payment, String memberKey) {
		return !StringUtils.hasText(memberKey) || memberKey.equals(String.valueOf(payment.getMemberId()));
	}

	private List<PgHistoryEntry> succeededEntries(PgHistoryResult history, PgHistoryEntryType type) {
		return history.entries().stream()
			.filter(entry -> entry.type() == type)
			.filter(PgHistoryEntry::succeeded)
			.toList();
	}
}
