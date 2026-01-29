package com.commerce.payment.provider;

import com.commerce.payment.domain.PaymentProvider;

public interface PaymentProviderProperties {

	PaymentProvider getProvider();

	String getClientId();

	String getClientSecret();

	String getChainId();

	String getReturnUrl();
}
