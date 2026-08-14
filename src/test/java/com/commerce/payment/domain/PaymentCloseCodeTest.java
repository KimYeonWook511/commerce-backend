package com.commerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentCloseCodeTest {

	@DisplayName("종결 코드 하나는 종결 상태 하나만 가리킨다 — 값만 보고도 어느 종착인지 안다")
	@Test
	void closedStatus_isDeterminedByCloseCodeAlone() {
		Map<PaymentStatus, List<PaymentCloseCode>> byStatus = Arrays.stream(PaymentCloseCode.values())
			.collect(Collectors.groupingBy(PaymentCloseCode::closedStatus));

		assertThat(byStatus.keySet())
			.containsExactlyInAnyOrder(PaymentStatus.FAILED, PaymentStatus.REJECTED, PaymentStatus.EXPIRED);
		assertThat(byStatus.values().stream().mapToInt(List::size).sum())
			.isEqualTo(PaymentCloseCode.values().length);
	}

	@DisplayName("종결 코드가 가리키는 상태는 셋 다 종착이고 활성 슬롯을 반납한다")
	@Test
	void closedStatus_isTerminalAndReleasesActiveSlot() {
		for (PaymentCloseCode closeCode : PaymentCloseCode.values()) {
			assertThat(closeCode.closedStatus().isTerminal())
				.as("%s 는 종착으로 이어진다", closeCode)
				.isTrue();
			assertThat(closeCode.closedStatus().holdsActiveSlot())
				.as("%s 로 종결하면 활성 슬롯을 반납한다", closeCode)
				.isFalse();
		}
	}

	@DisplayName("반려의 종결 코드 둘이 승인 반려 두 경우와 그대로 대응한다")
	@Test
	void rejectedCloseCodes_matchTheTwoRejectionCases() {
		List<PaymentCloseCode> rejected = Arrays.stream(PaymentCloseCode.values())
			.filter(closeCode -> closeCode.closedStatus() == PaymentStatus.REJECTED)
			.toList();

		assertThat(rejected).containsExactlyInAnyOrder(
			PaymentCloseCode.AMOUNT_MISMATCH,
			PaymentCloseCode.ORDER_NOT_PAYABLE
		);
	}
}
