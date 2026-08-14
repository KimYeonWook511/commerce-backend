package com.commerce.payment.legacy.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

class PaymentProviderPropertiesResolverTest {

	private final PaymentProviderProperties naverPay;
	private final List<PaymentProviderProperties> properties;
	private final PaymentProviderPropertiesResolver resolver;

	PaymentProviderPropertiesResolverTest() {
		naverPay = new TestNaverPayProperties();

		properties = List.of(naverPay);

		resolver = new PaymentProviderPropertiesResolver(properties);
	}

	@DisplayName("결제 수단에 맞는 프로퍼티를 조회한다")
	@Test
	void resolve_whenProviderExists_returnProperties() {
		// given

		// when
		PaymentProviderProperties result = resolver.resolve(PaymentProvider.NAVERPAY);

		// then
		assertThat(result).isEqualTo(naverPay);
	}

	@DisplayName("지원하지 않는 결제 수단이면 예외가 발생한다")
	@Test
	void resolve_whenProviderNotFound_throwException() {
		// given
		PaymentProviderPropertiesResolver emptyResolver = new PaymentProviderPropertiesResolver(List.of());

		// when & then
		assertThatThrownBy(() -> emptyResolver.resolve(PaymentProvider.NAVERPAY))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode())
					.isEqualTo(PaymentErrorCode.PAYMENT_PROVIDER_NOT_SUPPORTED);
			});
	}

	private static class TestNaverPayProperties implements PaymentProviderProperties {

		@Override
		public PaymentProvider getProvider() {
			return PaymentProvider.NAVERPAY;
		}

		@Override
		public String getClientId() {
			return "client-id";
		}

		@Override
		public String getClientSecret() {
			return "client-secret";
		}

		@Override
		public String getChainId() {
			return "chain-id";
		}

		@Override
		public String getReturnUrl() {
			return "https://return-url";
		}

		public String getApprovalUrl() {
			return "https://approval-url";
		}
	}
}
