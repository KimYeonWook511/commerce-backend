package com.commerce.test.async.service;

import java.net.ConnectException;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.commerce.test.async.AsyncTest.ExternalScenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebClientService {

	private final WebClient webClient;
	private final AsyncResultService asyncResultService;

	public void requestAsync(Long entityId, ExternalScenario scenario) {
		log.info("webclient request start. entityId={}, scenario={}, thread={}",
			entityId, scenario, Thread.currentThread().getName());

		webClient.get()
			.uri(uriBuilder -> uriBuilder.path("/external").queryParam("scenario", scenario.name()).build())
			.retrieve()
			.onStatus(HttpStatusCode::isError, response ->
				response.createException()
					.doOnNext(exception -> log.info(
						"webclient request failed by external error. entityId={}, scenario={}, statusCode={}",
						entityId,
						scenario,
						exception.getStatusCode()
					))
					.flatMap(Mono::error)
			)
			.toBodilessEntity()
			.doOnSuccess(response -> {
				log.info("webclient request succeeded. entityId={}, scenario={}, thread={}",
					entityId, scenario, Thread.currentThread().getName());
				asyncResultService.markSucceeded(entityId);
			})
			.doOnError(exception -> {
				if (hasCause(exception, ConnectException.class)) {
					log.info("webclient request connection timeout. entityId={}, scenario={}", entityId, scenario);
					asyncResultService.markFailed(entityId);
					return;
				}
				log.info("webclient request read timeout. entityId={}, scenario={}", entityId, scenario);
				asyncResultService.markFailed(entityId);
			})
			.onErrorResume(exception -> Mono.empty())
			.subscribe();
	}

	private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
