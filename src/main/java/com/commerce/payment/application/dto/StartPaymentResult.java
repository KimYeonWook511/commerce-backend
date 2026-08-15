package com.commerce.payment.application.dto;

/**
 * 결제창을 여는 데 필요한 값. 클라이언트가 결제사 SDK에 그대로 넘기므로 항목이 하나라도 빠지거나
 * 늘면 안 된다.
 *
 * <p>{@code merchantPayKey}는 우리가 발급한 결제 키다. 이름이 결제사 규격에서 왔고 프론트가 그 이름으로
 * 읽으므로 응답에서는 그대로 두고, 우리 안에서는 결제 키로 부른다.
 */
public record StartPaymentResult(
	String clientId,
	String chainId,
	String merchantPayKey,
	String productName,
	int productCount,
	int totalPayAmount,
	int taxScopeAmount,
	int taxExScopeAmount,
	String returnUrl
) {
}
