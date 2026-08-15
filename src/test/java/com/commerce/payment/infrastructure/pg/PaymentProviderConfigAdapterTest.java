package com.commerce.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.application.port.dto.PaymentProviderConfig;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.infrastructure.pg.naverpay.Properties;

@DisplayName("결제사 설정 어댑터")
class PaymentProviderConfigAdapterTest {

	@DisplayName("결제사를 지정해 결제창에 필요한 설정을 읽는다")
	@Test
	void read_whenProviderGiven_returnsItsConfig() {
		Properties properties = new Properties();
		ReflectionTestUtils.setField(properties, "clientId", "client-id");
		ReflectionTestUtils.setField(properties, "clientSecret", "client-secret");
		ReflectionTestUtils.setField(properties, "chainId", "chain-id");
		ReflectionTestUtils.setField(properties, "returnUrl", "https://test.pg/return");

		PaymentProviderConfig config = new PaymentProviderConfigAdapter(properties).read(PaymentPg.NAVERPAY);

		assertThat(config.clientId()).isEqualTo("client-id");
		assertThat(config.chainId()).isEqualTo("chain-id");
		assertThat(config.returnUrl()).isEqualTo("https://test.pg/return");
	}
}
