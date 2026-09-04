package com.commerce.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.payment.presentation.scheduler.PaymentSchedulerConfig;

@SpringBootTest
@ActiveProfiles("test")
class SchedulingConfigTest {

	@Autowired
	private ApplicationContext context;

	@DisplayName("전용 풀이 있어도 자기 풀을 지정하지 않은 주기 실행이 갈 공용 풀이 남아 있다")
	@Test
	void taskSchedulers_whenDedicatedPoolExists_keepDefaultNamedPool() {
		Map<String, TaskScheduler> pools = context.getBeansOfType(TaskScheduler.class);

		// 공용 풀이 사라지면 남은 하나가 결제 전용 풀이 되어 다른 도메인의 배치가 그리로 흘러든다.
		// 스프링은 이 타입의 빈이 있으면 공용 풀을 만들지 않으므로 저절로 생기지 않는다.
		assertThat(pools).containsKeys(
			ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME,
			PaymentSchedulerConfig.SCHEDULER_BEAN,
			PaymentSchedulerConfig.RECONCILE_SCHEDULER_BEAN);
	}

	@DisplayName("대사 풀과 나머지 후처리 풀이 스레드를 나눠 갖는다")
	@Test
	void reconcilePool_whenResolved_isSeparateInstanceFromPostProcessPool() {
		Map<String, TaskScheduler> pools = context.getBeansOfType(TaskScheduler.class);

		// 두 이름이 같은 빈을 가리키면 대사가 밀릴 때 환불 발송이 스레드를 얻지 못한다. 이름만 비교하는
		// 검사는 상수 값을 같게 바꿔도 통과하므로 실제 인스턴스가 다른지를 여기서 본다.
		assertThat(pools.get(PaymentSchedulerConfig.RECONCILE_SCHEDULER_BEAN))
			.isNotSameAs(pools.get(PaymentSchedulerConfig.SCHEDULER_BEAN));
	}
}
