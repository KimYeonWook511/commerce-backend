package com.commerce.outbox.stock.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.common.log.LogContext;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;
import com.commerce.outbox.domain.repository.OutboxEventRepository;
import com.commerce.outbox.stock.application.result.OutboxPublishResult;
import com.commerce.outbox.stock.application.port.StockRestoreEventPublisher;

@ExtendWith(MockitoExtension.class)
class StockRestoreOutboxRelayUseCaseTest {

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private StockRestoreEventPublisher eventPublisher;

	@InjectMocks
	private StockRestoreOutboxRelayUseCase stockRestoreOutboxRelayUseCase;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(stockRestoreOutboxRelayUseCase, "batchSize", 100);
		ReflectionTestUtils.setField(stockRestoreOutboxRelayUseCase, "retryBaseSeconds", 30L);
		ReflectionTestUtils.setField(stockRestoreOutboxRelayUseCase, "retryMaxSeconds", 3600L);
		ReflectionTestUtils.setField(stockRestoreOutboxRelayUseCase, "stalePublishingSeconds", 300L);
	}

	@AfterEach
	void cleanUpMdc() {
		LogContext.removeTraceId();
	}

	@DisplayName("PENDING 발행 시 '성공/실패/선점실패(skip)' 케이스를 집계한다")
	@Test
	void publishPendingEvents_whenMixedResults_aggregateCounts() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget claimMissTarget = mockTarget(1L, "evt-1");
		OutboxPublishTarget successTarget = mockTarget(2L, "evt-2");
		OutboxPublishTarget failedTarget = mockTarget(3L, "evt-3");
		given(failedTarget.getAttemptCount()).willReturn(0);

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(claimMissTarget, successTarget, failedTarget));

		given(outboxEventRepository.markPublishingFromPending(1L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(0);
		given(outboxEventRepository.markPublishingFromPending(2L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markPublishingFromPending(3L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markSent(2L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markFailed(
			eq(3L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(30))
		)).willReturn(1);

		doNothing()
			.doThrow(new IllegalStateException("publish failed"))
			.when(eventPublisher)
			.publish(any());

		// when
		OutboxPublishResult result = stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(result.getSelectedCount()).isEqualTo(3);
		assertThat(result.getPublishedCount()).isEqualTo(1);
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getSkippedCount()).isEqualTo(1);

		then(eventPublisher).should(times(2)).publish(any());
		then(outboxEventRepository).should().markFailed(
			eq(3L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(30))
		);
	}

	@DisplayName("PENDING 발행 대상이 없으면 모든 집계는 0이다")
	@Test
	void publishPendingEvents_whenNoTargets_returnZeroCounts() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of());

		// when
		OutboxPublishResult result = stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(result.getSelectedCount()).isZero();
		assertThat(result.getPublishedCount()).isZero();
		assertThat(result.getFailedCount()).isZero();
		assertThat(result.getSkippedCount()).isZero();
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@DisplayName("발행 실패 후 markFailed가 실패해도 예외를 던지지 않고 failed로 집계한다")
	@Test
	void publishPendingEvents_whenMarkFailedFails_countFailedWithoutThrowing() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(30);
		OutboxPublishTarget failedTarget = mockTarget(11L, "evt-11");
		given(failedTarget.getAttemptCount()).willReturn(0);
		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(failedTarget));
		given(outboxEventRepository.markPublishingFromPending(11L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		doThrow(new IllegalStateException("publish failed"))
			.when(eventPublisher)
			.publish(any());
		given(outboxEventRepository.markFailed(
			eq(11L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(30))
		)).willThrow(new IllegalStateException("mark failed"));

		// when
		OutboxPublishResult result = stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(result.getSelectedCount()).isEqualTo(1);
		assertThat(result.getPublishedCount()).isZero();
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getSkippedCount()).isZero();
	}

	@DisplayName("발행 실패 메시지가 길면 최대 길이로 잘라서 저장한다")
	@Test
	void publishPendingEvents_whenErrorMessageTooLong_truncateErrorMessage() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(40);
		OutboxPublishTarget failedTarget = mockTarget(12L, "evt-12");
		given(failedTarget.getAttemptCount()).willReturn(0);
		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(failedTarget));
		given(outboxEventRepository.markPublishingFromPending(12L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		doThrow(new IllegalStateException("x".repeat(1500)))
			.when(eventPublisher)
			.publish(any());

		ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
		given(outboxEventRepository.markFailed(
			eq(12L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			errorCaptor.capture(),
			eq(now.plusSeconds(30))
		)).willReturn(1);

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(errorCaptor.getValue()).hasSize(1000);
	}

	@DisplayName("재시도 가능한 FAILED 이벤트 발행 후 SENT 반영 실패 시 skipped로 집계한다")
	@Test
	void publishRetryableFailedEvents_whenMarkSentFails_countSkipped() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(10);
		OutboxPublishTarget target = mockTarget(10L, "evt-retry");

		given(outboxEventRepository.findRetryableFailedPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now),
			eq(100)
		)).willReturn(List.of(target));

		given(outboxEventRepository.markPublishingFromRetryableFailed(
			10L, OutboxEventType.STOCK_RESTORE_REQUESTED, now
		)).willReturn(1);
		given(outboxEventRepository.markSent(10L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(0);

		// when
		OutboxPublishResult result = stockRestoreOutboxRelayUseCase.publishRetryableFailedEvents(now);

		// then
		assertThat(result.getSelectedCount()).isEqualTo(1);
		assertThat(result.getPublishedCount()).isEqualTo(0);
		assertThat(result.getFailedCount()).isEqualTo(0);
		assertThat(result.getSkippedCount()).isEqualTo(1);
	}

	@DisplayName("재시도 가능한 FAILED 이벤트 발행 실패 시 재시도 지연은 최대값으로 제한된다")
	@Test
	void publishRetryableFailedEvents_whenRetryDelayExceedsMax_useMaxRetryDelay() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(50);
		OutboxPublishTarget target = mockTarget(20L, "evt-20");
		given(target.getAttemptCount()).willReturn(40);

		given(outboxEventRepository.findRetryableFailedPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromRetryableFailed(
			20L, OutboxEventType.STOCK_RESTORE_REQUESTED, now
		)).willReturn(1);
		doThrow(new IllegalStateException("retry failed")).when(eventPublisher).publish(any());
		given(outboxEventRepository.markFailed(
			eq(20L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(3600))
		)).willReturn(1);

		// when
		OutboxPublishResult result = stockRestoreOutboxRelayUseCase.publishRetryableFailedEvents(now);

		// then
		assertThat(result.getSelectedCount()).isEqualTo(1);
		assertThat(result.getPublishedCount()).isZero();
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getSkippedCount()).isZero();
		then(outboxEventRepository).should().markFailed(
			eq(20L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(3600))
		);
	}

	@DisplayName("stale PUBLISHING 이벤트를 배치 크기만큼 조회해 FAILED로 복구한다")
	@Test
	void recoverStalePublishingEvents_whenStaleTargetsExist_recoverByIds() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(20);
		List<Long> staleIds = List.of(101L, 102L);

		given(outboxEventRepository.findStalePublishingTargetIds(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now.minusSeconds(300)),
			eq(100)
		)).willReturn(staleIds);

		given(outboxEventRepository.recoverStalePublishingEventsByIds(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(staleIds),
			eq(now.plusSeconds(30)),
			eq("stale publishing timeout")
		)).willReturn(2);

		// when
		int recoveredCount = stockRestoreOutboxRelayUseCase.recoverStalePublishingEvents(now);

		// then
		assertThat(recoveredCount).isEqualTo(2);
	}

	@DisplayName("stale PUBLISHING 대상이 없으면 복구 쿼리를 호출하지 않는다")
	@Test
	void recoverStalePublishingEvents_whenNoTargets_returnZero() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(25);
		given(outboxEventRepository.findStalePublishingTargetIds(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now.minusSeconds(300)),
			eq(100)
		)).willReturn(List.of());

		// when
		int recoveredCount = stockRestoreOutboxRelayUseCase.recoverStalePublishingEvents(now);

		// then
		assertThat(recoveredCount).isZero();
		then(outboxEventRepository).should(times(0)).recoverStalePublishingEventsByIds(
			any(),
			any(),
			any(),
			anyString()
		);
	}

	@DisplayName("MDC가 비어있고 target traceId가 유효하면 publish 호출 시점에 MDC에 복원하고 종료 시 정리한다 (스케줄러 경로)")
	@Test
	void publishPendingEvents_whenMdcEmptyAndTargetHasValidTraceId_restoreMdcAndClear() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget target = mockTarget(30L, "evt-30");
		String traceId = "trace-xyz-999";
		given(target.getTraceId()).willReturn(traceId);

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromPending(30L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markSent(30L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(eventPublisher).publish(any());

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(capturedTraceId.get()).isEqualTo(traceId);
		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("MDC에 이미 traceId가 있으면 target traceId가 유효해도 기존 값을 보존한다 (HTTP 흐름에서 직접 호출 시나리오)")
	@Test
	void publishPendingEvents_whenMdcAlreadyHasTraceId_preservesExistingMdc() {
		// given
		String existingTraceId = "pre-existing-trace";
		String targetTraceId = "target-trace-different";
		LogContext.putTraceId(existingTraceId);
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget target = mockTarget(34L, "evt-34");
		given(target.getTraceId()).willReturn(targetTraceId);

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromPending(34L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markSent(34L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(eventPublisher).publish(any());

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(capturedTraceId.get()).isEqualTo(existingTraceId);
		assertThat(LogContext.getTraceId()).isEqualTo(existingTraceId);
	}

	@DisplayName("MDC에 이미 traceId가 있으면 target traceId가 null이어도 기존 값을 보존한다")
	@Test
	void publishPendingEvents_whenMdcAlreadyHasTraceIdAndTargetTraceIdNull_preservesExistingMdc() {
		// given
		LogContext.putTraceId("pre-existing-trace");
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget target = mockTarget(31L, "evt-31");
		given(target.getTraceId()).willReturn(null);

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromPending(31L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markSent(31L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(eventPublisher).publish(any());

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(capturedTraceId.get()).isEqualTo("pre-existing-trace");
		assertThat(LogContext.getTraceId()).isEqualTo("pre-existing-trace");
	}

	@DisplayName("MDC가 비어있고 target traceId 형식이 유효하지 않으면 MDC를 건드리지 않는다")
	@Test
	void publishPendingEvents_whenMdcEmptyAndTargetTraceIdInvalid_doesNotTouchMdc() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget target = mockTarget(32L, "evt-32");
		given(target.getTraceId()).willReturn("invalid trace id with spaces!");

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromPending(32L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markSent(32L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(eventPublisher).publish(any());

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(capturedTraceId.get()).isNull();
		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("publish 실패 시에도 finally에서 MDC traceId를 정리한다")
	@Test
	void publishPendingEvents_whenPublishFails_clearsMdc() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		OutboxPublishTarget target = mockTarget(33L, "evt-33");
		given(target.getAttemptCount()).willReturn(0);
		given(target.getTraceId()).willReturn("trace-fail-001");

		given(outboxEventRepository.findPendingPublishTargets(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(100)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromPending(33L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		given(outboxEventRepository.markFailed(
			eq(33L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(30))
		)).willReturn(1);
		doThrow(new IllegalStateException("publish failed")).when(eventPublisher).publish(any());

		// when
		stockRestoreOutboxRelayUseCase.publishPendingEvents(now);

		// then
		assertThat(LogContext.getTraceId()).isNull();
	}

	private OutboxPublishTarget mockTarget(Long id, String eventId) {
		OutboxPublishTarget target = mock(OutboxPublishTarget.class);
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		lenient().when(target.getId()).thenReturn(id);
		lenient().when(target.getEventId()).thenReturn(eventId);
		lenient().when(target.getEventType()).thenReturn(OutboxEventType.STOCK_RESTORE_REQUESTED);
		lenient().when(target.getAggregateType()).thenReturn(OutboxAggregateType.ORDER);
		lenient().when(target.getAggregateId()).thenReturn(1000L + id);
		lenient().when(target.getPayload()).thenReturn("{\"orderId\":1}");
		lenient().when(target.getCreatedAt()).thenReturn(now.minusHours(1));
		lenient().when(target.getAttemptCount()).thenReturn(0);
		return target;
	}
}
