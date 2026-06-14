package com.commerce.order.presentation.scheduler;

import java.time.LocalDateTime;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class OrderExpirationJobScheduler {

	private final JobLauncher jobLauncher;
	private final Job orderExpirationJob;

	@Value("${order.expiration.minutes:60}")
	private long expirationMinutes;

	@Scheduled(cron = "${order.expiration.batch.cron:0 */5 * * * *}")
	public void run() {
		LocalDateTime cutoff = LocalDateTime.now().minusMinutes(expirationMinutes);
		JobParameters jobParameters = new JobParametersBuilder()
			.addString("cutoff", cutoff.toString())
			.toJobParameters();
		try {
			jobLauncher.run(orderExpirationJob, jobParameters);
		} catch (Exception ex) {
			log.error("Order expiration job launch failed. cutoff={}", cutoff, ex);
		}
	}
}
