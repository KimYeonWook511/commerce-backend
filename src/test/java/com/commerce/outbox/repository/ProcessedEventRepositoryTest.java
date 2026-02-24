package com.commerce.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.ProcessedEventConsumerType;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class ProcessedEventRepositoryTest {

	@Autowired
	private ProcessedEventRepository processedEventRepository;

	@DisplayName("eventId와 consumerType이 같으면 처리 이력 존재 여부를 조회할 수 있다")
	@Test
	void existsByEventIdAndConsumerType_whenProcessedEventExists_returnTrue() {
		// given
		processedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		);

		// when
		boolean exists = processedEventRepository.existsByEventIdAndConsumerType(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ProcessedEventConsumerType.STOCK_RESTORE
		);

		// then
		assertThat(exists).isTrue();
	}

	@DisplayName("eventId와 consumerType 조합은 유일해야 한다")
	@Test
	void save_whenEventIdAndConsumerTypeDuplicated_throwException() {
		// given
		processedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		);

		// when & then
		assertThatThrownBy(() -> processedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
