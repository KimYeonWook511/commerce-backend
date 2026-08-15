package com.commerce.payment.infrastructure.pg.naverpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * 이 결제사를 부르는 데 필요한 설정. 패키지가 어느 결제사인지를 말하므로 이름에 넣지 않는다.
 */
@Component
@Getter
public class Properties {

	@Value("${payment.pg.naverpay.client-id}")
	private String clientId;

	@Value("${payment.pg.naverpay.client-secret}")
	private String clientSecret;

	@Value("${payment.pg.naverpay.chain-id}")
	private String chainId;

	@Value("${payment.pg.naverpay.return-url}")
	private String returnUrl;

	@Value("${payment.pg.naverpay.approval-url}")
	private String approvalUrl;

	@Value("${payment.pg.naverpay.cancel-url}")
	private String cancelUrl;

	/** 결제 번호 자리를 담은 주소. 부를 때 그 자리를 채운다 */
	@Value("${payment.pg.naverpay.history-url}")
	private String historyUrl;
}
