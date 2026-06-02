package com.commerce.test.async;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.commerce.test.async.config.AsyncTestConfig;
import com.commerce.test.async.domain.AsyncTestEntity;
import com.commerce.test.async.domain.AsyncTestStatus;
import com.commerce.test.async.repository.AsyncTestEntityRepository;
import com.commerce.test.async.service.AsyncService;

@Tag("learning")
@Disabled("학습용 — Spring @Async 동작과 WebClient timeout/실패 시나리오 학습. 운영 검증 대상 아님")
@SpringBootTest(classes = AsyncTest.AsyncTestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"logging.level.root=WARN",
	"logging.level.org.hibernate.SQL=OFF",
	"logging.level.p6spy=OFF",
	"logging.level.com.commerce.test.async=INFO",
	"logging.level.org.springframework.transaction=OFF",
	"logging.level.org.springframework.orm.jpa.JpaTransactionManager=OFF"
})
public class AsyncTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(AsyncTestConfig.class)
	static class AsyncTestApplication {
	}

	public enum ExternalScenario {
		SUCCESS,
		FAIL,
		CONNECTION_TIMEOUT,
		READ_TIMEOUT
	}

	@Autowired
	private AsyncService asyncService;

	@Autowired
	private AsyncTestEntityRepository asyncTestEntityRepository;

	@AfterEach
	void tearDown() {
		asyncTestEntityRepository.deleteAllInBatch();
	}

	@DisplayName("엔티티를 먼저 저장하고 서비스는 종료되며 이후 비동기 응답은 다른 스레드와 다른 트랜잭션 마커로 SUCCEEDED를 반영한다")
	@Test
	void createEntityAndRequest_whenSuccess_saveFirstAndThenHandleResponseInAnotherThreadAndTransaction()
		throws Exception {
		String requestCallerThread = Thread.currentThread().getName();

		Long entityId = asyncService.createAndRequestWithAsyncAnnotation("async-success", ExternalScenario.SUCCESS);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after request save", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		assertThat(requestedEntity.getFirstThreadName()).isEqualTo(requestCallerThread);
		assertThat(requestedEntity.getFirstTxName()).isNotBlank();
		assertThat(requestedEntity.getAsyncThreadName()).isNull();
		assertThat(requestedEntity.getAsyncTxName()).isNull();

		awaitUntilStatus(entityId, AsyncTestStatus.SUCCEEDED, Duration.ofSeconds(3));

		AsyncTestEntity completedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after async success", completedEntity);
		assertThat(completedEntity.getStatus()).isEqualTo(AsyncTestStatus.SUCCEEDED);
		assertThat(completedEntity.getAsyncThreadName()).startsWith("async-test-");
		assertThat(completedEntity.getAsyncThreadName()).isNotEqualTo(completedEntity.getFirstThreadName());
		assertThat(completedEntity.getAsyncTxName()).isNotBlank();
		assertThat(completedEntity.getAsyncTxName()).isNotEqualTo(completedEntity.getFirstTxName());
	}

	@DisplayName("비동기 외부 호출이 실패하면 다른 스레드와 다른 트랜잭션 마커로 FAILED를 반영한다")
	@Test
	void createEntityAndRequest_whenFail_markFailedInAnotherThreadAndTransaction() throws Exception {
		Long entityId = asyncService.createAndRequestWithAsyncAnnotation("async-fail", ExternalScenario.FAIL);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after request save - fail", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		awaitUntilStatus(entityId, AsyncTestStatus.FAILED, Duration.ofSeconds(3));

		AsyncTestEntity failEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after async fail", failEntity);
		assertThat(failEntity.getAsyncThreadName()).startsWith("async-test-");
		assertThat(failEntity.getAsyncThreadName()).isNotEqualTo(failEntity.getFirstThreadName());
		assertThat(failEntity.getAsyncTxName()).isNotEqualTo(failEntity.getFirstTxName());
	}

	@DisplayName("비동기 외부 호출이 read timeout이면 다른 스레드와 다른 트랜잭션 마커로 FAILED를 반영한다")
	@Test
	void createEntityAndRequest_whenReadTimeout_markFailedInAnotherThreadAndTransaction() throws Exception {
		Long entityId = asyncService.createAndRequestWithAsyncAnnotation("async-read-timeout", ExternalScenario.READ_TIMEOUT);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after request save - read timeout", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		awaitUntilStatus(entityId, AsyncTestStatus.FAILED, Duration.ofSeconds(3));

		AsyncTestEntity timeoutEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after async read timeout", timeoutEntity);
		assertThat(timeoutEntity.getAsyncThreadName()).startsWith("async-test-");
		assertThat(timeoutEntity.getAsyncTxName()).isNotEqualTo(timeoutEntity.getFirstTxName());
	}

	@DisplayName("WebClient 외부 호출이 성공하면 서비스는 먼저 종료되고 이후 다른 스레드와 다른 트랜잭션 마커로 SUCCEEDED를 반영한다")
	@Test
	void createEntityAndRequestByWebClient_whenSuccess_markSucceededInAnotherThreadAndTransaction() throws Exception {
		String requestCallerThread = Thread.currentThread().getName();

		Long entityId = asyncService.createAndRequestWithWebClient("webclient-success", ExternalScenario.SUCCESS);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient request save", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		assertThat(requestedEntity.getFirstThreadName()).isEqualTo(requestCallerThread);
		assertThat(requestedEntity.getFirstTxName()).isNotBlank();
		assertThat(requestedEntity.getAsyncThreadName()).isNull();
		assertThat(requestedEntity.getAsyncTxName()).isNull();

		awaitUntilStatus(entityId, AsyncTestStatus.SUCCEEDED, Duration.ofSeconds(3));

		AsyncTestEntity successEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient success", successEntity);
		assertThat(successEntity.getAsyncThreadName()).isNotEqualTo(successEntity.getFirstThreadName());
		assertThat(successEntity.getAsyncTxName()).isNotEqualTo(successEntity.getFirstTxName());
	}

	@DisplayName("WebClient 외부 호출이 실패하면 다른 스레드와 다른 트랜잭션 마커로 FAILED를 반영한다")
	@Test
	void createEntityAndRequestByWebClient_whenFail_markFailedInAnotherThreadAndTransaction() throws Exception {
		Long entityId = asyncService.createAndRequestWithWebClient("webclient-fail", ExternalScenario.FAIL);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient request save - fail", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		awaitUntilStatus(entityId, AsyncTestStatus.FAILED, Duration.ofSeconds(3));

		AsyncTestEntity failEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient fail", failEntity);
		assertThat(failEntity.getAsyncThreadName()).isNotEqualTo(failEntity.getFirstThreadName());
		assertThat(failEntity.getAsyncTxName()).isNotEqualTo(failEntity.getFirstTxName());
	}

	@DisplayName("WebClient 외부 호출이 read timeout이면 다른 스레드와 다른 트랜잭션 마커로 FAILED를 반영한다")
	@Test
	void createEntityAndRequestByWebClient_whenReadTimeout_markFailedInAnotherThreadAndTransaction() throws Exception {
		Long entityId = asyncService.createAndRequestWithWebClient("webclient-read-timeout", ExternalScenario.READ_TIMEOUT);

		AsyncTestEntity requestedEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient request save - read timeout", requestedEntity);
		assertThat(requestedEntity.getStatus()).isEqualTo(AsyncTestStatus.REQUESTED);
		awaitUntilStatus(entityId, AsyncTestStatus.FAILED, Duration.ofSeconds(3));

		AsyncTestEntity timeoutEntity = asyncTestEntityRepository.findById(entityId).orElseThrow();
		printEntity("after webclient read timeout", timeoutEntity);
		assertThat(timeoutEntity.getAsyncThreadName()).isNotEqualTo(timeoutEntity.getFirstThreadName());
		assertThat(timeoutEntity.getAsyncTxName()).isNotEqualTo(timeoutEntity.getFirstTxName());
	}

	private void awaitUntilStatus(Long entityId, AsyncTestStatus expectedStatus, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			AsyncTestEntity entity = asyncTestEntityRepository.findById(entityId).orElseThrow();
			if (entity.getStatus() == expectedStatus) {
				printEntity("status changed to " + expectedStatus, entity);
				return;
			}
			Thread.sleep(20L);
		}
		throw new AssertionError("상태가 변경되지 않았습니다. expected=" + expectedStatus);
	}

	private void printEntity(String phase, AsyncTestEntity entity) {
		System.out.println(
			"[AsyncTestEntity] phase=" + phase
				+ ", id=" + entity.getId()
				+ ", name=" + entity.getName()
				+ ", status=" + entity.getStatus()
				+ ", firstThreadName=" + entity.getFirstThreadName()
				+ ", firstTxName=" + entity.getFirstTxName()
				+ ", asyncThreadName=" + entity.getAsyncThreadName()
				+ ", asyncTxName=" + entity.getAsyncTxName()
		);
	}
}
