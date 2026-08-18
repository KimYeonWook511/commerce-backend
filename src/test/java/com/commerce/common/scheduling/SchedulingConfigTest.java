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
			PaymentSchedulerConfig.SCHEDULER_BEAN);
	}
}
