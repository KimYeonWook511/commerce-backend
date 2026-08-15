package com.commerce.payment.infrastructure.pg.naverpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 이 결제사를 부르는 데 쓰는 HTTP 클라이언트. 빈 이름을 옛 연동과 겹치지 않게 둔다.
 *
 * <p>읽기 제한 시간이 진입점마다 달라 클라이언트를 둘로 둔다. 회원이 화면에서 기다리는 자리는 회원을
 * 오래 붙들지 않는 것이 기준이고, 배치와 대사는 회원이 없어 배치 스레드를 오래 잡지 않는 것이 기준이다.
 * 결제사 명세는 timeout을 60초로 두라고 권고하지만 그것은 "일찍 끊지 마라"는 뜻이고, 끊어도 결제사는
 * 계속 처리하며 우리는 그 결과를 대사로 회수한다.
 */
@Configuration
public class ClientConfig {

	@Value("${payment.pg.naverpay.timeout.connect-millis:3000}")
	private int connectTimeoutMillis;

	@Value("${payment.pg.naverpay.timeout.read-millis:10000}")
	private int readTimeoutMillis;

	@Value("${payment.pg.naverpay.timeout.batch-read-millis:5000}")
	private int batchReadTimeoutMillis;

	/** 회원 요청 흐름이 쓰는 클라이언트 */
	@Bean(name = "pgNaverPayRestTemplate")
	public RestTemplate pgNaverPayRestTemplate() {
		return createRestTemplate(readTimeoutMillis);
	}

	/** 발송 배치와 대사가 쓰는 클라이언트. 회원이 안 기다리므로 더 짧게 끊는다 */
	@Bean(name = "pgNaverPayBatchRestTemplate")
	public RestTemplate pgNaverPayBatchRestTemplate() {
		return createRestTemplate(batchReadTimeoutMillis);
	}

	private RestTemplate createRestTemplate(int readTimeout) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMillis);
		requestFactory.setReadTimeout(readTimeout);

		RestTemplate restTemplate = new RestTemplate(requestFactory);
		// 상태 코드로 예외를 던지지 않고 응답을 그대로 돌려받는다. 실패 응답이 성공 상태로 오는
		// 결제사라 상태 코드만으로 결과를 가를 수 없고, 중복 요청 응답은 오류 상태와 함께 이전 응답
		// 본문을 실어 보내므로 그 본문을 읽어야 결과가 정해진다.
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		return restTemplate;
	}
}
