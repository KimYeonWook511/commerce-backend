package com.commerce.test.support;

import java.util.Arrays;
import java.util.Comparator;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.member.infrastructure.JpaMemberRepository;
import com.commerce.order.infrastructure.JpaOrderItemRepository;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.outbox.repository.OutboxEventRepository;
import com.commerce.outbox.repository.ProcessedEventRepository;
import com.commerce.payment.infrastructure.JpaPaymentAttemptRepository;
import com.commerce.payment.infrastructure.JpaPaymentRepository;
import com.commerce.product.infrastructure.JpaProductRepository;
import com.commerce.stock.infrastructure.JpaStockHistoryRepository;
import com.commerce.stock.infrastructure.JpaStockRepository;

import lombok.RequiredArgsConstructor;

@TestComponent
public class PersistenceCleanupTestSupport {

	public void deleteAllInBatch(PersistenceTestSupport... supports) {
		Arrays.stream(supports)
			.sorted(Comparator.comparingInt(support -> support.cleanupOrder().value()))
			.forEach(PersistenceTestSupport::deleteAllInBatch);
	}
}
