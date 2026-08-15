package com.commerce.payment.infrastructure.pg.naverpay.code;

import java.util.Optional;

import com.commerce.payment.application.port.dto.PgHistoryEntryType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이력 항목의 종류 코드를 우리 어휘로 옮기는 표.
 *
 * <p>부분취소가 반드시 들어간다. 나눠 환불하면 마지막 잔액을 남김없이 환불한 건까지 부분취소로
 * 기록되므로, 전체취소만 보면 부분환불이 섞인 결제의 환불을 하나도 알아보지 못한다.
 */
@Getter
@RequiredArgsConstructor
public enum AdmissionTypeCode {

	APPROVAL("01", PgHistoryEntryType.APPROVAL),
	FULL_CANCEL("03", PgHistoryEntryType.REFUND),
	PARTIAL_CANCEL("04", PgHistoryEntryType.REFUND);

	private final String code;
	private final PgHistoryEntryType entryType;

	public static Optional<AdmissionTypeCode> from(String code) {
		for (AdmissionTypeCode value : values()) {
			if (value.code.equals(code)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
