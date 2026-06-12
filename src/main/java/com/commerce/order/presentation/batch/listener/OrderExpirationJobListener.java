package com.commerce.order.presentation.batch.listener;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OrderExpirationJobListener implements JobExecutionListener {

	@Override
	public void beforeJob(JobExecution jobExecution) {
		log.info("==================== BATCH START ====================");
		log.info("Order expiration job started. executionId={}, parameters={}",
			jobExecution.getId(),
			jobExecution.getJobParameters());
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		if (jobExecution.getStatus().isUnsuccessful()) {
			log.error("==================== BATCH END ====================");
			log.error("Order expiration job failed. executionId={}, status={}, exitStatus={}",
				jobExecution.getId(),
				jobExecution.getStatus(),
				jobExecution.getExitStatus());
			jobExecution.getAllFailureExceptions()
				.forEach(ex -> log.error("Order expiration job exception. executionId={}", jobExecution.getId(), ex));
			return;
		}

		log.info("==================== BATCH END ====================");
		log.info("Order expiration job finished. executionId={}, status={}, exitStatus={}",
			jobExecution.getId(),
			jobExecution.getStatus(),
			jobExecution.getExitStatus());
	}
}
