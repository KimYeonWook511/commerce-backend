package com.commerce.support;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class TestcontainersSupport {

	// JMM 가시성 문제(오래된 null) 해결을 위한 volatile 사용 (double-checked locking)
	private static volatile MySQLContainer<?> mysql;
	private static volatile GenericContainer<?> redis;
	private static volatile ConfluentKafkaContainer kafka;

	private static final Object MYSQL_LOCK = new Object();
	private static final Object REDIS_LOCK = new Object();
	private static final Object KAFKA_LOCK = new Object();

	private TestcontainersSupport() {
	}

	// Lazy Loading (필요할 때만 컨테이너를 시작함)
	public static void registerMySql(DynamicPropertyRegistry registry) {
		// System.out.println("Registering MySQL container");
		if (mysql == null) {
			synchronized (MYSQL_LOCK) {
				if (mysql == null) {
					MySQLContainer<?> created = new MySQLContainer<>("mysql:8.0")
						.withDatabaseName("commerce_db")
						.withUsername("test")
						.withPassword("test");
					created.start();
					// start()를 한 이후에 참조를 해야함!! -> 초기화 중인 객체를 아래에서 사용하다 문제가 생길 수 있음
					mysql = created;
				}
			}
		}

		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	public static void registerRedis(DynamicPropertyRegistry registry) {
		// System.out.println("Registering Redis container");
		if (redis == null) {
			synchronized (REDIS_LOCK) {
				if (redis == null) {
					// 배포랑 버전 맞춰야 함
					GenericContainer<?> created = new GenericContainer<>("redis:7.4")
						.withExposedPorts(6379);
					created.start();
					redis = created;
				}
			}
		}

		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

	public static void registerKafka(DynamicPropertyRegistry registry) {
		System.out.println("Registering Kafka container");
		ensureKafkaStarted();

		registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
		registry.add("spring.kafka.producer.key-serializer",
			() -> "org.apache.kafka.common.serialization.StringSerializer");
		registry.add("spring.kafka.producer.value-serializer",
			() -> "org.apache.kafka.common.serialization.StringSerializer");
		registry.add("spring.kafka.consumer.key-deserializer",
			() -> "org.apache.kafka.common.serialization.StringDeserializer");
		registry.add("spring.kafka.consumer.value-deserializer",
			() -> "org.apache.kafka.common.serialization.StringDeserializer");
	}

	public static String getKafkaBootstrapServers() {
		ensureKafkaStarted();
		return kafka.getBootstrapServers();
	}

	public static void createKafkaTopics(String... topicNames) {
		ensureKafkaStarted();

		List<NewTopic> topics = Arrays.stream(topicNames)
			.map(topicName -> new NewTopic(topicName, 1, (short)1))
			.toList();

		try (AdminClient adminClient = AdminClient.create(Map.of(
			"bootstrap.servers", kafka.getBootstrapServers()
		))) {
			adminClient.createTopics(topics).all().get(10, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to create Kafka test topics", ex);
		}
	}

	private static void ensureKafkaStarted() {
		if (kafka == null) {
			synchronized (KAFKA_LOCK) {
				if (kafka == null) {
					ConfluentKafkaContainer created =
						new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
					created.start();
					kafka = created;
				}
			}
		}
	}
}
