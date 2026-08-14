package com.commerce.payment.infrastructure.pg.naverpay.code;

import java.util.Optional;

import com.commerce.payment.application.port.dto.PgOutcome;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 승인 응답 코드를 네 갈래로 접는 표.
 *
 * <p>목록에 없는 코드는 여기서 값을 찾지 못하고, 부르는 쪽이 그것을 결과 불명으로 접는다 — 실패로
 * 접으면 실제로 나간 돈을 안 나간 것으로 다루게 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ApproveCode {

	SUCCESS("Success", PgOutcome.SUCCEEDED, "성공"),

	/**
	 * 같은 결제 번호로 승인이 이미 진행 중이다. 우리 요청이 처리되는 중일 수 있어 실패로 볼 수 없다.
	 */
	ALREADY_ON_GOING("AlreadyOnGoing", PgOutcome.UNKNOWN, "이미 진행 중인 결제"),
	/**
	 * 같은 결제 번호로 승인이 이미 끝났다. 승인이 났다는 뜻이지만 금액도 거래 번호도 주지 않아 이 응답
	 * 만으로는 확정할 수 없고, 이력을 읽어야 내용이 채워진다.
	 */
	ALREADY_COMPLETE("AlreadyComplete", PgOutcome.UNKNOWN, "이미 완료된 결제"),

	/** 가맹점 설정이 틀린 것이라 개별 건을 사람에게 넘길 일이 아니다. 고치면 밀린 건이 함께 풀린다 */
	INVALID_MERCHANT("InvalidMerchant", PgOutcome.RETRYABLE_FAILURE, "유효하지 않은 가맹점"),
	BANK_MAINTENANCE("BankMaintenance", PgOutcome.RETRYABLE_FAILURE, "충전 계좌 점검"),
	MAINTENANCE_ONGOING("MaintenanceOngoing", PgOutcome.RETRYABLE_FAILURE, "서비스 점검 중"),
	FAULT_CHECK_ONGOING("FaultCheckOngoing", PgOutcome.RETRYABLE_FAILURE, "원천사 시스템 점검 중"),

	/** 승인 가능 시간이 지났다. 이 답이 승인 실패를 확정하는 유일한 근거다 */
	TIME_EXPIRED("TimeExpired", PgOutcome.TERMINAL_FAILURE, "승인 가능 시간 초과"),
	OWNER_AUTH_FAIL("OwnerAuthFail", PgOutcome.TERMINAL_FAILURE, "본인 카드 인증 실패"),
	NOT_ENOUGH_ACCOUNT_BALANCE("NotEnoughAccountBalance", PgOutcome.TERMINAL_FAILURE, "충전 계좌 잔고 부족"),
	FAIL("Fail", PgOutcome.TERMINAL_FAILURE, "결제사·은행 오류로 승인 거절");

	private final String code;
	private final PgOutcome outcome;
	private final String description;

	public static Optional<ApproveCode> from(String code) {
		for (ApproveCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
