package com.commerce.outbox.stock.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.common.log.kafka.TraceIdKafkaConfig;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.infrastructure.OutboxRelayMessage;
import com.commerce.outbox.stock.application.StockRestoreOutboxConsumeService;
import com.commerce.outbox.stock.application.payload.StockRestoreRequestedPayload;
import com.commerce.support.TestcontainersSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("docker")
@SpringBootTest(
	classes = StockRestoreKafkaEventConsumerIntegrationTest.TestConfig.class,
	webEnvironment = WebEnvironment.NONE,
	properties = {
		"spring.kafka.listener.auto-startup=true"
	}
)
@TestPropertySource(properties = {
	"spring.kafka.listener.missing-topics-fatal=false",
	"spring.kafka.consumer.auto-offset-reset=earliest",
	"spring.kafka.consumer.properties.max.poll.interval.ms=300000",
	"spring.kafka.consumer.properties.session.timeout.ms=10000"
})
class StockRestoreKafkaEventConsumerIntegrationTest {

	private static final String TOPIC = "stock-restore-events-it-" + UUID.randomUUID();
	private static final String DLT_TOPIC = TOPIC + ".DLT";
	private static final String GROUP_ID = "stock-restore-consumer-it-" + UUID.randomUUID();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerKafka(registry);
		registry.add("outbox.stock-restore.relay.topic", () -> TOPIC);
		registry.add("outbox.stock-restore.consumer.group-id", () -> GROUP_ID);
		registry.add("outbox.stock-restore.consumer.retry.max-attempts", () -> "3");
		registry.add("outbox.stock-restore.consumer.retry.initial-interval-millis", () -> "50");
		registry.add("outbox.stock-restore.consumer.retry.multiplier", () -> "1.0");
		registry.add("outbox.stock-restore.consumer.retry.max-interval-millis", () -> "50");
		registry.add("outbox.stock-restore.consumer.dlt.enabled", () -> "true");
		registry.add("outbox.stock-restore.consumer.dlt.suffix", () -> ".DLT");

		TestcontainersSupport.createKafkaTopics(TOPIC, DLT_TOPIC);
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
		StockRestoreKafkaEventConsumer.class,
		StockRestoreKafkaConsumerConfig.class
	})
	static class TestConfig {
	}

	@Autowired
	private KafkaTemplate<Object, Object> kafkaTemplate;

	@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
	@Autowired
	private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private StockRestoreOutboxConsumeService stockRestoreOutboxConsumeService;

	@BeforeEach
	void setUp() {
		reset(stockRestoreOutboxConsumeService);
		waitForListenerAssignment();
	}

	@DisplayName("비재시도 예외가 발생하면 DLT로 전송한다")
	@Test
	void consume_whenNonRetryableExceptionOccurs_publishToDlt() throws Exception {
		// given
		String invalidRelayMessage = "{\"invalid-json\"";

		// when
		sendMessage(invalidRelayMessage);

		// then
		ConsumerRecord<String, String> dltRecord = pollDltRecord(invalidRelayMessage);
		assertThat(dltRecord.topic()).isEqualTo(DLT_TOPIC);
		then(stockRestoreOutboxConsumeService).should(never()).consume(any());
	}

	@DisplayName("정상 메시지를 소비하면 서비스가 1회 호출되고 DLT로 전송되지 않는다")
	@Test
	void consume_whenValidMessage_callServiceOnceAndDoNotPublishToDlt() throws Exception {
		// given
		String relayMessage = toRelayMessageJson("evt-success-" + UUID.randomUUID());

		// when
		sendMessage(relayMessage);

		// then
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(stockRestoreOutboxConsumeService).should(times(1)).consume(any())
		);
		assertThat(pollDltRecordIfExists(relayMessage, Duration.ofSeconds(1))).isNull();
	}

	@DisplayName("재시도 가능한 예외가 반복되면 재시도 소진 후 DLT로 전송한다")
	@Test
	void consume_whenRetryableExceptionPersists_retryThenPublishToDlt() throws Exception {
		// given
		String relayMessage = toRelayMessageJson("evt-retry-" + UUID.randomUUID());
		doThrow(new IllegalStateException("restore failed"))
			.when(stockRestoreOutboxConsumeService)
			.consume(any());

		// when
		sendMessage(relayMessage);

		// then
		ConsumerRecord<String, String> dltRecord = pollDltRecord(relayMessage);
		assertThat(dltRecord.topic()).isEqualTo(DLT_TOPIC);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(stockRestoreOutboxConsumeService).should(times(3)).consume(any())
		);
	}

	private void waitForListenerAssignment() {
		for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
			ContainerTestUtils.waitForAssignment(container, 1);
		}
	}

	private void sendMessage(String message) throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
		kafkaTemplate.send(TOPIC, message).get(5, TimeUnit.SECONDS);
	}

	private ConsumerRecord<String, String> pollDltRecord(String expectedValue) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersSupport.getKafkaBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-consumer-" + UUID.randomUUID());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(List.of(DLT_TOPIC));

			final ConsumerRecord<String, String>[] found = new ConsumerRecord[1];
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
				for (ConsumerRecord<String, String> record : records) {
					if (expectedValue.equals(record.value())) {
						found[0] = record;
						break;
					}
				}
				assertThat(found[0]).isNotNull();
			});
			return found[0];
		}
	}

	private ConsumerRecord<String, String> pollDltRecordIfExists(String expectedValue, Duration timeout) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersSupport.getKafkaBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-consumer-" + UUID.randomUUID());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

		long deadlineNanos = System.nanoTime() + timeout.toNanos();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(List.of(DLT_TOPIC));
			while (System.nanoTime() < deadlineNanos) {
				ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
				for (ConsumerRecord<String, String> record : records) {
					if (expectedValue.equals(record.value())) {
						return record;
					}
				}
			}
			return null;
		}
	}

	private String toRelayMessageJson(String eventId) throws JsonProcessingException {
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of(
				StockRestoreRequestedPayload.Item.builder()
					.productId(100L)
					.quantity(2)
					.build()
			))
			.build();
		String payloadJson = objectMapper.writeValueAsString(payload);
		OutboxRelayMessage relayMessage = OutboxRelayMessage.builder()
			.eventId(eventId)
			.eventType(OutboxEventType.STOCK_RESTORE_REQUESTED)
			.aggregateType(OutboxAggregateType.ORDER)
			.aggregateId(1L)
			.payload(payloadJson)
			.occurredAt(LocalDateTime.now())
			.build();
		return objectMapper.writeValueAsString(relayMessage);
	}
}
