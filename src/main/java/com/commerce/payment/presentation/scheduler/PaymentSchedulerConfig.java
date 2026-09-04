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
 *
 * <p>그 안에서 대사를 다시 나눈다. 대사는 한 주기에 대상을 다 처리하므로 밀린 만큼 스레드를 오래 잡는데,
 * 같은 풀에 두면 그동안 환불 발송이 스레드를 얻지 못한다. 발송이 멈추면 접수된 환불이 안 나가 회원 돈이
 * 그대로 묶인다. 주기 실행은 자기 자신이 겹치지는 않지만 그것이 다른 진입점의 굶주림까지 막아 주지는
 * 않는다.
 */
@Configuration
public class PaymentSchedulerConfig {

	/** 진입점이 이 이름으로 풀을 가리킨다. 양쪽에 문자열을 따로 적으면 오타가 조용히 공용 풀로 되돌린다 */
	public static final String SCHEDULER_BEAN = "paymentPostProcessTaskScheduler";

	/** 대사 진입점이 가리키는 풀. 오래 잡는 쪽을 여기 몰아 나머지 후처리가 굶지 않게 한다 */
	public static final String RECONCILE_SCHEDULER_BEAN = "paymentReconcileTaskScheduler";

	@Bean(SCHEDULER_BEAN)
	public TaskScheduler paymentPostProcessTaskScheduler(
		ThreadPoolTaskSchedulerBuilder builder,
		@Value("${payment.postprocess.scheduler.pool-size}") int poolSize
	) {
		// 밀릴 때 어느 도메인이 스레드를 쥐고 있는지 로그로 가른다.
		return builder.poolSize(poolSize).threadNamePrefix("payment-postprocess-").build();
	}

	/**
	 * 결제 대사와 환불 대사가 서로도 밀리지 않을 만큼 둔다. 하나로 줄이면 결제 대사가 오래 도는 동안
	 * 환불 회수가 통째로 멈추는데, 그쪽은 이미 나간 돈을 되돌리는 자리라 결제 대사에 밀려서는 안 된다.
	 */
	@Bean(RECONCILE_SCHEDULER_BEAN)
	public TaskScheduler paymentReconcileTaskScheduler(
		ThreadPoolTaskSchedulerBuilder builder,
		@Value("${payment.postprocess.scheduler.reconcile-pool-size}") int poolSize
	) {
		return builder.poolSize(poolSize).threadNamePrefix("payment-reconcile-").build();
	}
}
