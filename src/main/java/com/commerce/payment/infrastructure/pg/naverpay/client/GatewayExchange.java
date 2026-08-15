package com.commerce.payment.infrastructure.pg.naverpay.client;

import com.commerce.payment.domain.PgErrorType;

/**
 * 결제사를 한 번 부르고 돌아온 것. 응답을 받았는지, 못 받았다면 무엇 때문인지를 여기서 가른다.
 *
 * <p>응답 본문을 해석하지 않은 문자열 그대로 나른다 — 해석은 결과 코드 표를 가진 어댑터의 일이고,
 * 응답을 못 읽는 경우까지 여기서 다루면 전송과 해석이 한 자리에 섞인다.
 *
 * @param errorType  전송 단계에서 무슨 일이 있었나
 * @param httpStatus 응답을 받았을 때만 있다
 * @param rawBody    응답 본문. 응답을 못 받았으면 없다
 */
public record GatewayExchange(
	PgErrorType errorType,
	Integer httpStatus,
	String rawBody
) {

	static GatewayExchange responded(int httpStatus, String rawBody) {
		PgErrorType errorType = (httpStatus / 100 == 2) ? PgErrorType.NONE : PgErrorType.HTTP;
		return new GatewayExchange(errorType, httpStatus, rawBody);
	}

	static GatewayExchange notResponded(PgErrorType errorType) {
		return new GatewayExchange(errorType, null, null);
	}

	public boolean hasBody() {
		return rawBody != null && !rawBody.isBlank();
	}
}
