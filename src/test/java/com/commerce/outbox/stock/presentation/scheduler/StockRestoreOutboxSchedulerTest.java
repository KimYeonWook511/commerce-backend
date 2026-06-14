package com.commerce.outbox.stock.presentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.outbox.stock.application.usecase.StockRestoreOutboxRelayUseCase;
import com.commerce.outbox.stock.application.result.OutboxPublishResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class StockRestoreOutboxSchedulerTest {

	@Mock
	private StockRestoreOutboxRelayUseCase stockRestoreOutboxRelayService;

	@InjectMocks
	private StockRestoreOutboxScheduler stockRestoreOutboxScheduler;

	private Logger logger;
	private ListAppender<ILoggingEvent> listAppender;

	@BeforeEach
	void setUp() {
		logger = (Logger)LoggerFactory.getLogger(StockRestoreOutboxScheduler.class);
		listAppender = new ListAppender<>();
		listAppender.start();
		logger.addAppender(listAppender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(listAppender);
		listAppender.stop();
	}

	@DisplayName("PENDING 발행 스케줄러가 실행되면 서비스에 현재 시간을 전달하고 결과가 있으면 로그를 남긴다")
	@Test
	void publishPendingEvents_whenResultExists_callServiceAndLog() {
		// given
		given(stockRestoreOutboxRelayService.publishPendingEvents(any()))
			.willReturn(OutboxPublishResult.builder()
				.selectedCount(3)
				.publishedCount(2)
				.failedCount(1)
				.skippedCount(0)
				.build());

		// when
		stockRestoreOutboxScheduler.publishPendingEvents();

		// then
		then(stockRestoreOutboxRelayService).should().publishPendingEvents(any());
		assertThat(listAppender.list)
			.anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.INFO);
				assertThat(event.getFormattedMessage())
					.contains("job=outbox-stock-restore-pending-publish")
					.contains("selected=3")
					.contains("published=2")
					.contains("failed=1")
					.contains("skipped=0");
			});
	}

	@DisplayName("PENDING 발행 스케줄러는 처리 결과가 모두 0이면 로그를 남기지 않는다")
	@Test
	void publishPendingEvents_whenAllCountsZero_notLog() {
		// given
		given(stockRestoreOutboxRelayService.publishPendingEvents(any()))
			.willReturn(OutboxPublishResult.builder()
				.selectedCount(0)
				.publishedCount(0)
				.failedCount(0)
				.skippedCount(0)
				.build());

		// when
		stockRestoreOutboxScheduler.publishPendingEvents();

		// then
		then(stockRestoreOutboxRelayService).should().publishPendingEvents(any());
		assertThat(listAppender.list).isEmpty();
	}

	@DisplayName("FAILED 재발행 스케줄러가 실행되면 서비스에 현재 시간을 전달하고 결과가 있으면 로그를 남긴다")
	@Test
	void publishRetryableFailedEvents_whenResultExists_callServiceAndLog() {
		// given
		given(stockRestoreOutboxRelayService.publishRetryableFailedEvents(any()))
			.willReturn(OutboxPublishResult.builder()
				.selectedCount(2)
				.publishedCount(1)
				.failedCount(0)
				.skippedCount(1)
				.build());

		// when
		stockRestoreOutboxScheduler.publishRetryableFailedEvents();

		// then
		then(stockRestoreOutboxRelayService).should().publishRetryableFailedEvents(any());
		assertThat(listAppender.list)
			.anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.INFO);
				assertThat(event.getFormattedMessage())
					.contains("job=outbox-stock-restore-retry-publish")
					.contains("selected=2")
					.contains("published=1")
					.contains("failed=0")
					.contains("skipped=1");
			});
	}

	@DisplayName("stale 복구 스케줄러는 복구 건수가 0보다 크면 경고 로그를 남긴다")
	@Test
	void recoverStalePublishingEvents_whenRecoveredCountPositive_logWarn() {
		// given
		given(stockRestoreOutboxRelayService.recoverStalePublishingEvents(any())).willReturn(5);

		// when
		stockRestoreOutboxScheduler.recoverStalePublishingEvents();

		// then
		then(stockRestoreOutboxRelayService).should().recoverStalePublishingEvents(any());
		assertThat(listAppender.list)
			.anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage())
					.contains("job=outbox-stock-restore-stale-recovery")
					.contains("recovered=5");
			});
	}

	@DisplayName("stale 복구 스케줄러는 복구 건수가 0이면 로그를 남기지 않는다")
	@Test
	void recoverStalePublishingEvents_whenRecoveredCountZero_notLog() {
		// given
		given(stockRestoreOutboxRelayService.recoverStalePublishingEvents(any())).willReturn(0);

		// when
		stockRestoreOutboxScheduler.recoverStalePublishingEvents();

		// then
		then(stockRestoreOutboxRelayService).should().recoverStalePublishingEvents(any());
		assertThat(listAppender.list).isEmpty();
	}
}
