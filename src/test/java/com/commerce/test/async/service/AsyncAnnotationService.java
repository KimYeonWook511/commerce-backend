package com.commerce.test.async.service;

import java.net.ConnectException;

import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.commerce.test.async.AsyncTest.ExternalScenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AsyncAnnotationService {

	private final RestTemplate restTemplate;
	private final AsyncResultService asyncResultService;

	@Async("asyncTestExecutor")
	public void requestAsync(Long entityId, ExternalScenario scenario) {
		log.info("async request start. entityId={}, scenario={}, thread={}", entityId, scenario, Thread.currentThread().getName());
		try {
			restTemplate.execute(
				"/external?scenario=" + scenario.name(),
				HttpMethod.GET,
				null,
				response -> {
					response.close();
					return null;
				}
			);
			log.info("async request succeeded. entityId={}, scenario={}", entityId, scenario);
			asyncResultService.markSucceeded(entityId);
		} catch (HttpServerErrorException exception) {
			log.info("async request failed by external error. entityId={}, scenario={}, statusCode={}",
				entityId, scenario, exception.getStatusCode());
			asyncResultService.markFailed(entityId);
		} catch (ResourceAccessException exception) {
			if (exception.getCause() instanceof ConnectException) {
				log.info("async request connection timeout. entityId={}, scenario={}", entityId, scenario);
				asyncResultService.markFailed(entityId);
				return;
			}
			log.info("async request read timeout. entityId={}, scenario={}", entityId, scenario);
			asyncResultService.markFailed(entityId);
		}
	}
}
