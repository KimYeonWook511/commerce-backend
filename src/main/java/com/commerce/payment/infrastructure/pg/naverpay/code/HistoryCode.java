package com.commerce.payment.infrastructure.pg.naverpay.code;

import java.util.Optional;

import com.commerce.payment.application.port.dto.PgOutcome;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이력 조회 응답 코드를 네 갈래로 접는 표.
 *
 * <p>조회가 거절된 것과 목록이 빈 것은 다른 일이다. 거절을 빈 목록으로 다루면 인증 설정이 틀린 순간
 * 멀쩡한 결제가 전부 실패로 확정되므로, 성공이 아닌 코드는 반드시 실패 갈래로 나간다.
 */
@Getter
@RequiredArgsConstructor
public enum HistoryCode {

	SUCCESS("Success", PgOutcome.SUCCEEDED, "성공"),

	/** 설정을 고치면 밀린 건이 함께 풀린다 */
	INVALID_MERCHANT("InvalidMerchant", PgOutcome.RETRYABLE_FAILURE, "유효하지 않은 가맹점"),
	MAINTENANCE_ONGOING("MaintenanceOngoing", PgOutcome.RETRYABLE_FAILURE, "서비스 점검 중"),
	/** 사유를 알려주지 않는 실패다. 돈을 움직이는 호출이 아니므로 다음 주기에 다시 읽는다 */
	FAIL("Fail", PgOutcome.RETRYABLE_FAILURE, "이력 조회 실패"),

	/** 우리가 보낸 값이 조건에 맞지 않는다. 같은 값으로 다시 물어도 같은 답이 온다 */
	REQUIRE_CONDITION("RequireCondition", PgOutcome.TERMINAL_FAILURE, "입력값이 조건을 만족하지 않음");

	private final String code;
	private final PgOutcome outcome;
	private final String description;

	public static Optional<HistoryCode> from(String code) {
		for (HistoryCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
