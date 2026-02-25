package com.commerce.outbox.stock.mq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.commerce.common.exception.KafkaConsumeNonRetryableException;

@Configuration
public class StockRestoreKafkaConsumerConfig {

	@Value("${outbox.stock-restore.consumer.retry.initial-interval-millis:1000}")
	private long initialIntervalMillis;

	@Value("${outbox.stock-restore.consumer.retry.multiplier:2.0}")
	private double multiplier;

	@Value("${outbox.stock-restore.consumer.retry.max-interval-millis:10000}")
	private long maxIntervalMillis;

	@Value("${outbox.stock-restore.consumer.retry.max-attempts:3}")
	private int maxAttempts;

	@Bean
	public DefaultErrorHandler stockRestoreKafkaErrorHandler() {
		// 비재시도 예외는 즉시 중단하고, 그 외 예외는 백오프 정책으로 재시도한다.
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(stockRestoreConsumerBackOff());
		errorHandler.addNotRetryableExceptions(KafkaConsumeNonRetryableException.class);
		return errorHandler;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<Object, Object> stockRestoreKafkaListenerContainerFactory(
		ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
		ConsumerFactory<Object, Object> consumerFactory,
		DefaultErrorHandler stockRestoreKafkaErrorHandler
	) {
		ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		configurer.configure(factory, consumerFactory);
		factory.setCommonErrorHandler(stockRestoreKafkaErrorHandler);
		// 개별 메시지 단위로 ack 처리하여 실패 영향 범위를 줄인다.
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
		return factory;
	}

	private ExponentialBackOffWithMaxRetries stockRestoreConsumerBackOff() {
		// maxAttempts는 최초 1회 시도를 포함하므로 backoff에는 재시도 횟수(maxAttempts - 1)를 전달함
		int maxRetries = Math.max(maxAttempts - 1, 0);
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
		backOff.setInitialInterval(initialIntervalMillis);
		backOff.setMultiplier(multiplier);
		backOff.setMaxInterval(maxIntervalMillis);
		return backOff;
	}
}
