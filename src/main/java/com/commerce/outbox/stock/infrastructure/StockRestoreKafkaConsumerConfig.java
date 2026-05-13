package com.commerce.outbox.stock.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.commerce.common.kafka.exception.KafkaConsumeNonRetryableException;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class StockRestoreKafkaConsumerConfig {

	@Value("${outbox.stock-restore.consumer.retry.initial-interval-millis:1000}")
	private long initialIntervalMillis;

	@Value("${outbox.stock-restore.consumer.retry.multiplier:2.0}")
	private double multiplier;

	@Value("${outbox.stock-restore.consumer.retry.max-interval-millis:10000}")
	private long maxIntervalMillis;

	@Value("${outbox.stock-restore.consumer.retry.max-attempts:3}")
	private int maxAttempts;

	@Value("${outbox.stock-restore.consumer.dlt.enabled:true}")
	private boolean dltEnabled;

	@Value("${outbox.stock-restore.consumer.dlt.suffix:.DLT}")
	private String dltSuffix;

	@Bean
	public DeadLetterPublishingRecoverer stockRestoreDeadLetterPublishingRecoverer(
		KafkaOperations<Object, Object> kafkaOperations
	) {
		return new DeadLetterPublishingRecoverer(
			kafkaOperations,
			(ConsumerRecord<?, ?> record, Exception ex) -> {
				TopicPartition topicPartition = new TopicPartition(record.topic() + dltSuffix, record.partition());
				log.warn(
					"Route failed kafka record to DLT. sourceTopic={}, dltTopic={}, partition={}, offset={}, exception={}",
					record.topic(),
					topicPartition.topic(),
					record.partition(),
					record.offset(),
					ex.getClass().getSimpleName()
				);
				return topicPartition;
			}
		);
	}

	@Bean
	public DefaultErrorHandler stockRestoreKafkaErrorHandler(
		DeadLetterPublishingRecoverer stockRestoreDeadLetterPublishingRecoverer
	) {
		DefaultErrorHandler errorHandler = dltEnabled
			? new DefaultErrorHandler(stockRestoreDeadLetterPublishingRecoverer, stockRestoreConsumerBackOff())
			: new DefaultErrorHandler(stockRestoreConsumerBackOff());
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
		factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
		return factory;
	}

	private ExponentialBackOffWithMaxRetries stockRestoreConsumerBackOff() {
		int maxRetries = Math.max(maxAttempts - 1, 0);
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
		backOff.setInitialInterval(initialIntervalMillis);
		backOff.setMultiplier(multiplier);
		backOff.setMaxInterval(maxIntervalMillis);
		return backOff;
	}
}
