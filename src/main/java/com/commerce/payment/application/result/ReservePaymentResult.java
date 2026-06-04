package com.commerce.payment.application.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ReservePaymentResult {

	private String clientId;
	private String chainId;
	private String merchantPayKey;
	private String productName;
	private int productCount;
	private int totalPayAmount;
	private int taxScopeAmount;
	private int taxExScopeAmount;
	private String returnUrl;

	@Builder
	private ReservePaymentResult(String clientId, String chainId, String merchantPayKey, String productName,
		int productCount, int totalPayAmount, int taxScopeAmount, int taxExScopeAmount, String returnUrl) {
		this.clientId = clientId;
		this.chainId = chainId;
		this.merchantPayKey = merchantPayKey;
		this.productName = productName;
		this.productCount = productCount;
		this.totalPayAmount = totalPayAmount;
		this.taxScopeAmount = taxScopeAmount;
		this.taxExScopeAmount = taxExScopeAmount;
		this.returnUrl = returnUrl;
	}
}
