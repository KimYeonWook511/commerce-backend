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
		// H2 한계로 DataIntegrityViolationException 타입으로 어서션한다.
		// 실제 MySQL 환경에서는 DuplicateKeyException이 발생함 — UniqueConstraintViolationIntegrationTest(dockerTest)로 보완.
		assertThatThrownBy(() -> jpaProcessedEventRepository.saveAndFlush(
			ProcessedEvent.create("01ARZ3NDEKTSV4RRFFQ69G5FAV", ProcessedEventConsumerType.STOCK_RESTORE)
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
