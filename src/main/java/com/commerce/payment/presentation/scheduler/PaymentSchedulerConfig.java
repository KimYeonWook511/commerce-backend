package com.commerce.payment.presentation.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 결제 후처리가 쓰는 전용 스레드 풀. 이 도메인의 배치만 이 풀에서 돈다.
 *
 * <p>공용 풀을 키우는 것으로는 갈라지지 않는다. 결제 후처리는 전부 같은 결제사를 부르므로 결제사가
 * 느려지면 여섯이 함께 느려지고, 늘린 칸을 그 여섯이 다 차지해 주문 만료와 재고 복구가 그대로 밀린다.
 * 풀을 나눠야 다른 도메인이 쓸 스레드가 남는다.
 *
 * <p>진입점과 같은 자리에 둔다. 이 풀은 도메인 정책이 아니라 그 진입 방식에 딸린 실행 설정이라,
 * 도메인 정책을 등록하는 자리에 섞으면 그 자리의 뜻이 흐려진다.
 */
@Configuration
@Profile("!test")
public class PaymentSchedulerConfig {

	/** 진입점이 이 이름으로 풀을 가리킨다. 양쪽에 문자열을 따로 적으면 오타가 조용히 공용 풀로 되돌린다 */
	public static final String SCHEDULER_BEAN = "paymentPostProcessTaskScheduler";

	@Bean(SCHEDULER_BEAN)
	public TaskScheduler paymentPostProcessTaskScheduler(
		@Value("${payment.postprocess.scheduler.pool-size}") int poolSize
	) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(poolSize);
		// 밀릴 때 어느 도메인이 스레드를 쥐고 있는지 로그로 가른다.
		scheduler.setThreadNamePrefix("payment-postprocess-");
		return scheduler;
	}
}
