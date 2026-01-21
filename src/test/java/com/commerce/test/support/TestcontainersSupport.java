package com.commerce.test.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

public final class TestcontainersSupport {

	// JMM 가시성 문제(오래된 null) 해결을 위한 volatile 사용 (double-checked locking)
	private static volatile MySQLContainer<?> mysql;
	private static volatile GenericContainer<?> redis;

	private static final Object MYSQL_LOCK = new Object();
	private static final Object REDIS_LOCK = new Object();

	private TestcontainersSupport() {
	}

	// Lazy Loading (필요할 때만 컨테이너를 시작함)
	public static void registerMySql(DynamicPropertyRegistry registry) {
		System.out.println("Registering MySQL container");
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
		System.out.println(mysql.getJdbcUrl());
		registry.add("spring.datasource.username", mysql::getUsername);
		System.out.println(mysql.getUsername());
		registry.add("spring.datasource.password", mysql::getPassword);
		System.out.println(mysql.getPassword());
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
		System.out.println(mysql.getDriverClassName());
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	public static void registerRedis(DynamicPropertyRegistry registry) {
		System.out.println("Registering Redis container");
		if (redis == null) {
			synchronized (REDIS_LOCK) {
				if (redis == null) {
					GenericContainer<?> created = new GenericContainer<>("redis:7.2")
						.withExposedPorts(6379);
					created.start();
					redis = created;
				}
			}
		}

		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}
}
