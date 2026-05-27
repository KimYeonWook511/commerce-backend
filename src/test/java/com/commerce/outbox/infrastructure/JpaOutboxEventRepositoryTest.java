package com.commerce.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.common.util.UlidGenerator;
import com.commerce.outbox.domain.OutboxAggregateType;
import com.commerce.outbox.domain.OutboxEvent;
import com.commerce.outbox.domain.OutboxEventStatus;
import com.commerce.outbox.domain.OutboxEventType;
import com.commerce.outbox.domain.OutboxPublishTarget;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class JpaOutboxEventRepositoryTest {

	@Autowired
	private JpaOutboxEventRepository jpaOutboxEventRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("PENDING 발행 대상 조회 시 eventType과 상태가 일치하는 이벤트만 ID 오름차순으로 반환한다")
	@Test
	void findPendingPublishTargets_whenConditionsMatch_returnPendingTargets() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0);
		OutboxEvent firstPending = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PENDING, now);
		OutboxEvent secondPending = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PENDING, now.plusMinutes(1));
		saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.plusMinutes(2));
		entityManager.flush();
		entityManager.clear();

		// when
		List<OutboxPublishTarget> targets = jpaOutboxEventRepository.findPendingPublishTargets(
			OutboxEventType.STOCK_RESTORE_REQUESTED,
			PageRequest.of(0, 10)
		);

		// then
		assertThat(targets).hasSize(2);
		assertThat(targets.get(0).getId()).isEqualTo(firstPending.getId());
		assertThat(targets.get(1).getId()).isEqualTo(secondPending.getId());
		assertThat(targets).allSatisfy(target -> {
			assertThat(target.getEventType()).isEqualTo(OutboxEventType.STOCK_RESTORE_REQUESTED);
			assertThat(target.getAggregateType()).isEqualTo(OutboxAggregateType.ORDER);
			assertThat(target.getPayload()).isNotBlank();
			assertThat(target.getCreatedAt()).isNotNull();
		});
	}

	@DisplayName("재시도 가능한 FAILED 발행 대상 조회 시 nextRetryAt이 지난 이벤트만 반환한다")
	@Test
	void findRetryableFailedPublishTargets_whenRetryTimeReached_returnTargets() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 1, 0);
		OutboxEvent retryable = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.minusSeconds(1));
		saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.plusSeconds(1));
		saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PENDING, now.minusSeconds(5));
		saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now.minusSeconds(5));
		entityManager.flush();
		entityManager.clear();

		// when
		List<OutboxPublishTarget> targets = jpaOutboxEventRepository.findRetryableFailedPublishTargets(
			OutboxEventType.STOCK_RESTORE_REQUESTED,
			now,
			PageRequest.of(0, 10)
		);

		// then
		assertThat(targets).hasSize(1);
		assertThat(targets.getFirst().getId()).isEqualTo(retryable.getId());
		assertThat(targets.getFirst().getAttemptCount()).isEqualTo(0);
	}

	@DisplayName("PENDING 상태 선점은 상태와 이벤트 타입이 일치할 때만 PUBLISHING으로 변경된다")
	@Test
	void markPublishingFromPending_whenMatch_updatePublishing() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 2, 0);
		OutboxEvent target = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PENDING, now);
		entityManager.flush();
		entityManager.clear();

		// when
		int updated = jpaOutboxEventRepository.markPublishingFromPending(
			target.getId(),
			OutboxEventType.STOCK_RESTORE_REQUESTED
		);

		// then
		assertThat(updated).isEqualTo(1);
		OutboxEvent updatedEvent = jpaOutboxEventRepository.findById(target.getId()).orElseThrow();
		assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHING);
		assertThat(updatedEvent.getLastError()).isNull();
	}

	@DisplayName("PENDING 상태 선점은 FAILED 상태 이벤트를 변경하지 않는다")
	@Test
	void markPublishingFromPending_whenStatusMismatch_returnZero() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 2, 0);
		OutboxEvent target = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.plusMinutes(1));
		entityManager.flush();
		entityManager.clear();

		// when
		int updated = jpaOutboxEventRepository.markPublishingFromPending(
			target.getId(),
			OutboxEventType.STOCK_RESTORE_REQUESTED
		);

		// then
		assertThat(updated).isEqualTo(0);
	}

	@DisplayName("재시도 가능한 FAILED 상태 선점은 nextRetryAt이 지난 경우에만 PUBLISHING으로 변경된다")
	@Test
	void markPublishingFromRetryableFailed_whenRetryable_updatePublishing() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 2, 10);
		OutboxEvent retryable = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.minusSeconds(1));
		OutboxEvent notRetryable = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.plusSeconds(30));
		entityManager.flush();
		entityManager.clear();

		// when
		int updatedRetryable = jpaOutboxEventRepository.markPublishingFromRetryableFailed(
			retryable.getId(), OutboxEventType.STOCK_RESTORE_REQUESTED, now);
		int updatedNotRetryable = jpaOutboxEventRepository.markPublishingFromRetryableFailed(
			notRetryable.getId(), OutboxEventType.STOCK_RESTORE_REQUESTED, now);

		// then
		assertThat(updatedRetryable).isEqualTo(1);
		assertThat(updatedNotRetryable).isEqualTo(0);
		assertThat(jpaOutboxEventRepository.findById(retryable.getId()).orElseThrow().getStatus())
			.isEqualTo(OutboxEventStatus.PUBLISHING);
		assertThat(jpaOutboxEventRepository.findById(notRetryable.getId()).orElseThrow().getStatus())
			.isEqualTo(OutboxEventStatus.FAILED);
	}

	@DisplayName("SENT 반영은 PUBLISHING 상태일 때만 완료 상태와 publishedAt을 업데이트한다")
	@Test
	void markSent_whenPublishing_updateSentAndPublishedAt() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 2, 20);
		OutboxEvent target = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now);
		ReflectionTestUtils.setField(target, "lastError", "old error");
		jpaOutboxEventRepository.saveAndFlush(target);
		entityManager.clear();

		// when
		int updated = jpaOutboxEventRepository.markSent(target.getId(), OutboxEventType.STOCK_RESTORE_REQUESTED);

		// then
		assertThat(updated).isEqualTo(1);
		OutboxEvent updatedEvent = jpaOutboxEventRepository.findById(target.getId()).orElseThrow();
		assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.SENT);
		assertThat(updatedEvent.getPublishedAt()).isNotNull();
		assertThat(updatedEvent.getLastError()).isNull();
	}

	@DisplayName("FAILED 반영은 PUBLISHING 상태일 때만 재시도 정보와 에러 메시지를 업데이트한다")
	@Test
	void markFailed_whenPublishing_updateFailedStateAndRetryInfo() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 2, 30);
		OutboxEvent target = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now);
		ReflectionTestUtils.setField(target, "attemptCount", 2);
		jpaOutboxEventRepository.saveAndFlush(target);
		entityManager.clear();

		// when
		LocalDateTime nextRetryAt = now.plusMinutes(30);
		int updated = jpaOutboxEventRepository.markFailed(
			target.getId(),
			OutboxEventType.STOCK_RESTORE_REQUESTED,
			"publish failed",
			nextRetryAt
		);

		// then
		assertThat(updated).isEqualTo(1);
		OutboxEvent updatedEvent = jpaOutboxEventRepository.findById(target.getId()).orElseThrow();
		assertThat(updatedEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(updatedEvent.getAttemptCount()).isEqualTo(3);
		assertThat(updatedEvent.getLastError()).isEqualTo("publish failed");
		assertThat(updatedEvent.getNextRetryAt()).isEqualTo(nextRetryAt);
	}

	@DisplayName("stale PUBLISHING 대상 조회 시 updatedAt 기준으로 오래된 이벤트만 ID 오름차순으로 반환한다")
	@Test
	void findStalePublishingTargetIds_whenStaleExists_returnIds() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 4, 5);
		OutboxEvent staleFirst = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now.minusHours(1).minusMinutes(5));
		OutboxEvent staleSecond = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now.minusHours(1));
		OutboxEvent fresh = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now.minusMinutes(55));
		jpaOutboxEventRepository.flush();
		LocalDateTime staleThreshold = now.minusMinutes(5);
		updateUpdatedAt(staleFirst.getId(), staleThreshold.minusMinutes(10));
		updateUpdatedAt(staleSecond.getId(), staleThreshold.minusMinutes(5));
		updateUpdatedAt(fresh.getId(), staleThreshold.plusMinutes(1));
		entityManager.clear();

		// when
		List<Long> ids = jpaOutboxEventRepository.findStalePublishingTargetIds(
			OutboxEventType.STOCK_RESTORE_REQUESTED,
			staleThreshold,
			PageRequest.of(0, 10)
		);

		// then
		assertThat(ids).containsExactly(staleFirst.getId(), staleSecond.getId());
	}

	@DisplayName("stale 복구 bulk update는 대상 ID와 이벤트 타입이 일치하는 PUBLISHING만 FAILED로 변경한다")
	@Test
	void recoverStalePublishingEventsByIds_whenMatch_updateOnlyTargets() {
		// given
		LocalDateTime now = LocalDateTime.of(2000, 1, 1, 4, 0);
		OutboxEvent first = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now);
		OutboxEvent second = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.PUBLISHING, now.plusMinutes(1));
		OutboxEvent alreadyFailed = saveOutboxEvent(
			OutboxEventType.STOCK_RESTORE_REQUESTED, OutboxEventStatus.FAILED, now.plusMinutes(2));
		entityManager.flush();
		entityManager.clear();

		// when
		LocalDateTime nextRetryAt = now.plusHours(1);
		int updated = jpaOutboxEventRepository.recoverStalePublishingEventsByIds(
			OutboxEventType.STOCK_RESTORE_REQUESTED,
			List.of(first.getId(), second.getId(), alreadyFailed.getId()),
			nextRetryAt,
			"stale publishing timeout"
		);

		// then
		assertThat(updated).isEqualTo(2);

		OutboxEvent updatedFirst = jpaOutboxEventRepository.findById(first.getId()).orElseThrow();
		OutboxEvent updatedSecond = jpaOutboxEventRepository.findById(second.getId()).orElseThrow();
		OutboxEvent unchangedFailed = jpaOutboxEventRepository.findById(alreadyFailed.getId()).orElseThrow();
		assertThat(updatedFirst.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(updatedSecond.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(updatedFirst.getAttemptCount()).isEqualTo(1);
		assertThat(updatedSecond.getAttemptCount()).isEqualTo(1);
		assertThat(updatedFirst.getNextRetryAt()).isEqualTo(nextRetryAt);
		assertThat(updatedFirst.getLastError()).isEqualTo("stale publishing timeout");

		assertThat(unchangedFailed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}

	private OutboxEvent saveOutboxEvent(
		OutboxEventType eventType,
		OutboxEventStatus status,
		LocalDateTime nextRetryAt
	) {
		OutboxEvent event = OutboxEvent.createPending(
			eventId(),
			eventType,
			"{\"orderId\":1}",
			nextRetryAt,
			OutboxAggregateType.ORDER,
			100L,
			null
		);
		ReflectionTestUtils.setField(event, "status", status);
		return jpaOutboxEventRepository.save(event);
	}

	private String eventId() {
		return UlidGenerator.generate();
	}

	private void updateUpdatedAt(Long id, LocalDateTime updatedAt) {
		entityManager.createNativeQuery("update tbl_outbox_event set updated_at = :updatedAt where id = :id")
			.setParameter("updatedAt", updatedAt)
			.setParameter("id", id)
			.executeUpdate();
		entityManager.flush();
	}
}
