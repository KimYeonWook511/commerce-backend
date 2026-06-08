package com.commerce.outbox.infrastructure;

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
class JpaProcessedEventRepositoryTest {

	@Autowired
	private JpaProcessedEventRepository jpaProcessedEventRepository;

	@DisplayName("eventId와 consumerType 조합은 유일해야 한다")
	@Test
	void save_whenEventIdAndConsumerTypeDuplicated_throwException() {
		// given
		jpaProcessedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		);

		// when & then
		// H2와 MySQL 모두 DataIntegrityViolationException이 발생한다.
		assertThatThrownBy(() -> jpaProcessedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
