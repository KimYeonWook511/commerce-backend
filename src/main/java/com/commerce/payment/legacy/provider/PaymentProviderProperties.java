package com.commerce.payment.legacy.provider;

import com.commerce.payment.legacy.domain.PaymentProvider;

public interface PaymentProviderProperties {

	PaymentProvider getProvider();

	String getClientId();

	String getClientSecret();

	String getChainId();

	String getReturnUrl();
}
