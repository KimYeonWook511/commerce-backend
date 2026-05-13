package com.commerce.payment.naverpay.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class NaverPayClientConfigTest {

	@DisplayName("네이버페이 RestTemplate 빈 생성 시 connect/read timeout이 설정된다")
	@Test
	void naverPayRestTemplate_whenCreate_setTimeouts() {
		// given
		NaverPayClientConfig config = new NaverPayClientConfig();
		ReflectionTestUtils.setField(config, "connectTimeoutMillis", 3000);
		ReflectionTestUtils.setField(config, "readTimeoutMillis", 60000);

		// when
		RestTemplate restTemplate = config.naverPayRestTemplate();

		// then
		assertThat(restTemplate.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
		SimpleClientHttpRequestFactory requestFactory =
			(SimpleClientHttpRequestFactory)restTemplate.getRequestFactory();
		assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(3000);
		assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(60000);
	}
}
