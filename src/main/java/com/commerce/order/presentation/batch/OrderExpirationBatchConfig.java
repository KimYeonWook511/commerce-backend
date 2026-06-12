package com.commerce.order.presentation.batch;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import com.commerce.common.exception.CustomException;
import com.commerce.order.application.OrderExpirationService;
import com.commerce.order.application.port.BlockingPaymentChecker;
import com.commerce.order.presentation.batch.listener.OrderExpirationJobListener;
import com.commerce.order.presentation.batch.listener.OrderExpirationItemReadListener;
import com.commerce.order.presentation.batch.listener.OrderExpirationItemWriteListener;
import com.commerce.order.presentation.batch.listener.OrderExpirationRetryListener;
import com.commerce.order.presentation.batch.listener.OrderExpirationSkipListener;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;

@Configuration
public class OrderExpirationBatchConfig {

	@Value("${order.expiration.batch.chunk-size:100}")
	private int chunkSize;

	@Bean
	public Job orderExpirationJob(
		JobRepository jobRepository,
		Step orderExpirationStep,
		OrderExpirationJobListener orderExpirationJobListener
	) {
		return new JobBuilder("orderExpirationJob", jobRepository)
			.listener(orderExpirationJobListener)
			.start(orderExpirationStep)
			.build();
	}

	@Bean
	public Step orderExpirationStep(
		JobRepository jobRepository,
		PlatformTransactionManager transactionManager,
		ItemReader<Order> expiredOrderReader,
		ItemWriter<Order> expiredOrderWriter,
		OrderExpirationRetryListener orderExpirationRetryListener,
		OrderExpirationSkipListener orderExpirationSkipListener,
		OrderExpirationItemReadListener orderExpirationItemReadListener,
		OrderExpirationItemWriteListener orderExpirationItemWriteListener
	) {
		return new StepBuilder("orderExpirationStep", jobRepository)
			.<Order, Order>chunk(chunkSize, transactionManager)
			.reader(expiredOrderReader)
			.writer(expiredOrderWriter)
			.listener(orderExpirationItemReadListener)
			.listener(orderExpirationItemWriteListener)
			.listener(orderExpirationRetryListener)
			.listener(orderExpirationSkipListener)
			.faultTolerant()
			.retry(OptimisticLockingFailureException.class) // writer에서 실패 시 청크 단위 트랜잭션 롤백 -> 청크 재시도
			.retryLimit(3)
			.skip(OptimisticLockingFailureException.class) // 청크에서 문제 아이템만 skip 처리
			.skip(CustomException.class)
			.skipLimit(10)
			.build();
	}

	@Bean
	@StepScope
	public ItemReader<Order> expiredOrderReader(
		OrderRepository orderRepository,
		BlockingPaymentChecker blockingPaymentChecker,
		@Value("#{jobParameters['cutoff']}") String cutoff
	) {
		return new ItemReader<>() {
			private Long lastId = 0L;
			private Iterator<Order> iterator = Collections.emptyIterator();
			private final LocalDateTime cutoffTime = LocalDateTime.parse(cutoff);

			@Override
			public Order read() {
				while (!iterator.hasNext()) {
					List<Order> orders = orderRepository.findExpiredOrdersAfterId(
						OrderStatus.INIT,
						cutoffTime,
						lastId,
						chunkSize
					);
					if (orders.isEmpty()) {
						return null;
					}
					lastId = orders.getLast().getId();
					Set<Long> blocked = blockingPaymentChecker.findOrderIdsWithBlockingPayment(
						orders.stream().map(Order::getId).toList()
					);
					iterator = orders.stream()
						.filter(o -> !blocked.contains(o.getId()))
						.toList()
						.iterator();
				}
				return iterator.next();
			}
		};
	}

	@Bean
	public ItemWriter<Order> expiredOrderWriter(OrderExpirationService orderExpirationService) {
		// return new ItemWriter<Order>() {
		// 	@Override
		// 	public void write(@NonNull Chunk<? extends Order> chunk) throws Exception {
		// 		LocalDateTime requestedAt = LocalDateTime.now();
		// 		for (Order order : chunk) {
		// 			orderExpirationService.expireOrder(order.getId(), requestedAt);
		// 		}
		// 	}
		// };
		return chunk -> {
			LocalDateTime requestedAt = LocalDateTime.now();
			chunk.forEach(order -> orderExpirationService.expireOrder(order.getId(), requestedAt));
		};
	}

	/**
	 * job을 재시도 해야할 필요가 있을 경우
	 */
	// @Bean
	// @StepScope
	// public ItemStreamReader<Order> expiredOrderReader(
	// 	OrderRepository orderRepository,
	// 	@Value("#{jobParameters['cutoff']}") String cutoff
	// ) {
	// 	return new ItemStreamReader<>() {
	// 		private Long lastId = 0L;
	// 		private Iterator<Order> iterator = Collections.emptyIterator();
	// 		private final LocalDateTime cutoffTime = LocalDateTime.parse(cutoff);
	//
	// 		@Override
	// 		public Order read() {
	// 			if (!iterator.hasNext()) {
	// 				List<Order> orders = orderRepository.findExpiredOrdersAfterId(
	// 					OrderStatus.INIT,
	// 					cutoffTime,
	// 					lastId,
	// 					chunkSize
	// 				);
	// 				if (!orders.isEmpty()) {
	// 					lastId = orders.getLast().getId();
	// 					iterator = orders.iterator();
	// 				}
	// 			}
	// 			return iterator.hasNext() ? iterator.next() : null;
	// 		}
	//
	// 		@Override
	// 		public void open(ExecutionContext executionContext) {
	// 			if (executionContext.containsKey("lastId")) {
	// 				lastId = executionContext.getLong("lastId");
	// 			}
	// 		}
	//
	// 		@Override
	// 		public void update(ExecutionContext executionContext) {
	// 			executionContext.putLong("lastId", lastId);
	// 		}
	//
	// 		@Override
	// 		public void close() {
	// 		}
	// 	};
	// }

}
