package com.commerce.payment.naverpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.provider.PaymentProviderProperties;

@Component
public class NaverPayProperties implements PaymentProviderProperties {

	@Value("${naverpay.client-id}")
	private String clientId;

	@Value("${naverpay.client-secret}")
	private String clientSecret;

	@Value("${naverpay.chain-id}")
	private String chainId;

	@Value("${naverpay.return-url}")
	private String returnUrl;

	@Value("${naverpay.approval-url}")
	private String approvalUrl;

	@Value("${naverpay.cancel-url}")
	private String cancelUrl;

	@Value("${naverpay.payment-history-url}")
	private String paymentHistoryUrl;

	@Override
	public PaymentProvider getProvider() {
		return PaymentProvider.NAVERPAY;
	}

	@Override
	public String getClientId() {
		return clientId;
	}

	@Override
	public String getClientSecret() {
		return clientSecret;
	}

	@Override
	public String getChainId() {
		return chainId;
	}

	@Override
	public String getReturnUrl() {
		return returnUrl;
	}

	public String getApprovalUrl() {
		return approvalUrl;
	}

	public String getCancelUrl() {
		return cancelUrl;
	}

	public String getPaymentHistoryUrl() {
		return paymentHistoryUrl;
	}

}
