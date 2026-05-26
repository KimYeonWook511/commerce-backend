package com.commerce.common.log.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.commerce.common.log.LogContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.common.kafka.exception.KafkaConsumeNonRetryableException;
import com.commerce.outbox.stock.infrastructure.StockRestoreKafkaConsumerConfig;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@SpringBootTest(
	classes = TraceIdKafkaPropagationIntegrationTest.TestConfig.class,
	webEnvironment = WebEnvironment.NONE,
	properties = "spring.kafka.listener.auto-startup=true"
)
@TestPropertySource(properties = {
	"spring.kafka.listener.missing-topics-fatal=false",
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.properties.max.poll.interval.ms=300000",
	"spring.kafka.consumer.properties.session.timeout.ms=10000",
	"outbox.stock-restore.consumer.dlt.enabled=false",
	"outbox.stock-restore.consumer.retry.max-attempts=1",
	"outbox.stock-restore.consumer.retry.initial-interval-millis=50",
	"outbox.stock-restore.consumer.retry.multiplier=1.0",
	"outbox.stock-restore.consumer.retry.max-interval-millis=50"
})
class TraceIdKafkaPropagationIntegrationTest {

	static final String TOPIC = "trace-prop-it-" + UUID.randomUUID();
	static final String GROUP_ID = "trace-prop-consumer-it-" + UUID.randomUUID();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerKafka(registry);
		registry.add("trace-prop.topic", () -> TOPIC);
		registry.add("trace-prop.group-id", () -> GROUP_ID);
		TestcontainersSupport.createKafkaTopics(TOPIC);
	}

	@EnableKafka
	@EnableAutoConfiguration(exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		JpaRepositoriesAutoConfiguration.class,
		RedisAutoConfiguration.class,
		RedisRepositoriesAutoConfiguration.class,
		BatchAutoConfiguration.class
	})
	@Import({
		TraceIdKafkaConfig.class,
		StockRestoreKafkaConsumerConfig.class,
		TraceIdKafkaPropagationIntegrationTest.TestKafkaConsumer.class
	})
	static class TestConfig {
	}

	@Component
	static class TestKafkaConsumer {

		final AtomicReference<String> capturedMdc = new AtomicReference<>();
		final AtomicReference<String> capturedHeader = new AtomicReference<>();
		volatile CountDownLatch latch = new CountDownLatch(1);
		volatile boolean shouldThrow = false;

		void reset() {
			capturedMdc.set(null);
			capturedHeader.set(null);
			latch = new CountDownLatch(1);
			shouldThrow = false;
		}

		@KafkaListener(
			topics = "${trace-prop.topic}",
			groupId = "${trace-prop.group-id}",
			containerFactory = "stockRestoreKafkaListenerContainerFactory"
		)
		void consume(ConsumerRecord<String, String> record) {
			capturedMdc.set(LogContext.getTraceId());
			Header header = record.headers().lastHeader(LogContext.TRACE_ID_HEADER);
			if (header != null) {
				capturedHeader.set(new String(header.value(), StandardCharsets.UTF_8));
			}
			latch.countDown();
			if (shouldThrow) {
				throw new KafkaConsumeNonRetryableException("test-induced failure for MDC cleanup verification");
			}
		}
	}

	@Autowired
	private TestKafkaConsumer testConsumer;

	@Autowired
	private KafkaTemplate<Object, Object> kafkaTemplate;

	@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
	@Autowired
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	@MockitoSpyBean
	private TraceIdRecordInterceptor traceIdRecordInterceptor;

	@BeforeEach
	void setUp() {
		testConsumer.reset();
		LogContext.removeTraceId();
		reset(traceIdRecordInterceptor);
		waitForListenerAssignment();
	}

	@AfterEach
	void tearDown() {
		LogContext.removeTraceId();
	}

	@DisplayName("MDC에 traceId가 있으면 consumer MDC에 동일한 traceId가 설정된다")
	@Test
	void intercept_whenMdcHasTraceId_consumerSeesTheSameTraceId() throws Exception {
		// given
		LogContext.putTraceId("fixed-abc-123");

		// when
		kafkaTemplate.send(TOPIC, "msg-a").get(5, TimeUnit.SECONDS);

		// then
		assertThat(testConsumer.latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(testConsumer.capturedMdc.get()).isEqualTo("fixed-abc-123");
	}

	@DisplayName("MDC에 traceId가 없으면 신규 UUID가 발급되어 헤더와 consumer MDC 값이 일치한다")
	@Test
	void intercept_whenMdcEmpty_consumerSeesNewUuidMatchingHeader() throws Exception {
		// when
		kafkaTemplate.send(TOPIC, "msg-b").get(5, TimeUnit.SECONDS);

		// then
		assertThat(testConsumer.latch.await(5, TimeUnit.SECONDS)).isTrue();
		String consumedTraceId = testConsumer.capturedMdc.get();
		assertThat(consumedTraceId).isNotNull();
		assertThat(testConsumer.capturedHeader.get()).isEqualTo(consumedTraceId);
	}

	@DisplayName("consume 완료 후 afterRecord() 콜백에서 MDC traceId가 제거된다 (success 경로)")
	@Test
	void success_afterConsume_traceIdRemovedFromMdc() throws Exception {
		// afterRecord() 실행 시점에 consumer 스레드에서 MDC 값을 직접 캡처한다
		CountDownLatch cleanupLatch = new CountDownLatch(1);
		AtomicReference<String> mdcAfterCleanup = new AtomicReference<>("NOT_CAPTURED");
		doAnswer(invocation -> {
			invocation.callRealMethod();
			mdcAfterCleanup.set(LogContext.getTraceId());
			cleanupLatch.countDown();
			return null;
		}).when(traceIdRecordInterceptor).afterRecord(any(), any());

		// when
		kafkaTemplate.send(TOPIC, "msg-c").get(5, TimeUnit.SECONDS);
		assertThat(testConsumer.latch.await(5, TimeUnit.SECONDS)).isTrue();

		// then - consumer 스레드에서 afterRecord() 이후 MDC가 비어있어야 한다
		assertThat(cleanupLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(mdcAfterCleanup.get()).isNull();
	}

	@DisplayName("KafkaConsumeNonRetryableException 발생 시 afterRecord() 콜백에서 MDC traceId가 제거된다 (failure 경로)")
	@Test
	void failure_whenNonRetryableException_traceIdRemovedFromMdc() throws Exception {
		// afterRecord() 실행 시점에 consumer 스레드에서 MDC 값을 직접 캡처한다
		CountDownLatch cleanupLatch = new CountDownLatch(1);
		AtomicReference<String> mdcAfterCleanup = new AtomicReference<>("NOT_CAPTURED");
		doAnswer(invocation -> {
			invocation.callRealMethod();
			mdcAfterCleanup.set(LogContext.getTraceId());
			cleanupLatch.countDown();
			return null;
		}).when(traceIdRecordInterceptor).afterRecord(any(), any());

		// given
		testConsumer.shouldThrow = true;

		// when
		kafkaTemplate.send(TOPIC, "msg-d").get(5, TimeUnit.SECONDS);
		assertThat(testConsumer.latch.await(5, TimeUnit.SECONDS)).isTrue();

		// then - consume 시점엔 MDC가 있어야 하고, afterRecord() 이후 비어있어야 한다
		assertThat(testConsumer.capturedMdc.get()).isNotNull();
		assertThat(cleanupLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(mdcAfterCleanup.get()).isNull();
	}

	private void waitForListenerAssignment() {
		for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
			ContainerTestUtils.waitForAssignment(container, 1);
		}
	}
}
