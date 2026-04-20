package com.commerce.test.async.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.commerce.test.async.AsyncTest;
import com.commerce.test.async.domain.AsyncTestEntity;
import com.commerce.test.async.repository.AsyncTestEntityRepository;
import com.commerce.test.async.service.AsyncResultService;
import com.commerce.test.async.service.AsyncAnnotationService;
import com.commerce.test.async.service.AsyncService;
import com.commerce.test.async.service.WebClientService;

import reactor.core.publisher.Mono;

@TestConfiguration
@EnableAsync
@EntityScan(basePackageClasses = AsyncTestEntity.class)
@EnableJpaRepositories(basePackageClasses = AsyncTestEntityRepository.class)
@Import({
	AsyncService.class,
	AsyncAnnotationService.class,
	AsyncResultService.class,
	WebClientService.class
})
public class AsyncTestConfig {

	@Bean(name = "asyncTestExecutor")
	ThreadPoolTaskExecutor asyncTestExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(10);
		executor.setThreadNamePrefix("async-test-");
		executor.initialize();
		return executor;
	}

	@Bean
	RestTemplate restTemplate() {
		return new RestTemplate(new StubRestTemplateRequestFactory());
	}

	@Bean
	WebClient webClient() {
		return WebClient.builder()
			.exchangeFunction(new StubExchangeFunction())
			.build();
	}

	static class StubRestTemplateRequestFactory implements ClientHttpRequestFactory {
		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
			return new MockClientHttpRequest(httpMethod, uri) {
				@Override
				protected ClientHttpResponse executeInternal() {
					AsyncTest.ExternalScenario scenario = extractScenario(uri);
					if (scenario == AsyncTest.ExternalScenario.SUCCESS) {
						return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
					}
					if (scenario == AsyncTest.ExternalScenario.FAIL) {
						return new MockClientHttpResponse(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR);
					}
					if (scenario == AsyncTest.ExternalScenario.CONNECTION_TIMEOUT) {
						throw new ResourceAccessException("connection timeout", new ConnectException("stub connect timeout"));
					}
					if (scenario == AsyncTest.ExternalScenario.READ_TIMEOUT) {
						throw new ResourceAccessException("read timeout", new SocketTimeoutException("stub read timeout"));
					}
					throw new IllegalArgumentException("지원하지 않는 시나리오입니다. scenario=" + scenario);
				}
			};
		}

		private AsyncTest.ExternalScenario extractScenario(URI uri) {
			String[] tokens = uri.getQuery().split("=");
			return AsyncTest.ExternalScenario.valueOf(tokens[1]);
		}
	}

	static class StubExchangeFunction implements ExchangeFunction {
		@Override
		public Mono<ClientResponse> exchange(ClientRequest request) {
			AsyncTest.ExternalScenario scenario = extractScenario(request.url());

			if (scenario == AsyncTest.ExternalScenario.SUCCESS) {
				return Mono.delay(Duration.ofMillis(50))
					.map(ignored -> ClientResponse.create(HttpStatus.OK).build());
			}
			if (scenario == AsyncTest.ExternalScenario.FAIL) {
				return Mono.delay(Duration.ofMillis(50))
					.map(ignored -> ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
			}
			if (scenario == AsyncTest.ExternalScenario.CONNECTION_TIMEOUT) {
				return Mono.delay(Duration.ofMillis(50))
					.then(Mono.error(new RuntimeException(new ConnectException("stub connect timeout"))));
			}
			if (scenario == AsyncTest.ExternalScenario.READ_TIMEOUT) {
				return Mono.delay(Duration.ofMillis(50))
					.then(Mono.error(new RuntimeException(new SocketTimeoutException("stub read timeout"))));
			}
			return Mono.error(new IllegalArgumentException("지원하지 않는 시나리오입니다. scenario=" + scenario));
		}

		private AsyncTest.ExternalScenario extractScenario(URI uri) {
			String[] tokens = uri.getQuery().split("=");
			return AsyncTest.ExternalScenario.valueOf(tokens[1]);
		}
	}
}
