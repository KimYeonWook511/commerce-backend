package com.commerce.payment.naverpay.exception;

public enum NaverPayErrorCode {
	NETWORK("네이버페이 요청 중 네트워크 오류가 발생한 경우"),
	SERVER_ERROR("네이버페이 서버 오류로 요청 처리에 실패한 경우"),
	CLIENT_ERROR("잘못된 요청으로 네이버페이 호출에 실패한 경우"),
	AUTHENTICATION("인증 정보가 올바르지 않아 네이버페이 호출에 실패한 경우"),
	INVALID_RESPONSE("네이버페이 응답을 정상적으로 해석할 수 없는 경우");

	private final String description;

	NaverPayErrorCode(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
