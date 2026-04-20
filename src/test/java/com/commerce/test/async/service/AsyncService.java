package com.commerce.test.async.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.test.async.AsyncTest.ExternalScenario;
import com.commerce.test.async.domain.AsyncTestEntity;
import com.commerce.test.async.repository.AsyncTestEntityRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AsyncService {

	private final AsyncTestEntityRepository asyncTestEntityRepository;
	private final AsyncAnnotationService asyncAnnotationService;
	private final WebClientService webClientService;

	@Transactional
	public Long createAndRequestWithAsyncAnnotation(String name, ExternalScenario scenario) {
		AsyncTestEntity entity = asyncTestEntityRepository.save(
			AsyncTestEntity.requested(name, Thread.currentThread().getName(), nextTxMarker())
		);
		log.info("entity saved as REQUESTED. entityId={}, scenario={}, thread={}, tx={}",
			entity.getId(), scenario, entity.getFirstThreadName(), entity.getFirstTxName());
		asyncAnnotationService.requestAsync(entity.getId(), scenario);
		return entity.getId();
	}

	@Transactional
	public Long createAndRequestWithWebClient(String name, ExternalScenario scenario) {
		AsyncTestEntity entity = asyncTestEntityRepository.save(
			AsyncTestEntity.requested(name, Thread.currentThread().getName(), nextTxMarker())
		);
		log.info("entity saved as REQUESTED by webclient. entityId={}, scenario={}, thread={}, tx={}",
			entity.getId(), scenario, entity.getFirstThreadName(), entity.getFirstTxName());
		webClientService.requestAsync(entity.getId(), scenario);
		return entity.getId();
	}

	private String nextTxMarker() {
		return UUID.randomUUID().toString();
	}
}
