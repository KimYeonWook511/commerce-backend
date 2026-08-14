package com.commerce.payment.infrastructure.pg.naverpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 이 결제사를 부르는 데 쓰는 HTTP 클라이언트. 빈 이름을 옛 연동과 겹치지 않게 둔다.
 */
@Configuration
public class ClientConfig {

	@Value("${payment.pg.naverpay.timeout.connect-millis:3000}")
	private int connectTimeoutMillis;

	@Value("${payment.pg.naverpay.timeout.read-millis:10000}")
	private int readTimeoutMillis;

	@Bean(name = "pgNaverPayRestTemplate")
	public RestTemplate pgNaverPayRestTemplate() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeoutMillis);

		RestTemplate restTemplate = new RestTemplate(requestFactory);
		// 상태 코드로 예외를 던지지 않고 응답을 그대로 돌려받는다. 실패 응답이 성공 상태로 오는
		// 결제사라 상태 코드만으로 결과를 가를 수 없고, 중복 요청 응답은 오류 상태와 함께 이전 응답
		// 본문을 실어 보내므로 그 본문을 읽어야 결과가 정해진다.
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		return restTemplate;
	}
}
