package com.commerce.payment.application.port.dto;

import java.util.List;
import java.util.Optional;

import com.commerce.payment.domain.Refund;

/**
 * 이력 조회의 결과.
 *
 * <p>"목록이 비었다"와 "조회가 거절됐다"가 갈린다 — 앞은 갈래가 성공이고 항목이 없는 것이고, 뒤는
 * 갈래 자체가 실패다. 안 가르면 인증 설정이 틀린 순간 멀쩡한 결제가 전부 실패로 확정된다.
 *
 * <p>호출 기록에 담을 값을 싣지 않는다. 이력 조회는 결제사 상태를 바꾸려는 요청이 아니라 확인이라
 * 그 기록에 쌓지 않기로 했고, 섞으면 몇 번 시도했는지가 조회 횟수에 묻힌다.
 *
 * @param outcome 네 갈래 중 하나
 * @param entries 성공했을 때 읽어 온 항목. 그 밖의 갈래에서는 비어 있다
 * @param message 결제사가 준 문구
 */
public record PgHistoryResult(
	PgOutcome outcome,
	List<PgHistoryEntry> entries,
	String message
) {

	public static PgHistoryResult succeeded(List<PgHistoryEntry> entries, String message) {
		return new PgHistoryResult(PgOutcome.SUCCEEDED, List.copyOf(entries), message);
	}

	public static PgHistoryResult failed(PgOutcome outcome, String message) {
		return new PgHistoryResult(outcome, List.of(), message);
	}

	/**
	 * 그 환불 사건의 시도 중 성공으로 남은 항목. 이력이 그 사건을 완료로 설명하는지가 이 값의 유무로
	 * 갈리며, 판정이 두 자리로 갈리지 않게 여기 하나에 둔다.
	 *
	 * <p>항목의 성공 여부를 먼저 본다. 실패한 시도도 이력에 한 줄로 남으므로 존재만으로 완료를 단정하면
	 * 나가지 않은 돈을 나간 것으로 확정한다.
	 *
	 * <p>어느 항목이 그 사건의 것인지는 환불 자신이 가른다 — 시도 키를 만드는 규칙과 맞추는 규칙이 갈리면
	 * 이력에서 우리 시도를 못 찾고 그 사실이 조용히 지나간다.
	 */
	public Optional<PgHistoryEntry> settledRefundOf(Refund refund) {
		return entries.stream()
			.filter(entry -> entry.type() == PgHistoryEntryType.REFUND)
			.filter(PgHistoryEntry::succeeded)
			.filter(entry -> refund.ownsHistoryEntry(entry.refundAttemptKey()))
			.findFirst();
	}
}
