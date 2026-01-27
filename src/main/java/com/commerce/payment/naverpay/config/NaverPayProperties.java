package com.commerce.payment.naverpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.provider.PaymentProviderProperties;

@Component
public class NaverPayProperties implements PaymentProviderProperties {

	@Value("${naverpay.client-id}")
	private String clientId;

	@Value("${naverpay.chain-id}")
	private String chainId;

	@Value("${naverpay.return-url}")
	private String returnUrl;

	@Override
	public PaymentProvider getProvider() {
		return PaymentProvider.NAVERPAY;
	}

	@Override
	public String getClientId() {
		return clientId;
	}

	@Override
	public String getChainId() {
		return chainId;
	}

	@Override
	public String getReturnUrl() {
		return returnUrl;
	}
}
