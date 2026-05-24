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
		return producerFactory -> {
			Object existing = producerFactory.getConfigurationProperties()
				.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
			String interceptorName = TraceIdKafkaProducerInterceptor.class.getName();
			String newValue = (existing == null) ? interceptorName : existing + "," + interceptorName;
			producerFactory.updateConfigs(Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, newValue));
		};
	}
}
