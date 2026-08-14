package com.commerce.payment.application.port.dto;

import java.util.List;

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
}
