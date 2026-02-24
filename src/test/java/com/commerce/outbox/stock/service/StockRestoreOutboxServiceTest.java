package com.commerce.outbox.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventStatus;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.repository.OutboxEventRepository;
import com.commerce.outbox.repository.projection.OutboxPublishTarget;
import com.commerce.outbox.stock.mq.StockRestoreKafkaOutboxRelayPublisher;
import com.commerce.outbox.stock.service.command.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.service.result.OutboxPublishResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StockRestoreOutboxServiceTest {

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private StockRestoreKafkaOutboxRelayPublisher outboxRelayPublisher;

	@Mock
	private ObjectMapper objectMapper;

	@InjectMocks
	private StockRestoreOutboxService stockRestoreOutboxService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(stockRestoreOutboxService, "batchSize", 100);
		ReflectionTestUtils.setField(stockRestoreOutboxService, "retryBaseSeconds", 30L);
		ReflectionTestUtils.setField(stockRestoreOutboxService, "retryMaxSeconds", 3600L);
		ReflectionTestUtils.setField(stockRestoreOutboxService, "stalePublishingSeconds", 300L);
	}

	@DisplayName("재고 복구 outbox 이벤트 생성 시 PENDING 이벤트를 저장한다")
	@Test
	void createOutboxEvent_whenValidCommand_savePendingEvent() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		LocalDateTime requestedAt = now.plusHours(1);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(requestedAt)
			.items(List.of(
				StockRestoreOutboxCreateCommand.Item.builder().productId(1L).quantity(2).build()
			))
			.build();
		given(objectMapper.writeValueAsString(any())).willReturn("{\"orderId\":100}");

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

		// when
		stockRestoreOutboxService.createOutboxEvent(command);

		// then
		then(outboxEventRepository).should().save(captor.capture());
		OutboxEvent saved = captor.getValue();
		assertThat(saved.getEventId()).isNotBlank();
		assertThat(saved.getEventType()).isEqualTo(OutboxEventType.STOCK_RESTORE_REQUESTED);
		assertThat(saved.getAggregateType()).isEqualTo(OutboxAggregateType.ORDER);
		assertThat(saved.getAggregateId()).isEqualTo(100L);
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getAttemptCount()).isZero();
		assertThat(saved.getNextRetryAt()).isEqualTo(requestedAt);
		assertThat(saved.getPayload()).isEqualTo("{\"orderId\":100}");
	}

	@DisplayName("재고 복구 outbox 이벤트 생성 시 payload 직렬화에 실패하면 예외가 발생한다")
	@Test
	void createOutboxEvent_whenSerializeFails_throwIllegalStateException() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
		StockRestoreOutboxCreateCommand command = StockRestoreOutboxCreateCommand.builder()
			.orderId(100L)
			.requestedAt(now.plusHours(1).plusMinutes(5))
			.items(List.of())
			.build();
		given(objectMapper.writeValueAsString(any())).willThrow(new JsonProcessingException("boom") {});

		// when // then
		assertThatThrownBy(() -> stockRestoreOutboxService.createOutboxEvent(command))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to serialize outbox payload");
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

		given(outboxEventRepository.findPendingPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			ArgumentMatchers.any(Pageable.class)
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
			.when(outboxRelayPublisher)
			.publish(any());

		// when
		OutboxPublishResult result = stockRestoreOutboxService.publishPendingEvents(now);

		// then
		assertThat(result.getSelectedCount()).isEqualTo(3);
		assertThat(result.getPublishedCount()).isEqualTo(1);
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getSkippedCount()).isEqualTo(1);

		then(outboxRelayPublisher).should(times(2)).publish(any());
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
		given(outboxEventRepository.findPendingPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of());

		// when
		OutboxPublishResult result = stockRestoreOutboxService.publishPendingEvents(now);

		// then
		assertThat(result.getSelectedCount()).isZero();
		assertThat(result.getPublishedCount()).isZero();
		assertThat(result.getFailedCount()).isZero();
		assertThat(result.getSkippedCount()).isZero();
		then(outboxRelayPublisher).shouldHaveNoInteractions();
	}

	@DisplayName("발행 실패 후 markFailed가 실패해도 예외를 던지지 않고 failed로 집계한다")
	@Test
	void publishPendingEvents_whenMarkFailedFails_countFailedWithoutThrowing() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(30);
		OutboxPublishTarget failedTarget = mockTarget(11L, "evt-11");
		given(failedTarget.getAttemptCount()).willReturn(0);
		given(outboxEventRepository.findPendingPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of(failedTarget));
		given(outboxEventRepository.markPublishingFromPending(11L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		doThrow(new IllegalStateException("publish failed"))
			.when(outboxRelayPublisher)
			.publish(any());
		given(outboxEventRepository.markFailed(
			eq(11L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(30))
		)).willThrow(new IllegalStateException("mark failed"));

		// when
		OutboxPublishResult result = stockRestoreOutboxService.publishPendingEvents(now);

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
		given(outboxEventRepository.findPendingPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of(failedTarget));
		given(outboxEventRepository.markPublishingFromPending(12L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(1);
		doThrow(new IllegalStateException("x".repeat(1500)))
			.when(outboxRelayPublisher)
			.publish(any());

		ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
		given(outboxEventRepository.markFailed(
			eq(12L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			errorCaptor.capture(),
			eq(now.plusSeconds(30))
		)).willReturn(1);

		// when
		stockRestoreOutboxService.publishPendingEvents(now);

		// then
		assertThat(errorCaptor.getValue()).hasSize(1000);
	}

	@DisplayName("재시도 가능한 FAILED 이벤트 발행 후 SENT 반영 실패 시 skipped로 집계한다")
	@Test
	void publishRetryableFailedEvents_whenMarkSentFails_countSkipped() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0, 0).plusMinutes(10);
		OutboxPublishTarget target = mockTarget(10L, "evt-retry");

		given(outboxEventRepository.findRetryableFailedPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now),
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of(target));

		given(outboxEventRepository.markPublishingFromRetryableFailed(
			10L, OutboxEventType.STOCK_RESTORE_REQUESTED, now
		)).willReturn(1);
		given(outboxEventRepository.markSent(10L, OutboxEventType.STOCK_RESTORE_REQUESTED)).willReturn(0);

		// when
		OutboxPublishResult result = stockRestoreOutboxService.publishRetryableFailedEvents(now);

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

		given(outboxEventRepository.findRetryableFailedPublishTargetProjections(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(now),
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of(target));
		given(outboxEventRepository.markPublishingFromRetryableFailed(
			20L, OutboxEventType.STOCK_RESTORE_REQUESTED, now
		)).willReturn(1);
		doThrow(new IllegalStateException("retry failed")).when(outboxRelayPublisher).publish(any());
		given(outboxEventRepository.markFailed(
			eq(20L),
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			anyString(),
			eq(now.plusSeconds(3600))
		)).willReturn(1);

		// when
		OutboxPublishResult result = stockRestoreOutboxService.publishRetryableFailedEvents(now);

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
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(staleIds);

		given(outboxEventRepository.recoverStalePublishingEventsByIds(
			eq(OutboxEventType.STOCK_RESTORE_REQUESTED),
			eq(staleIds),
			eq(now.plusSeconds(30)),
			eq("stale publishing timeout")
		)).willReturn(2);

		// when
		int recoveredCount = stockRestoreOutboxService.recoverStalePublishingEvents(now);

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
			ArgumentMatchers.any(Pageable.class)
		)).willReturn(List.of());

		// when
		int recoveredCount = stockRestoreOutboxService.recoverStalePublishingEvents(now);

		// then
		assertThat(recoveredCount).isZero();
		then(outboxEventRepository).should(times(0)).recoverStalePublishingEventsByIds(
			any(),
			any(),
			any(),
			anyString()
		);
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
