package com.commerce.outbox.stock.mq.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StockRestoreKafkaConsumerConfigTest {

	@Mock
	private ConcurrentKafkaListenerContainerFactoryConfigurer configurer;

	@Mock
	private ConsumerFactory<Object, Object> consumerFactory;

	@Mock
	private KafkaOperations<Object, Object> kafkaOperations;

	@DisplayName("재고 복구 Kafka 리스너 팩토리는 에러 핸들러와 RECORD AckMode를 설정한다")
	@Test
	void stockRestoreKafkaListenerContainerFactory_whenCreateFactory_setErrorHandlerAndAckMode() {
		// given
		StockRestoreKafkaConsumerConfig config = new StockRestoreKafkaConsumerConfig();
		ReflectionTestUtils.setField(config, "initialIntervalMillis", 1000L);
		ReflectionTestUtils.setField(config, "multiplier", 2.0d);
		ReflectionTestUtils.setField(config, "maxIntervalMillis", 10000L);
		ReflectionTestUtils.setField(config, "maxAttempts", 3);
		ReflectionTestUtils.setField(config, "dltEnabled", true);
		ReflectionTestUtils.setField(config, "dltSuffix", ".DLT");
		DeadLetterPublishingRecoverer recoverer = config.stockRestoreDeadLetterPublishingRecoverer(kafkaOperations);
		DefaultErrorHandler errorHandler = config.stockRestoreKafkaErrorHandler(recoverer);

		// when
		ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
			config.stockRestoreKafkaListenerContainerFactory(configurer, consumerFactory, errorHandler);

		// then
		then(configurer).should().configure(factory, consumerFactory);
		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
	}

	@DisplayName("재고 복구 Kafka 에러 핸들러를 생성한다")
	@Test
	void stockRestoreKafkaErrorHandler_whenCreate_returnHandler() {
		// given
		StockRestoreKafkaConsumerConfig config = new StockRestoreKafkaConsumerConfig();
		ReflectionTestUtils.setField(config, "initialIntervalMillis", 1000L);
		ReflectionTestUtils.setField(config, "multiplier", 2.0d);
		ReflectionTestUtils.setField(config, "maxIntervalMillis", 10000L);
		ReflectionTestUtils.setField(config, "maxAttempts", 3);
		ReflectionTestUtils.setField(config, "dltEnabled", true);
		ReflectionTestUtils.setField(config, "dltSuffix", ".DLT");

		// when
		DeadLetterPublishingRecoverer recoverer = config.stockRestoreDeadLetterPublishingRecoverer(kafkaOperations);
		DefaultErrorHandler errorHandler = config.stockRestoreKafkaErrorHandler(recoverer);

		// then
		assertThat(errorHandler).isNotNull();
	}

	@DisplayName("재고 복구 Kafka DLT recoverer를 생성한다")
	@Test
	void stockRestoreDeadLetterPublishingRecoverer_whenCreate_returnRecoverer() {
		// given
		StockRestoreKafkaConsumerConfig config = new StockRestoreKafkaConsumerConfig();
		ReflectionTestUtils.setField(config, "dltSuffix", ".DLT");

		// when
		DeadLetterPublishingRecoverer recoverer = config.stockRestoreDeadLetterPublishingRecoverer(kafkaOperations);

		// then
		assertThat(recoverer).isNotNull();
	}
}
