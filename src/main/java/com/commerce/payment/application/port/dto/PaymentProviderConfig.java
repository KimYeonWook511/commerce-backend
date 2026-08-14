package com.commerce.payment.application.port.dto;

/**
 * 결제창을 띄우는 데 필요한 결제사 설정. 클라이언트가 결제사 SDK에 그대로 넘기는 값들이다.
 *
 * <p>인증 비밀값은 담지 않는다. 결제사를 부르는 것은 어댑터뿐이라 그 값이 응용 계층까지 올라올
 * 이유가 없다.
 */
public record PaymentProviderConfig(
	String clientId,
	String chainId,
	String returnUrl
) {
}
