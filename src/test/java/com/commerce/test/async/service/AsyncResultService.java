package com.commerce.test.async.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.test.async.domain.AsyncTestEntity;
import com.commerce.test.async.repository.AsyncTestEntityRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AsyncResultService {

	private final AsyncTestEntityRepository asyncTestEntityRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markSucceeded(Long entityId) {
		AsyncTestEntity entity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		entity.markSucceeded(Thread.currentThread().getName(), nextTxMarker());
		log.info("entity marked as SUCCEEDED. entityId={}, thread={}, tx={}",
			entity.getId(), entity.getAsyncThreadName(), entity.getAsyncTxName());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long entityId) {
		AsyncTestEntity entity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		entity.markFailed(Thread.currentThread().getName(), nextTxMarker());
		log.info("entity marked as FAILED. entityId={}, thread={}, tx={}",
			entity.getId(), entity.getAsyncThreadName(), entity.getAsyncTxName());
	}

	private String nextTxMarker() {
		return UUID.randomUUID().toString();
	}
}
