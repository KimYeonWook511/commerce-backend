package com.commerce.common.log.kafka;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TraceIdKafkaConfig {

	@Bean
	public TraceIdRecordInterceptor traceIdRecordInterceptor() {
		return new TraceIdRecordInterceptor();
	}

	@Bean
	public DefaultKafkaProducerFactoryCustomizer traceIdKafkaProducerFactoryCustomizer() {
		return producerFactory -> producerFactory.updateConfigs(
			Map.of(
				ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
				TraceIdKafkaProducerInterceptor.class.getName()
			)
		);
	}
}
