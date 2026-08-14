package com.commerce.payment.legacy.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

@Component
public class PaymentProviderPropertiesResolver {

	private final Map<PaymentProvider, PaymentProviderProperties> propertiesMap;

	public PaymentProviderPropertiesResolver(List<PaymentProviderProperties> propertiesList) {
		this.propertiesMap = propertiesList.stream()
			.collect(Collectors.toMap(PaymentProviderProperties::getProvider, Function.identity()));
	}

	public PaymentProviderProperties resolve(PaymentProvider provider) {
		PaymentProviderProperties properties = propertiesMap.get(provider);
		if (properties == null) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_NOT_SUPPORTED);
		}
		return properties;
	}
}
