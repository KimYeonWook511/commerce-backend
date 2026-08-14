package com.commerce.payment.infrastructure.pg.naverpay.client.response.body;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

/** 이력 조회 응답 본문. 전체 페이지 수를 함께 주므로 그 값을 보고 뒷 페이지를 이어 받는다 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryBody {

	private List<History> list;
	private int totalCount;
	private int responseCount;
	private int totalPageCount;
	private int currentPageNumber;

	@Getter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class History {

		private String paymentId;
		private String payHistId;
		private String merchantPayKey;
		/** 취소 요청에 실어 보낸 우리 환불 시도 키가 그대로 돌아온다 */
		private String merchantPayTransactionKey;
		/** 성공·실패 둘뿐이다. 처리 중을 뜻하는 값이 없다 */
		private String admissionState;
		/** 01 원결제 · 03 전체취소 · 04 부분취소 */
		private String admissionTypeCode;
		private String admissionYmdt;
		private int totalPayAmount;
	}
}
