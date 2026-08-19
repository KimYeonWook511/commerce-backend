package com.commerce.payment.presentation.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/**
 * 결제 후처리가 쓰는 전용 스레드 풀. 이 도메인의 배치만 이 풀에서 돈다.
 *
 * <p>공용 풀을 키우는 것으로도 당장은 되지만 그 크기가 결제 배치 수에 묶인다. 배치를 하나 더 붙일
 * 때마다 공용 풀을 함께 올려야 하고, 안 올리면 조용히 다시 밀린다. 그 연결은 코드 어디에도 드러나지
 * 않는다. 풀을 나누면 결제 쪽 숫자를 바꿔도 다른 도메인이 영향을 받지 않는다.
 *
 * <p>이 풀이 생기면 스프링이 공용 풀을 더 이상 만들지 않는다. 그것을 {@code common.scheduling}이
 * 직접 만들어 두므로, 그 자리가 사라지면 다른 도메인의 배치가 이 풀로 흘러들어 격리가 무너진다.
 *
 * <p>진입점과 같은 자리에 둔다. 이 풀은 도메인 정책이 아니라 그 진입 방식에 딸린 실행 설정이라,
 * 도메인 정책을 등록하는 자리에 섞으면 그 자리의 뜻이 흐려진다.
 */
@Configuration
public class PaymentSchedulerConfig {

	/** 진입점이 이 이름으로 풀을 가리킨다. 양쪽에 문자열을 따로 적으면 오타가 조용히 공용 풀로 되돌린다 */
	public static final String SCHEDULER_BEAN = "paymentPostProcessTaskScheduler";

	@Bean(SCHEDULER_BEAN)
	public TaskScheduler paymentPostProcessTaskScheduler(
		ThreadPoolTaskSchedulerBuilder builder,
		@Value("${payment.postprocess.scheduler.pool-size}") int poolSize
	) {
		// 밀릴 때 어느 도메인이 스레드를 쥐고 있는지 로그로 가른다.
		return builder.poolSize(poolSize).threadNamePrefix("payment-postprocess-").build();
	}
}
