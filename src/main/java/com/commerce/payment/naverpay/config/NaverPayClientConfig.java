package com.commerce.payment.naverpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class NaverPayClientConfig {

	@Value("${naverpay.timeout.connect-millis:3000}")
	private int connectTimeoutMillis;

	@Value("${naverpay.timeout.read-millis:60000}")
	private int readTimeoutMillis;

	@Bean(name = "naverPayRestTemplate")
	public RestTemplate naverPayRestTemplate() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);
		return new RestTemplate(requestFactory);
	}
}
